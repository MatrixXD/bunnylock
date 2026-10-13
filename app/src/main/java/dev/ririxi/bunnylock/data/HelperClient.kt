package dev.ririxi.bunnylock.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs RootServer in app_process inside a shell and talks JSON to it.
 * Every request carries an id, and the reader thread hands each reply to the call that waits for that id.
 */
abstract class HelperClient(
    private val context: Context,
    private val name: String,
) : PrivilegedClient {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val pendingReplies = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val lastRequestId = AtomicInteger(0)

    override val running get() = process?.isAlive == true

    protected open val startFailed: String get() = "The $name couldn't start."

    protected open val startTimedOut: String get() = "The $name didn't start in time."

    protected abstract fun openShell(): Process

    protected open fun exited() {}

    override suspend fun start(): JSONObject =
        withContext(Dispatchers.IO) {
            stop()
            val appInfo = context.applicationInfo
            val shell = openShell()
            val helperStdin = shell.outputStream.bufferedWriter()
            val helperStdout = shell.inputStream.bufferedReader()
            // The helper's stderr is only drained: a pipe that nobody reads would block the helper once it fills up.
            thread(name = "bunny-helper-stderr", isDaemon = true) {
                runCatching { shell.errorStream.bufferedReader().forEachLine { } }
            }
            // exec replaces the shell with the helper, so the shell's pipes become the helper's.
            helperStdin.write("export CLASSPATH='${appInfo.sourceDir}'\n")
            helperStdin
                .write(
                    "exec /system/bin/app_process /system/bin --nice-name=bunnylockd dev.ririxi.bunnylock.root.RootServer '${appInfo.nativeLibraryDir}'\n",
                )
            helperStdin.flush()

            val ready = CompletableDeferred<JSONObject>()
            startReplyReader(helperStdout, ready)

            val hello =
                try {
                    withTimeout(20_000) { ready.await() }
                } catch (e: Exception) {
                    shell.destroy()
                    if (e is TimeoutCancellationException) throw RootException(startTimedOut)
                    throw RootException(e.message ?: startFailed)
                }
            process = shell
            writer = helperStdin
            hello
        }

    /**
     * Reads the helper's output for as long as it lives: the hello line completes [ready], every other line
     * answers the pending call with the same id. The helper's stdout closing is how its death is noticed,
     * and everything still waiting then fails.
     */
    private fun startReplyReader(
        helperStdout: BufferedReader,
        ready: CompletableDeferred<JSONObject>,
    ) {
        thread(name = "bunny-helper-reader", isDaemon = true) {
            try {
                while (true) {
                    val line = helperStdout.readLine() ?: break
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (json.has("ready")) {
                        ready.complete(json)
                    } else {
                        pendingReplies.remove(json.optInt("id"))?.complete(json)
                    }
                }
            } catch (_: IOException) {
                // Destroying the process can break the pipe in the middle of a read, which ends the loop like the end of the stream does.
            }
            val helperStopped = RootException("The $name stopped unexpectedly.")
            ready.completeExceptionally(RootException(startFailed))
            pendingReplies.values.forEach { it.completeExceptionally(helperStopped) }
            pendingReplies.clear()
            exited()
        }
    }

    override suspend fun call(
        cmd: String,
        timeoutMs: Long,
        build: JSONObject.() -> Unit,
    ): JSONObject {
        val helperStdin = writer ?: throw RootException("The $name isn't running.")
        val id = lastRequestId.incrementAndGet()
        val request = JSONObject().put("id", id).put("cmd", cmd).apply(build)
        val reply = CompletableDeferred<JSONObject>()
        pendingReplies[id] = reply
        val response =
            try {
                withContext(Dispatchers.IO) {
                    // Calls can overlap, and each request has to reach the helper as one unbroken line.
                    synchronized(helperStdin) {
                        helperStdin.write(request.toString())
                        helperStdin.write("\n")
                        helperStdin.flush()
                    }
                }
                withTimeout(timeoutMs) { reply.await() }
            } catch (_: TimeoutCancellationException) {
                throw RootException("The $name didn't answer in time.")
            } catch (_: IOException) {
                // The pipe to a helper that has died breaks on the write.
                throw RootException("The $name stopped unexpectedly.")
            } finally {
                pendingReplies.remove(id)
            }
        if (!response.optBoolean("ok")) throw RootException(response.optString("error", "Something went wrong."))
        return response.optJSONObject("data") ?: JSONObject()
    }

    override fun stop() {
        // A helper that is already gone can't take the exit command. Destroying the process covers that case.
        runCatching {
            writer?.apply {
                write("{\"cmd\":\"exit\"}\n")
                flush()
            }
        }
        process?.destroy()
        process = null
        writer = null
    }
}

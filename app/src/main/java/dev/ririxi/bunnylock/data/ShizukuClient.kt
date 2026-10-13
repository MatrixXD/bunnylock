package dev.ririxi.bunnylock.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.ririxi.bunnylock.root.ShizukuService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs the helper through Shizuku: as a UserService, or in a Shizuku shell where the service won't start (RikkaApps/Shizuku#1171).
 * [onLost] reports a helper that died after it connected.
 */
class ShizukuClient(
    private val context: Context,
    private val onLost: () -> Unit,
) : PrivilegedClient {
    private var service: ShizukuService.Proxy? = null
    private var connection: ServiceConnection? = null
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var shell: ShellHelper? = null

    private val prefs = context.getSharedPreferences("bunnylock", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun serviceArgs() =
        Shizuku
            .UserServiceArgs(ComponentName(context.packageName, ShizukuService::class.java.name))
            // Not a daemon: Shizuku stops the helper once the app's process is gone.
            .daemon(false)
            .processNameSuffix("bunnylockd")
            // A fresh tag per bind, so an earlier helper's death can't drop this connection.
            .tag("bunnylockd-${++lastBind}")
            // Shizuku keeps a helper that is already running while this number matches, so the install time stands in: every update gets a fresh one.
            .version((context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000).toInt())

    val inShell: Boolean get() = shell !== null

    override val running: Boolean get() = shell?.running ?: (service?.alive == true)

    override suspend fun start(): JSONObject {
        if (Shizuku.isPreV11()) throw RootException("This Shizuku version is too old. Update Shizuku and try again.")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) requestPermission()
        val shellFirst = prefs.getBoolean(PREF_SHELL_FIRST, false)
        return try {
            if (shellFirst) startInShell() else startAsService()
        } catch (first: Exception) {
            if (first is CancellationException) throw first
            stop()
            val hello =
                try {
                    if (shellFirst) startAsService() else startInShell()
                } catch (second: Exception) {
                    if (second is CancellationException) throw second
                    throw RootException("${first.message} ${second.message}")
                }
            prefs.edit().putBoolean(PREF_SHELL_FIRST, !shellFirst).apply()
            hello
        }
    }

    private suspend fun startAsService(): JSONObject {
        service = bind()
        // The su helper is given this directory on its command line and says hello unasked. This one has to be asked.
        return call("hello") { put("libDir", context.applicationInfo.nativeLibraryDir) }
    }

    private suspend fun startInShell(): JSONObject {
        val helper = ShellHelper()
        val hello = helper.start()
        shell = helper
        return hello
    }

    private inner class ShellHelper : HelperClient(context, "Shizuku helper") {
        override fun openShell(): Process {
            val server = IShizukuService.Stub.asInterface(Shizuku.getBinder()) ?: throw RootException("Shizuku stopped.")
            return try {
                RemoteShell(server.newProcess(arrayOf("/system/bin/sh"), null, null))
            } catch (e: Exception) {
                throw RootException("Shizuku couldn't open a shell (${e.message}).")
            }
        }

        override fun exited() {
            mainHandler.post { if (shell === this) onLost() }
        }
    }

    private suspend fun requestPermission() {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            throw RootException("Shizuku access was denied earlier. Allow bunnylock in the Shizuku app and try again.")
        }
        // Some phones keep Shizuku from showing its prompt, so the wait has to end by itself.
        try {
            withTimeout(60_000) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val listener =
                            object : Shizuku.OnRequestPermissionResultListener {
                                override fun onRequestPermissionResult(
                                    requestCode: Int,
                                    grantResult: Int,
                                ) {
                                    if (requestCode != PERMISSION_REQUEST_CODE) return
                                    Shizuku.removeRequestPermissionResultListener(this)
                                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                        cont.resume(Unit)
                                    } else {
                                        cont.resumeWithException(RootException("Shizuku access was denied."))
                                    }
                                }
                            }
                        Shizuku.addRequestPermissionResultListener(listener)
                        cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw RootException(
                "Shizuku's permission prompt wasn't answered. If it never appeared, allow bunnylock in the Shizuku app and come back.",
            )
        }
    }

    private suspend fun bind(): ShizukuService.Proxy =
        try {
            withTimeout(15_000) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val newConnection =
                            object : ServiceConnection {
                                override fun onServiceConnected(
                                    name: ComponentName?,
                                    binder: IBinder?,
                                ) {
                                    if (!cont.isActive) return
                                    val proxy = binder?.takeIf { it.pingBinder() }?.let { ShizukuService.Proxy(it) }
                                    if (proxy === null) {
                                        cont.resumeWithException(RootException("The Shizuku service started but didn't respond."))
                                    } else {
                                        cont.resume(proxy)
                                    }
                                }

                                override fun onServiceDisconnected(name: ComponentName?) {
                                    // stop() or a newer bind() has replaced this connection, so its disconnect says nothing about the current helper.
                                    if (connection !== this) return
                                    service = null
                                    onLost()
                                }
                            }
                        val args = serviceArgs()
                        connection = newConnection
                        boundArgs = args
                        Shizuku.bindUserService(args, newConnection)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw RootException("The Shizuku service didn't start in time.")
        }

    override suspend fun call(
        cmd: String,
        timeoutMs: Long,
        build: JSONObject.() -> Unit,
    ): JSONObject {
        shell?.let { return it.call(cmd, timeoutMs, build) }
        val proxy = service ?: throw RootException("The Shizuku service isn't running.")
        val request = JSONObject().put("cmd", cmd).apply(build)
        // A binder call can't be interrupted, so one that hangs is left behind on its own thread.
        val reply = CompletableDeferred<String>()
        thread(name = "bunny-shizuku-call", isDaemon = true) {
            reply.completeWith(runCatching { proxy.call(request.toString()) })
        }
        val response =
            try {
                JSONObject(withTimeout(timeoutMs) { reply.await() })
            } catch (_: TimeoutCancellationException) {
                throw RootException("The Shizuku service didn't answer in time.")
            } catch (_: RemoteException) {
                throw RootException("The Shizuku service stopped unexpectedly.")
            }
        if (!response.optBoolean("ok")) throw RootException(response.optString("error", "Something went wrong."))
        return response.optJSONObject("data") ?: JSONObject()
    }

    override fun stop() {
        val shellHelper = shell
        val bound = connection
        val args = boundArgs
        // Cleared first, so the exit or disconnect this causes isn't reported as a lost helper.
        shell = null
        connection = null
        boundArgs = null
        service = null
        shellHelper?.stop()
        // The last argument also removes the service, which ends the helper process.
        if (bound !== null && args !== null) runCatching { Shizuku.unbindUserService(args, bound, true) }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 7031
        private const val PREF_SHELL_FIRST = "shizukuShell"

        private var lastBind = 0

        private fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun installedVersion(context: Context): String? =
            runCatching {
                context.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0).versionName ?: "of unknown version"
            }.getOrNull()

        fun granted(): Boolean =
            runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

        /** One line for the diagnostics: how much of Shizuku this app can see. */
        fun describe(context: Context): String {
            val app = installedVersion(context)?.let { "app $it installed" } ?: "app not installed"
            if (!binderAlive()) return "$app, no connection to its service"
            val kind = if (runCatching { Sui.isSui() }.getOrDefault(false)) "Sui" else "service"
            val server =
                runCatching { "$kind v${Shizuku.getVersion()}.${Shizuku.getServerPatchVersion()} running as uid ${Shizuku.getUid()}" }
                    .getOrElse { "$kind not answering (${it.message})" }
            return "$app, $server, access ${if (granted()) "granted" else "not granted"}"
        }

        /** Runs [block] each time Shizuku's binder arrives from now on, e.g. when Shizuku is started while the app is open. Returns the undo. */
        fun onBinderReceived(block: () -> Unit): () -> Unit {
            val listener = Shizuku.OnBinderReceivedListener { block() }
            Shizuku.addBinderReceivedListener(listener)
            return { Shizuku.removeBinderReceivedListener(listener) }
        }

        /** True when Shizuku is running. Its binder can arrive shortly after app start. */
        suspend fun awaitBinder(timeoutMs: Long = 2000): Boolean {
            if (binderAlive()) return true
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val listener =
                        object : Shizuku.OnBinderReceivedListener {
                            override fun onBinderReceived() {
                                Shizuku.removeBinderReceivedListener(this)
                                if (cont.isActive) cont.resume(true)
                            }
                        }
                    Shizuku.addBinderReceivedListenerSticky(listener)
                    cont.invokeOnCancellation { Shizuku.removeBinderReceivedListener(listener) }
                }
            } ?: false
        }
    }
}

private class RemoteShell(
    private val remote: IRemoteProcess,
) : Process() {
    private val stdin = ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
    private val stdout = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
    private val stderr = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int = remote.waitFor()

    override fun exitValue(): Int = remote.exitValue()

    // The default relies on an exception from exitValue() that doesn't cross the binder call.
    override fun isAlive(): Boolean = runCatching { remote.alive() }.getOrDefault(false)

    override fun destroy() {
        runCatching { remote.destroy() }
        listOf(stdin, stdout, stderr).forEach { runCatching { it.close() } }
    }
}

package dev.ririxi.bunnylock.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import dev.ririxi.bunnylock.root.RootServer
import dev.ririxi.bunnylock.root.ShizukuService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs the same request handler as the su helper inside a Shizuku UserService.
 * [onLost] reports a service that died after it connected.
 */
class ShizukuClient(
    private val context: Context,
    private val onLost: () -> Unit,
) : PrivilegedClient {
    private var service: ShizukuService.Proxy? = null
    private var connection: ServiceConnection? = null

    private val args =
        Shizuku
            .UserServiceArgs(ComponentName(context.packageName, ShizukuService::class.java.name))
            // Not a daemon: Shizuku stops the helper once the app's process is gone.
            .daemon(false)
            .processNameSuffix("bunnylockd")
            // Shizuku keeps a helper that is already running while this number matches, so the install time stands in: every update gets a fresh one.
            .version((context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000).toInt())

    override val running: Boolean get() = service?.alive == true

    override suspend fun start(): JSONObject {
        if (Shizuku.isPreV11()) throw RootException("This Shizuku version is too old. Update Shizuku and try again.")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) requestPermission()
        service = bind()
        // The su helper is given this directory on its command line and says hello unasked. This one has to be asked.
        return call("hello") { put("libDir", context.applicationInfo.nativeLibraryDir) }
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
                        connection = newConnection
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
        val bound = connection
        // Cleared before unbinding, so that the disconnect this causes counts as stale and isn't reported as a lost helper.
        connection = null
        service = null
        // The last argument also removes the service, which ends the helper process.
        if (bound !== null) runCatching { Shizuku.unbindUserService(args, bound, true) }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 7031

        private fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        /** The Shizuku app's version, or null when it isn't installed. */
        fun installedVersion(context: Context): String? =
            runCatching {
                context.packageManager.getPackageInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0).versionName ?: "of unknown version"
            }.getOrNull()

        /** Shizuku is running and has already let bunnylock in. */
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

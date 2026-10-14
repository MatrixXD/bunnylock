package dev.ririxi.bunnylock.util

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Utility that knows whether a voice call is active and provides a safe wrapper to run
 * network-locking actions only when no call is in progress.
 *
 * Usage:
 *   val locker = CallAwareNetworkLocker(context)
 *   locker.start()
 *   locker.tryApplyNetworkLock { /* perform network locking here */ }
 *   locker.stop()
 */
class CallAwareNetworkLocker(private val context: Context) {
    private val tag = "CallAwareNetworkLocker"

    @Volatile
    private var inCall: Boolean = false

    private val tm: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val handlerThread = HandlerThread("call-aware-network-locker").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val nowInCall = (state == TelephonyManager.CALL_STATE_OFFHOOK || state == TelephonyManager.CALL_STATE_RINGING)
            if (inCall != nowInCall) {
                inCall = nowInCall
                Log.d(tag, "Call state changed: inCall=$inCall")
            }
        }
    }

    /**
     * Start listening for call state changes. Call from app lifecycle (e.g., Application.onCreate or component start).
     */
    fun start() {
        try {
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.w(tag, "Missing permission to listen call state: ${e.message}")
        } catch (t: Throwable) {
            Log.w(tag, "Failed to start call listener: ${t.message}")
        }
    }

    /**
     * Stop listening for call state changes and cleanup.
     */
    fun stop() {
        try {
            tm.listen(listener, PhoneStateListener.LISTEN_NONE)
        } catch (ignored: Exception) { }
        try {
            handlerThread.quitSafely()
        } catch (ignored: Exception) { }
    }

    /**
     * Try to apply network locking action. If a call is in progress:
     *  - by default returns false and does not run the action.
     *  - if queueIfInCall==true the action will be queued and executed once the call ends or timeout occurs.
     *
     * Returns true if action was executed (or scheduled immediately) otherwise false.
     */
    fun tryApplyNetworkLock(lockAction: () -> Unit, queueIfInCall: Boolean = false, queueTimeoutMs: Long = 30_000L): Boolean {
        if (!inCall) {
            // safe to run immediately
            handler.post {
                try {
                    lockAction()
                } catch (t: Throwable) {
                    Log.e(tag, "lockAction failed", t)
                }
            }
            return true
        }

        if (!queueIfInCall) {
            Log.i(tag, "In call — skipping network lock")
            return false
        }

        // queue: watch for call end and run action once call ends or timeout
        val start = System.currentTimeMillis()
        val checker = object : Runnable {
            override fun run() {
                if (!inCall) {
                    try {
                        lockAction()
                    } catch (t: Throwable) {
                        Log.e(tag, "Queued lockAction failed", t)
                    }
                    return
                }
                if (System.currentTimeMillis() - start > queueTimeoutMs) {
                    Log.i(tag, "Queued network lock timed out; not applying")
                    return
                }
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(checker)
        Log.i(tag, "In call — queued network lock (will run after call or timeout $queueTimeoutMs ms)")
        return false
    }

    /**
     * Helper: whether a call is currently active (OFFHOOK or RINGING).
     */
    fun isInCall(): Boolean = inCall
}

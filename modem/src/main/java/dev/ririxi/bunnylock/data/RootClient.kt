package dev.ririxi.bunnylock.data

import android.content.Context
import org.json.JSONObject
import java.io.IOException

class RootClient(
    context: Context,
) : HelperClient(context, "root helper") {
    override val startFailed get() = "Root access was denied, or the root helper couldn't start."

    override val startTimedOut get() = "Root access wasn't granted in time. Check your root manager and try again."

    override fun openShell(): Process =
        try {
            ProcessBuilder("su").start()
        } catch (_: IOException) {
            throw RootException("Couldn't find su. bunnylock needs root (Magisk, KernelSU or APatch).")
        }

    override suspend fun start(): JSONObject {
        val hello = super.start()
        val uid = hello.optInt("uid", -1)
        if (uid != 0) {
            stop()
            throw RootException("The root helper isn't running as root (uid $uid).")
        }
        return hello
    }
}

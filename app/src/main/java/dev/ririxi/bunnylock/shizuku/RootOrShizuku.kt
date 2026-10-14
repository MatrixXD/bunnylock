package dev.ririxi.bunnylock.shizuku

import android.content.Context
import dev.ririxi.bunnylock.data.RootClient
import dev.ririxi.bunnylock.data.RootException
import dev.ririxi.bunnylock.modem.Access
import dev.ririxi.bunnylock.modem.HelperStarter
import dev.ririxi.bunnylock.modem.StartedHelper
import dev.ririxi.bunnylock.modem.asClause

class RootOrShizuku(
    private val context: Context,
    private val onShizukuLost: () -> Unit,
) : HelperStarter {
    override suspend fun start(showStep: (String) -> Unit): StartedHelper {
        showStep("Asking for root access...")
        val rootClient = RootClient(context)
        val rootError =
            try {
                return StartedHelper(rootClient, rootClient.start(), Access.ROOT)
            } catch (e: Exception) {
                e.message?.asClause() ?: "root access was denied"
            }
        showStep("Looking for Shizuku...")
        if (!ShizukuClient.awaitBinder()) {
            val shizukuProblem =
                if (ShizukuClient.installedVersion(context) === null) {
                    "Shizuku isn't installed. Grant root, or install and start Shizuku, then try again."
                } else {
                    "Shizuku is installed but hasn't connected to bunnylock, so it probably isn't running. Open Shizuku to check, then try again."
                }
            throw RootException("No root access ($rootError), and $shizukuProblem")
        }
        showStep("Connecting to Shizuku. Allow bunnylock if it asks...")
        val shizukuClient = ShizukuClient(context, onShizukuLost)
        val hello =
            try {
                shizukuClient.start()
            } catch (e: Exception) {
                shizukuClient.stop()
                throw RootException(
                    "No root access ($rootError), and Shizuku didn't work either (${e.message?.asClause() ?: "no reason given"}).",
                )
            }
        val access = if (hello.optInt("uid", -1) == 0) Access.SHIZUKU_ROOT else Access.SHIZUKU_ADB
        val note = if (shizukuClient.inShell) "Shizuku's service for bunnylock doesn't start on this phone, so the helper runs in a Shizuku shell." else null
        return StartedHelper(shizukuClient, hello, access, note)
    }
}

package dev.ririxi.bunnylock.root

import android.os.IBinder
import android.os.Process
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Samsung's ISemTelephony ("isemtelephony"): 5G mode on One UI without root.
 * Modes (Samsung VolteConstants.NR_MODE): 0 SA only, 1 NSA only, 2 SA + NSA.
 */
object Samsung {
    private class SemTelephony(
        private val service: Any,
        val getNrMode: Method,
        val setNrMode: Method,
    ) {
        fun call(
            method: Method,
            vararg args: Any?,
        ): Any? =
            try {
                method.invoke(service, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    /** Null on phones without the service, or where it lacks getNrMode(int) or setNrMode(int, int, boolean, String). */
    private val semTelephony: SemTelephony? by lazy {
        runCatching {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "isemtelephony") as IBinder? ?: return@runCatching null
            val service =
                Class
                    .forName("com.android.internal.telephony.ISemTelephony\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder) ?: return@runCatching null
            val methods = service.javaClass.methods
            val getNrMode =
                methods.firstOrNull {
                    it.name == "getNrMode" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }
            val setNrMode =
                methods.firstOrNull {
                    it.name == "setNrMode" &&
                        it.parameterTypes.contentEquals(
                            arrayOf(
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                                String::class.java,
                            ),
                        )
                }
            if (getNrMode === null || setNrMode === null) null else SemTelephony(service, getNrMode, setNrMode)
        }.getOrNull()
    }

    fun nrModeAvailable(): Boolean = semTelephony !== null

    private fun requireSemTelephony(): SemTelephony =
        semTelephony ?: throw IllegalStateException("This phone doesn't have Samsung's 5G mode control.")

    fun nrMode(slot: Int): JSONObject {
        val telephony = requireSemTelephony()
        return JSONObject().put("mode", telephony.call(telephony.getNrMode, slot) as Int)
    }

    fun setNrMode(
        slot: Int,
        mode: Int,
    ): JSONObject {
        val telephony = requireSemTelephony()
        val callingPackage = if (Process.myUid() == 0) "root" else "com.android.shell"
        val accepted = telephony.call(telephony.setNrMode, slot, mode, false, callingPackage) as Boolean
        if (!accepted) throw IllegalStateException("Samsung's telephony service didn't accept that 5G mode.")
        return JSONObject().put("mode", mode)
    }
}

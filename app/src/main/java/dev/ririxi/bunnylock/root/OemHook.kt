package dev.ririxi.bunnylock.root

import android.os.IBinder
import dalvik.system.PathClassLoader
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Sends the request bytes for a SIM slot, writes the reply into the response buffer and returns the reply's length
 * (negative for a RIL error). That is the shape of MediaTek's invokeOemRilRequestRawBySlot, and AT methods are adapted to it.
 */
private typealias OemRequest = (slot: Int, request: ByteArray, response: ByteArray) -> Int

/**
 * AT commands through a telephony service instead of the modem tty, so it works for the ADB shell user
 * (Shizuku) as well as root. Candidates: MediaTek's phoneEx (invokeOemRilRequestRaw*), vendor services
 * with an AT method (e.g. Oplus' IOplusTelephonyInternalExt.sendAtCmd), and ITelephony's OEM request
 * (Android 11 to 14). A candidate is only used after a plain "AT" gets a valid answer.
 */
object OemHook {
    const val PREFIX = "ril:"

    private const val MEDIATEK_PHONE_SERVICE = "phoneEx"
    private const val PHONE_SERVICE = "phone"

    private const val RESPONSE_BUFFER_SIZE = 8192
    private const val CALLBACK_TIMEOUT_MS = 8000L
    private const val RAW_PROBE_TIMEOUT_MS = 3000

    // Longer than CALLBACK_TIMEOUT_MS, so a callback that never comes is reported as that and not as this timeout.
    private const val AT_METHOD_PROBE_TIMEOUT_MS = 9000

    private val FRAMEWORK_DIRS =
        listOf("/system/framework", "/system_ext/framework", "/product/framework", "/vendor/framework", "/odm/framework")
    private val JAR_HINT =
        Regex("mediatek|mtk|telephony|oplus|oppo|oneplus|oem|radio|google|pixel|samsung|sem|shannon", RegexOption.IGNORE_CASE)
    private val SERVICE_HINT =
        Regex(
            "phone|telephony|radio|mtk|mediatek|oplus|oppo|oneplus|oem|google|pixel|samsung|shannon|exynos|modem|ril|sem",
            RegexOption.IGNORE_CASE,
        )

    /** Methods worth listing in the diagnostics, so a passthrough this code doesn't know yet can be spotted. */
    private val RELEVANT_METHOD = Regex("oem|ril|raw|atcommand|sendat|atcmd|band|modem|endc|nrconfig|nrmode|5g", RegexOption.IGNORE_CASE)
    private val AT_METHOD = Regex("^(send|invoke|exec|do)?at(cmd|command)", RegexOption.IGNORE_CASE)

    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "bunny-oemhook").apply { isDaemon = true } }

    private class Target(
        val label: String,
        val isAtMethod: Boolean,
        val sendRaw: OemRequest,
    ) {
        /** Some vendor AT methods return only the response body. A missing final result then means OK. */
        var omitsFinalResult = false
    }

    /** What the search looked at and what went wrong, for diagnostics. */
    private val probeLog = ArrayList<String>()

    private val probe: Result<Target> by lazy { runCatching { findWorkingTarget() } }

    private val workingTarget: Target? get() = probe.getOrNull()

    private fun serviceManager() = Class.forName("android.os.ServiceManager")

    private fun serviceBinder(name: String): IBinder? =
        runCatching { serviceManager().getMethod("getService", String::class.java).invoke(null, name) as IBinder? }.getOrNull()

    private fun serviceNames(): List<String> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (serviceManager().getMethod("listServices").invoke(null) as Array<String>).toList()
        }.getOrDefault(emptyList())

    /** A vendor's Stub class may live in a framework jar that isn't on this process's class path, so the likely jars are searched too. */
    private val classLoaders: List<ClassLoader> by lazy {
        val own = OemHook::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        val jars =
            FRAMEWORK_DIRS
                .flatMap { dir -> File(dir).listFiles()?.toList().orEmpty() }
                .filter { it.name.endsWith(".jar") && JAR_HINT.containsMatchIn(it.name) }
        listOf(own) + jars.map { PathClassLoader(it.path, own) }
    }

    private fun stubFor(descriptor: String): Class<*>? =
        classLoaders.firstNotNullOfOrNull { loader ->
            runCatching { Class.forName("$descriptor\$Stub", false, loader) }.getOrNull()
        }

    private fun signature(method: Method) =
        "${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }}): ${method.returnType.simpleName}"

    private fun Method.takes(vararg types: Class<*>?): Boolean = parameterTypes.contentEquals(types)

    /** Whatever an AT method returned, as reply text. A bare success flag becomes the final result line. */
    private fun replyText(returned: Any?): String =
        when (returned) {
            is String -> returned
            is ByteArray -> String(returned, Charsets.ISO_8859_1)
            is Array<*> -> returned.joinToString("\n") { it?.toString().orEmpty() }
            is Boolean -> if (returned) "OK" else "ERROR"
            is Int -> if (returned >= 0) "OK" else "ERROR"
            else -> returned?.toString().orEmpty()
        }

    private fun commandText(request: ByteArray): String = String(request, Charsets.US_ASCII)

    /** Copies as much of the reply as fits into the caller's buffer and returns that length. */
    private fun writeReply(
        reply: String,
        response: ByteArray,
    ): Int {
        val bytes = reply.toByteArray(Charsets.ISO_8859_1)
        val length = minOf(bytes.size, response.size)
        bytes.copyInto(response, endIndex = length)
        return length
    }

    /** Async shape: (slot, token, command, callback) or (slot, command, callback). The reply arrives on the callback. */
    private fun asyncAtAdapter(
        service: Any,
        method: Method,
    ): OemRequest? {
        val parameterTypes = method.parameterTypes
        val callbackType = parameterTypes.lastOrNull() ?: return null
        if (!OemCallback.isCallback(callbackType)) return null
        val leadingTypes = parameterTypes.dropLast(1)
        val withToken = leadingTypes == listOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java)
        val withoutToken = leadingTypes == listOf(Int::class.javaPrimitiveType, String::class.java)
        if (!withToken && !withoutToken) return null
        return { slot, request, response ->
            val callback = OemCallback(callbackType)
            val command = commandText(request)
            if (withToken) {
                method.invoke(service, slot, System.nanoTime(), command, callback.proxy)
            } else {
                method.invoke(service, slot, command, callback.proxy)
            }
            if (!callback.await(CALLBACK_TIMEOUT_MS)) throw IOException("No callback reply for $command.")
            writeReply(callback.reply, response)
        }
    }

    /**
     * Adapts an AT method shaped like (command) or (slot, command), with command as String or bytes.
     * Methods that answer through a callback are left to [asyncAtAdapter].
     */
    private fun atAdapter(
        service: Any,
        method: Method,
    ): OemRequest? {
        asyncAtAdapter(service, method)?.let { return it }
        val invoke: ((Int, ByteArray) -> Any?) =
            when {
                method.takes(String::class.java) -> { _, request -> method.invoke(service, commandText(request)) }

                method.takes(ByteArray::class.java) -> { _, request -> method.invoke(service, request) }

                method.takes(Int::class.javaPrimitiveType, String::class.java) -> { slot, request ->
                    method.invoke(service, slot, commandText(request))
                }

                method.takes(Int::class.javaPrimitiveType, ByteArray::class.java) -> { slot, request ->
                    method.invoke(service, slot, request)
                }

                else -> return null
            }
        return { slot, request, response -> writeReply(replyText(invoke(slot, request)), response) }
    }

    private fun candidatesFor(
        name: String,
        binder: IBinder,
    ): List<Target> {
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull() ?: return emptyList()
        val stub = stubFor(descriptor)
        if (stub === null) {
            probeLog += "$name ($descriptor, class not found)"
            return emptyList()
        }
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return emptyList()
        val methods = service.javaClass.methods
        val relevant =
            methods
                .filter { RELEVANT_METHOD.containsMatchIn(it.name) }
                .map(::signature)
                .distinct()
                .sorted()
        val relevantList = if (relevant.isEmpty()) "" else ": ${relevant.joinToString(", ")}"
        probeLog += "$name ($descriptor)$relevantList"

        val targets = ArrayList<Target>()
        methods
            .firstOrNull {
                it.name == "invokeOemRilRequestRawBySlot" &&
                    it.takes(Int::class.javaPrimitiveType, ByteArray::class.java, ByteArray::class.java)
            }?.let { method ->
                targets +=
                    Target("$name.${method.name}", isAtMethod = false) { slot, request, response ->
                        method.invoke(service, slot, request, response) as Int
                    }
            }
        methods
            .firstOrNull {
                it.name == "invokeOemRilRequestRaw" && it.takes(ByteArray::class.java, ByteArray::class.java)
            }?.let { method ->
                targets +=
                    Target("$name.${method.name}", isAtMethod = false) { _, request, response ->
                        method.invoke(service, request, response) as Int
                    }
            }
        methods.filter { AT_METHOD.containsMatchIn(it.name) }.forEach { method ->
            atAdapter(service, method)?.let { targets += Target("$name.${signature(method)}", isAtMethod = true, sendRaw = it) }
        }
        return targets
    }

    /** A service whose interface can't be inspected just has no candidates. */
    private fun candidatesOrNone(
        name: String,
        binder: IBinder,
    ): List<Target> = runCatching { candidatesFor(name, binder) }.getOrDefault(emptyList())

    /** True when the candidate gives a valid answer to a plain "AT" on the first SIM. */
    private fun tryTarget(candidate: Target): Boolean {
        val timeoutMs = if (candidate.isAtMethod) AT_METHOD_PROBE_TIMEOUT_MS else RAW_PROBE_TIMEOUT_MS
        val reply =
            try {
                sendTo(candidate, slot = 0, command = "AT", timeoutMs = timeoutMs)
            } catch (e: Exception) {
                probeLog += "${candidate.label} failed: ${e.message}"
                return false
            }
        val lines = reply.lines().map { it.trim() }
        if (lines.any { it == "OK" }) return true
        if (candidate.isAtMethod && lines.none(::isErrorLine)) {
            candidate.omitsFinalResult = true
            return true
        }
        probeLog += "${candidate.label} replied: ${reply.trim().ifEmpty { "nothing" }}"
        return false
    }

    private fun findWorkingTarget(): Target {
        val vendorServices =
            listOf(MEDIATEK_PHONE_SERVICE) +
                serviceNames().filter { it != MEDIATEK_PHONE_SERVICE && it != PHONE_SERVICE && SERVICE_HINT.containsMatchIn(it) }
        for (name in vendorServices) {
            val binder = serviceBinder(name) ?: continue
            val found = candidatesOrNone(name, binder).firstOrNull(::tryTarget)
            if (found !== null) return found
        }
        // ITelephony routes to the RIL's OEM hook. Only MediaTek's RIL answers it, others never reply.
        if (isMediatek()) {
            val rawCandidates =
                serviceBinder(PHONE_SERVICE)?.let { candidatesOrNone(PHONE_SERVICE, it) }.orEmpty().filter {
                    !it.isAtMethod
                }
            val found = rawCandidates.firstOrNull(::tryTarget)
            if (found !== null) return found
        }
        throw IOException("No OEM request passthrough answered on this phone.")
    }

    private fun isMediatek(): Boolean {
        val props = listOf("ro.soc.manufacturer", "ro.hardware", "ro.board.platform").joinToString(" ") { Sys.prop(it) }.lowercase()
        return "mediatek" in props || Regex("\\bmt\\d{4}").containsMatchIn(props)
    }

    fun available(): Boolean = workingTarget !== null

    /** Which path works, or why none does. */
    fun status(): String {
        val working = workingTarget
        return if (working !== null) "Working through ${working.label}." else probe.exceptionOrNull()?.message.orEmpty()
    }

    /** Every service and method looked at while searching, and what failed. Reading [probe] first makes sure the search has run. */
    fun diagnostics(): List<String> = probe.let { probeLog.distinct() }

    /** The slot of a "ril:<sim>" node, where SIMs count from 1. Without a readable number it is the first SIM. */
    fun slotOf(node: String): Int = (node.removePrefix(PREFIX).toIntOrNull() ?: 1) - 1

    fun send(
        slot: Int,
        command: String,
        timeoutMs: Int,
    ): String {
        val target = workingTarget ?: throw IOException("No RIL passthrough is available on this phone.")
        return sendTo(target, slot, command, timeoutMs)
    }

    /** The request runs on [executor] because a service that never answers would otherwise block the caller for good. */
    private fun sendTo(
        target: Target,
        slot: Int,
        command: String,
        timeoutMs: Int,
    ): String {
        val request = command.toByteArray(Charsets.US_ASCII)
        val response = ByteArray(RESPONSE_BUFFER_SIZE)
        val task = executor.submit<Int> { target.sendRaw(slot, request, response) }
        val length =
            try {
                task.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                task.cancel(true)
                throw IOException("The modem didn't answer $command in time.")
            } catch (e: ExecutionException) {
                val cause = (e.cause as? InvocationTargetException)?.targetException ?: e.cause
                throw IOException("The service refused $command (${cause?.javaClass?.simpleName}: ${cause?.message}).")
            }
        // A negative length is a RIL error. The modem's own reply was "ERROR".
        if (length < 0) return "ERROR"
        val reply = String(response, 0, minOf(length, response.size), Charsets.ISO_8859_1)
        val hasFinalResult = reply.lines().map { it.trim() }.any { it == "OK" || isErrorLine(it) }
        return if (target.omitsFinalResult && !hasFinalResult) "$reply\nOK" else reply
    }

    private fun isErrorLine(line: String): Boolean = line == "ERROR" || line.startsWith("+CME ERROR")
}

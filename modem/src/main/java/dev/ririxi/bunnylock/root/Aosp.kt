package dev.ririxi.bunnylock.root

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.telephony.AccessNetworkConstants.AccessNetworkType
import android.telephony.RadioAccessSpecifier
import dev.ririxi.bunnylock.core.toIntList
import dev.ririxi.bunnylock.modem.andList
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ITelephony called as uid 0 so MODIFY_PHONE_STATE checks pass. Only works where the
 * radio HAL implements the call.
 */
object Aosp {
    // TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_* values.
    private const val REASON_USER = 0
    private const val REASON_POWER = 1
    private const val REASON_CARRIER = 2
    private const val REASON_ENABLE_2G = 3
    private val OTHER_REASONS = listOf(REASON_POWER, REASON_CARRIER, REASON_ENABLE_2G)

    /** An allowed network types bitmask of NETWORK_TYPE_* values: type n is bit n - 1. */
    private fun bitsOf(vararg networkTypes: Int): Long = networkTypes.fold(0L) { mask, networkType -> mask or (1L shl (networkType - 1)) }

    /** NETWORK_TYPE_CDMA, EVDO_0, EVDO_A, 1xRTT, EVDO_B and EHRPD. */
    private val CDMA_BITS = bitsOf(4, 5, 6, 7, 12, 14)

    /** The network types of 2G, 3G, 4G and 5G, in that order. LTE carrier aggregation counts as 4G. */
    private val GENERATION_BITS =
        listOf(
            bitsOf(1, 2, 4, 7, 11, 16),
            bitsOf(3, 5, 6, 8, 9, 10, 12, 14, 15, 17),
            bitsOf(13, 19),
            bitsOf(20),
        )
    private val GENERATION_NAMES = listOf("2G", "3G", "4G", "5G")

    // TelephonyManager.NR_DUAL_CONNECTIVITY_ENABLE and ENABLE_NR_DUAL_CONNECTIVITY_SUCCESS (system APIs, so not in the SDK).
    private const val NR_DC_ENABLE = 1
    private const val NR_DC_SUCCESS = 0

    /** ril.h's MAX_BANDS: what one specifier holds in RILs built on the legacy structs. */
    private const val MAX_RIL_BANDS = 8

    private const val BOOLEAN_CONSUMER = "com.android.internal.telephony.IBooleanConsumer"
    private const val CHANNELS_CALLBACK_TIMEOUT_SECONDS = 8L

    private const val RADIO_OFF_PAUSE_MS = 2500L
    private const val MODE_READBACK_CHECKS = 5
    private const val MODE_READBACK_RETRY_MS = 500L
    private const val RADIO_STATE_POLL_MS = 500L
    private const val RADIO_STATE_TIMEOUT_MS = 10_000L

    /** The per-SIM "is the radio on" query. Its name depends on the Android version. */
    private val SIM_RADIO_STATE_QUERIES = listOf("isRadioOnForSubscriber", "isRadioOnForSubscriberWithFeature")

    /** Used when the app couldn't name a subscription: the one in [slot], else the default, else the only active one. Negative if none. */
    fun resolveSubId(
        subId: Int,
        slot: Int,
    ): Int {
        if (subId >= 0) return subId
        val isub = runCatching { systemInterface("isub", "com.android.internal.telephony.ISub") }.getOrNull() ?: return subId

        // ISub returns an int or an int[] depending on the method and Android version.
        fun ids(
            name: String,
            vararg args: Any,
        ): List<Int> =
            runCatching {
                val method = isub.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size }
                when (val result = method.invoke(isub, *args)) {
                    is Int -> listOf(result)
                    is IntArray -> result.toList()
                    else -> emptyList()
                }
            }.getOrDefault(emptyList()).filter { it >= 0 }

        return ids("getSubId", slot).firstOrNull()
            ?: ids("getDefaultSubId").firstOrNull()
            ?: ids("getActiveSubIdList", true).singleOrNull()
            ?: subId
    }

    /** The hidden AIDL client of a system service: `<aidlInterface>.Stub.asInterface(ServiceManager.getService(serviceName))`. */
    private fun systemInterface(
        serviceName: String,
        aidlInterface: String,
    ): Any {
        val binder =
            Class
                .forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, serviceName) as IBinder
        return Class
            .forName("$aidlInterface\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private val phone: Any by lazy { systemInterface("phone", "com.android.internal.telephony.ITelephony") }

    /**
     * The ITelephony method with exactly [argumentCount] parameters, or else the shortest overload with more:
     * newer Android versions append trailing parameters (Android 17 added some).
     */
    private fun findMethod(
        name: String,
        argumentCount: Int,
    ): Method? {
        val named = phone.javaClass.methods.filter { it.name == name }
        return named.firstOrNull { it.parameterTypes.size == argumentCount }
            ?: named.filter { it.parameterTypes.size > argumentCount }.minByOrNull { it.parameterTypes.size }
    }

    private val callingPackage: String get() = if (Process.myUid() == 0) "root" else "com.android.shell"

    /** Stands in for a trailing parameter the caller didn't pass. A String there is taken to be the calling package. */
    private fun defaultFor(type: Class<*>): Any? =
        when (type) {
            String::class.java -> callingPackage
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Boolean::class.javaPrimitiveType -> false
            else -> null
        }

    /** Method.invoke doesn't narrow boxed numbers (a Long won't go into an int parameter), so numbers are converted to the declared type. */
    private fun toParameterType(
        arg: Any?,
        type: Class<*>,
    ): Any? =
        when (type) {
            Int::class.javaPrimitiveType -> (arg as Number).toInt()
            Long::class.javaPrimitiveType -> (arg as Number).toLong()
            Byte::class.javaPrimitiveType -> (arg as Number).toByte()
            else -> arg
        }

    /** Invokes ITelephony.[name]: [findMethod] picks the overload, and trailing parameters without an argument get [defaultFor] values. */
    private fun call(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method = findMethod(name, args.size) ?: throw NoSuchMethodException("This Android version doesn't offer ITelephony.$name.")
        val parameterTypes = method.parameterTypes
        val invokeArgs =
            Array(parameterTypes.size) { index ->
                if (index < args.size) toParameterType(args[index], parameterTypes[index]) else defaultFor(parameterTypes[index])
            }
        return try {
            method.invoke(phone, *invokeArgs)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun hasMethod(
        name: String,
        argumentCount: Int,
    ): Boolean = findMethod(name, argumentCount) !== null

    /** The bitmask a getter returns, or null when the getter is missing, fails or reports a negative value. */
    private fun readMask(
        name: String,
        vararg args: Any?,
    ): Long? = runCatching { (call(name, *args) as Number).toLong() }.getOrNull()?.takeIf { it >= 0 }

    private fun maskText(mask: Long?): String = mask?.let { "0x%X".format(it) } ?: "unavailable"

    private fun errorText(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"

    /**
     * Which of 2G, 3G, 4G and 5G a mask allows. A modem reads a mask back without the network types it lacks
     * (TD-SCDMA or CDMA, say), so a request and its readback are compared by generation and not bit by bit.
     */
    private fun generations(mask: Long): List<Boolean> = GENERATION_BITS.map { mask and it != 0L }

    private fun hasAnyGeneration(mask: Long): Boolean = generations(mask).any { it }

    private fun otherReasonMasks(subId: Int): Map<Int, Long> {
        val unset = unsetReasonMask()
        return buildMap {
            for (reason in OTHER_REASONS) {
                val mask = readMask("getAllowedNetworkTypesForReason", subId, reason) ?: continue
                if (mask > 0 && mask != unset) put(reason, mask)
            }
        }
    }

    private fun unsetReasonMask(): Long? =
        runCatching {
            val defaultMode = Class.forName("com.android.internal.telephony.RILConstants").getField("PREFERRED_NETWORK_MODE").getInt(null)
            val raf = Class.forName("android.telephony.RadioAccessFamily").getMethod("getRafFromNetworkType", Int::class.javaPrimitiveType).invoke(null, defaultMode)
            (raf as Int).toLong()
        }.getOrNull()

    private fun sentToModem(
        mask: Long,
        otherReasons: Map<Int, Long>,
    ): Long = otherReasons.values.fold(mask) { result, allowed -> result and allowed }

    private fun heldBackCause(
        mask: Long,
        otherReasons: Map<Int, Long>,
    ): String? {
        val requested = generations(mask)
        val removedByReason =
            otherReasons
                .mapValues { (_, allowed) ->
                    val kept = generations(allowed)
                    GENERATION_NAMES.filterIndexed { index, _ -> requested[index] && !kept[index] }
                }.filterValues { it.isNotEmpty() }
        if (removedByReason.isEmpty()) return null
        val causes =
            removedByReason.map { (reason, removed) ->
                when (reason) {
                    REASON_POWER -> "power saving (Battery Saver, for example) is on"
                    REASON_CARRIER -> "the carrier settings leave out ${andList(removed)}"
                    else -> "the \"Allow 2G\" setting is off"
                }
            }
        val keptOff = GENERATION_NAMES.filter { name -> removedByReason.values.any { name in it } }
        return "Android keeps ${andList(keptOff)} off for now: ${andList(causes)}."
    }

    private fun reasonMasksText(subId: Int): String {
        // The index is the TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_* value.
        val reasons =
            listOf("USER", "POWER", "CARRIER", "2G", "TEST")
                .mapIndexedNotNull { reason, label ->
                    readMask("getAllowedNetworkTypesForReason", subId, reason)?.let { "$label=${maskText(it)}" }
                }
        val modem = readMask("getAllowedNetworkTypesBitmask", subId)
        return (reasons + "modem=${maskText(modem)}").joinToString(", ")
    }

    /** Every network-mode value the framework will tell, for the error message when a mode is refused. */
    private fun networkModeDiagnostic(
        subId: Int,
        slot: Int,
    ): String {
        val radioAccessFamily = readMask("getRadioAccessFamily", slot, callingPackage)
        val setters =
            buildList {
                if (hasMethod("setAllowedNetworkTypesForReason", 3)) add("reason")
                if (hasMethod("setAllowedNetworkTypes", 2)) add("allowed")
                if (hasMethod("setPreferredNetworkType", 2)) add("preferred")
            }
        return "${reasonMasksText(subId)}, RAF=${maskText(radioAccessFamily)}, setters=${setters.joinToString("/").ifEmpty { "none" }}"
    }

    private fun supportedTypes(): Long? {
        val slotMasks = listOf(0, 1).mapNotNull { slot -> readMask("getRadioAccessFamily", slot, callingPackage) }
        return slotMasks.fold(0L) { mask, slotMask -> mask or slotMask }.takeIf { it > 0 }
    }

    /** Every value is optional: a getter that this Android version lacks or that fails just leaves its key out. */
    fun state(subId: Int): JSONObject {
        val json = JSONObject()
        val userAllowed = readMask("getAllowedNetworkTypesForReason", subId, REASON_USER)
        val modemAllowed = readMask("getAllowedNetworkTypesBitmask", subId)
        val otherReasons = otherReasonMasks(subId)
        val heldBack =
            userAllowed !== null &&
                modemAllowed !== null &&
                generations(modemAllowed) != generations(userAllowed) &&
                generations(modemAllowed) == generations(sentToModem(userAllowed, otherReasons))
        val shownAllowed = if (heldBack) userAllowed else modemAllowed ?: userAllowed
        shownAllowed?.let { json.put("allowed", it) }
        if (heldBack) heldBackCause(userAllowed, otherReasons)?.let { json.put("modeHeldBack", it) }
        json.put("modeMasks", reasonMasksText(subId))
        userAllowed?.let { json.put("userAllowed", it) }
        modemAllowed?.let { json.put("modemAllowed", it) }
        supportedTypes()?.let { json.put("supportedTypes", it) }
        runCatching { json.put("nrDc", call("isNrDualConnectivityEnabled", subId) as Boolean) }
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val channels = call("getSystemSelectionChannels", subId) as List<RadioAccessSpecifier>?
            json.put("channels", specsToJson(channels ?: emptyList()))
        }.onFailure { json.put("channelsError", it.message ?: it.javaClass.simpleName) }
        runCatching { json.put("selectionMode", call("getNetworkSelectionMode", subId) as Int) }
        runCatching {
            val manualPlmn = call("getManualNetworkSelectionPlmn", subId) as String?
            if (!manualPlmn.isNullOrEmpty()) json.put("manualPlmn", manualPlmn)
        }
        return json
    }

    /** Blocks until the modem accepts or rejects the registration attempt. */
    fun setPlmn(
        subId: Int,
        numeric: String,
        persist: Boolean,
    ): JSONObject {
        val operatorInfoClass = Class.forName("com.android.internal.telephony.OperatorInfo")
        // OperatorInfo(alphaLong, alphaShort, numeric, ran), or the three-String constructor where that one doesn't exist.
        val operatorInfo =
            runCatching {
                operatorInfoClass
                    .getConstructor(String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)
                    .newInstance(numeric, numeric, numeric, AccessNetworkType.UNKNOWN)
            }.getOrElse {
                operatorInfoClass
                    .getConstructor(String::class.java, String::class.java, String::class.java)
                    .newInstance(numeric, numeric, numeric)
            }
        val registered = call("setNetworkSelectionModeManual", subId, operatorInfo, persist) as Boolean
        check(registered) {
            "Couldn't register on ${numeric.take(3)} ${numeric.drop(3)}. The network may be out of range, or it may not allow this SIM."
        }
        return JSONObject().put("plmn", numeric)
    }

    fun setPlmnAutomatic(subId: Int): JSONObject {
        call("setNetworkSelectionModeAutomatic", subId)
        return JSONObject().put("automatic", true)
    }

    private fun setAllowedOnce(
        subId: Int,
        mask: Long,
    ): Boolean =
        if (hasMethod("setAllowedNetworkTypesForReason", 3)) {
            call("setAllowedNetworkTypesForReason", subId, REASON_USER, mask) as Boolean
        } else {
            call("setAllowedNetworkTypes", subId, mask) as Boolean
        }

    /**
     * Modems reject masks with network types they lack (e.g. CDMA on MediaTek without C2K), so on refusal
     * this retries without CDMA and then with only the types the phone reports as supported.
     */
    fun setAllowed(
        subId: Int,
        mask: Long,
        slot: Int,
    ): JSONObject {
        val before = networkModeDiagnostic(subId, slot)
        val previous = readMask("getAllowedNetworkTypesForReason", subId, REASON_USER) ?: readMask("getAllowedNetworkTypesBitmask", subId)
        val supported = readMask("getRadioAccessFamily", slot, callingPackage)?.takeIf { it > 0 }
        val attempts =
            listOfNotNull(mask, mask and CDMA_BITS.inv(), supported?.let { mask and it })
                .filter { it != 0L }
                .distinct()
        val otherReasons = otherReasonMasks(subId)
        val failures = ArrayList<String>()
        var notApplied: Long? = null
        for (attempt in attempts) {
            val result = runCatching { setAllowedOnce(subId, attempt) }
            val accepted = result.getOrNull() == true
            if (accepted && modemSwitchesTo(subId, attempt, otherReasons)) return applied(subId, attempt, mask, otherReasons)
            val error = result.exceptionOrNull()
            val failure =
                when {
                    error !== null -> errorText(error)
                    accepted -> "reported success, but modem readback is ${maskText(readMask("getAllowedNetworkTypesBitmask", subId))}"
                    else -> "refused"
                }
            failures += "mask 0x%X %s".format(attempt, failure)
            if (accepted) {
                notApplied = attempt
                break
            }
        }
        // Some modems only switch modes while no connection is open.
        notApplied?.let { attempt ->
            val switched =
                runCatching {
                    inAirplaneMode {
                        // Android skips a value it already holds, so the old one goes back first.
                        if (previous !== null && previous != attempt) runCatching { setAllowedOnce(subId, previous) }
                        setAllowedOnce(subId, attempt) && modemSwitchesTo(subId, attempt, otherReasons)
                    }
                }.getOrDefault(false)
            if (switched) return applied(subId, attempt, mask, otherReasons).put("airplaneMode", true)
            failures += "mask 0x%X not taken with the radio off either".format(attempt)
        }
        val restored = previous !== null && runCatching { setAllowedOnce(subId, previous) }.getOrDefault(false)
        val restoredText = if (restored) " The previous network mode was put back." else ""
        val causeText = heldBackCause(mask, otherReasons)?.let { " $it" }.orEmpty()
        val after = networkModeDiagnostic(subId, slot)
        throw IllegalStateException(
            "The radio refused this network mode (${failures.joinToString("; ")}).$restoredText$causeText Before: $before. After: $after.",
        )
    }

    private fun applied(
        subId: Int,
        attempt: Long,
        requested: Long,
        otherReasons: Map<Int, Long>,
    ): JSONObject {
        val json = JSONObject().put("allowed", attempt).put("requested", requested)
        val readback = readMask("getAllowedNetworkTypesBitmask", subId)
        if (readback !== null && generations(readback) != generations(attempt)) heldBackCause(attempt, otherReasons)?.let { json.put("heldBack", it) }
        return json
    }

    private fun modemSwitchesTo(
        subId: Int,
        mask: Long,
        otherReasons: Map<Int, Long>,
    ): Boolean {
        val expected = listOfNotNull(mask, sentToModem(mask, otherReasons).takeIf(::hasAnyGeneration)).map(::generations)
        repeat(MODE_READBACK_CHECKS) { check ->
            if (check > 0) Thread.sleep(MODE_READBACK_RETRY_MS)
            val readback = readMask("getAllowedNetworkTypesBitmask", subId)
            if (readback === null || generations(readback) in expected) return true
        }
        return false
    }

    /** [state] is a TelephonyManager.NR_DUAL_CONNECTIVITY_* value: 1 = enable EN-DC, 2 = disable, 3 = disable immediately. */
    fun setNrDc(
        subId: Int,
        state: Int,
    ): JSONObject {
        val resultCode = call("setNrDualConnectivityState", subId, state) as Int
        if (resultCode != NR_DC_SUCCESS) {
            // The other TelephonyManager.ENABLE_NR_DUAL_CONNECTIVITY_* results.
            val reason =
                when (resultCode) {
                    1 -> "The radio isn't available right now."
                    2 -> "The radio returned an error. It may not support switching EN-DC."
                    3 -> "The radio didn't accept that EN-DC state."
                    else -> "The radio returned error $resultCode."
                }
            throw IllegalStateException(reason)
        }
        return JSONObject().put("nrDc", state == NR_DC_ENABLE)
    }

    /** An empty list removes the restriction. */
    fun setChannels(
        subId: Int,
        specs: JSONArray,
        splitRefused: Boolean = true,
    ): JSONObject {
        check(subId >= 0) {
            "Android has no active subscription for this SIM, so there is nothing to send the lock to. " +
                "Check that the SIM is inserted and switched on, and that bunnylock has the Phone permission."
        }
        val whole = List(specs.length()) { specs.getJSONObject(it) }.map(::toSpecifier)
        // Samsung's Snapdragon RIL refuses a specifier with more than MAX_RIL_BANDS bands, so a refused long list is sent again in pieces.
        val split = if (splitRefused) whole.flatMap(::splitToRilBands) else whole
        val requestDiagnostic = channelRequestDiagnostic(subId, specs)
        val outcomes = ArrayList<String>()
        var lastAnswer: Boolean? = null
        // When nothing had to be split both lists have the same size, and only the first is tried.
        for (specifiers in listOf(whole, split).distinctBy { it.size }) {
            val shape = if (specifiers === whole) "as sent" else "split into ${specifiers.size} specifiers"
            val (answer, callbackDiagnostic) = requestChannels(subId, specifiers, requestDiagnostic)
            lastAnswer = answer
            outcomes += "$shape: $callbackDiagnostic"
            if (answer == true) {
                return JSONObject()
                    .put("result", true)
                    .put("channels", specsToJson(specifiers))
                    .put("diagnostic", "$requestDiagnostic; ${outcomes.joinToString("; ")}; after=${channelState(subId)}")
            }
            // Only an explicit refusal is retried in pieces. When no answer came, the attempts end here.
            if (answer === null) break
        }
        val rejected = "Android's radio service rejected the channel lock"
        val fewer = "Selecting $MAX_RIL_BANDS or fewer bands may work on this phone."
        val failure =
            when {
                lastAnswer === null -> "Android's radio service didn't confirm the channel lock."
                split.size > whole.size -> "$rejected, also with the bands split into groups of $MAX_RIL_BANDS. $fewer"
                whole.any { it.bands.size > MAX_RIL_BANDS } -> "$rejected. $fewer"
                else -> "$rejected."
            }
        throw IllegalStateException("$failure $requestDiagnostic; ${outcomes.joinToString("; ")}; after=${channelState(subId)}")
    }

    private fun toSpecifier(spec: JSONObject): RadioAccessSpecifier =
        RadioAccessSpecifier(spec.getInt("ran"), spec.optJSONArray("bands").toIntArray(), spec.optJSONArray("channels").toIntArray())

    private fun splitToRilBands(spec: RadioAccessSpecifier): List<RadioAccessSpecifier> {
        if (spec.bands.size <= MAX_RIL_BANDS || spec.channels.isNotEmpty()) return listOf(spec)
        return spec.bands
            .toList()
            .chunked(MAX_RIL_BANDS)
            .map { RadioAccessSpecifier(spec.radioAccessNetwork, it.toIntArray(), IntArray(0)) }
    }

    /** What was asked for and what was in place before, repeated in every setChannels diagnostic. */
    private fun channelRequestDiagnostic(
        subId: Int,
        specs: JSONArray,
    ): String {
        val before = channelState(subId)
        val counts =
            (0 until specs.length()).joinToString { index ->
                val spec = specs.getJSONObject(index)
                "ran=${spec.getInt("ran")}, bands=${spec.optJSONArray("bands")?.length() ?: 0}, channels=${spec.optJSONArray("channels")?.length() ?: 0}"
            }
        val signature =
            findMethod("setSystemSelectionChannels", 3)?.let { method ->
                "${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
            } ?: "setSystemSelectionChannels unavailable"
        return "subId=$subId; request=$specs; counts=[$counts]; before=$before; method=$signature"
    }

    /**
     * Calls ITelephony.setSystemSelectionChannels(List<RadioAccessSpecifier>, int subId, IBooleanConsumer resultCallback).
     * Returns the radio service's answer (null when it never came) and how it arrived.
     *
     * IBooleanConsumer is hidden, so there is no Stub to extend: a reflection proxy stands in for the interface and
     * hands out a plain Binder that reads accept(boolean), the interface's only transaction.
     */
    private fun requestChannels(
        subId: Int,
        specifiers: List<RadioAccessSpecifier>,
        requestDiagnostic: String,
    ): Pair<Boolean?, String> {
        val consumerInterface = Class.forName(BOOLEAN_CONSUMER)
        val latch = CountDownLatch(1)
        var result: Boolean? = null
        var callbackPath = "none"
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    result =
                        runCatching {
                            data.enforceInterface(BOOLEAN_CONSUMER)
                            data.readInt() != 0
                        }.getOrNull()
                    callbackPath = "binder"
                    latch.countDown()
                    return true
                }
            }
        val callback =
            Proxy.newProxyInstance(consumerInterface.classLoader, arrayOf(consumerInterface)) { _, method, args ->
                when (method.name) {
                    "asBinder" -> binder

                    "accept" -> {
                        result = args?.get(0) as? Boolean
                        callbackPath = "interface"
                        latch.countDown()
                        null
                    }

                    "hashCode" -> System.identityHashCode(binder)

                    "equals" -> false

                    "toString" -> "BunnyBooleanConsumer"

                    else -> null
                }
            }
        val started = System.nanoTime()
        val callError = runCatching { call("setSystemSelectionChannels", specifiers, subId, callback) }.exceptionOrNull()
        if (callError !== null) {
            throw IllegalStateException(
                "Android's radio service couldn't start the channel lock. $requestDiagnostic; error=${errorText(callError)}",
                callError,
            )
        }
        val callbackReceived = latch.await(CHANNELS_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val callbackDiagnostic = if (callbackReceived) "callback=$result via $callbackPath" else "callback timed out"
        return result to "$callbackDiagnostic after ${elapsedMs}ms"
    }

    private fun channelState(subId: Int): String =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val current = call("getSystemSelectionChannels", subId) as? List<RadioAccessSpecifier>
            specsToJson(current.orEmpty()).toString()
        }.getOrElse(::errorText)

    /** When the radio HAL doesn't support or refuses the modem reboot, the radio is power-cycled instead and the reason is returned as rebootError. */
    fun rebootModem(
        slot: Int,
        subId: Int,
    ): JSONObject {
        val reboot = runCatching { call("rebootModem", slot) as Boolean }
        val failure = reboot.exceptionOrNull()
        if (failure !== null && failure !is UnsupportedOperationException) throw failure
        if (reboot.getOrNull() == true) return JSONObject().put("ok", true).put("method", "modem")
        val error = failure?.let(::errorText) ?: "The radio HAL refused the modem reboot"
        return cycleSubscriberRadio(subId).put("rebootError", error)
    }

    /** Stock Qualcomm reports a reduced feature set, so the framework never forwards EN-DC changes there. */
    fun nrDcConfigurable(): Boolean =
        runCatching {
            call("isRadioInterfaceCapabilitySupported", "CAPABILITY_NR_DUAL_CONNECTIVITY_CONFIGURATION_AVAILABLE") as Boolean
        }.getOrDefault(hasMethod("setNrDualConnectivityState", 2))

    /**
     * Restarts one SIM's radio and leaves the other connected. setRadioForSubscriber ignores a request for the state the
     * radio already reports, so "on" waits until it shows off. If the radio gets stuck, airplane mode is toggled instead.
     */
    fun cycleSubscriberRadio(subId: Int): JSONObject {
        if (subId < 0) return cycleDefaultRadio()
        val stateQuery = SIM_RADIO_STATE_QUERIES.firstOrNull { hasMethod(it, 2) }
        check(stateQuery !== null && hasMethod("setRadioForSubscriber", 2)) { "This Android version can't restart one SIM's radio." }

        fun radioOn(): Boolean = call(stateQuery, subId, callingPackage) == true

        fun awaitRadio(on: Boolean): Boolean {
            val deadline = System.nanoTime() + RADIO_STATE_TIMEOUT_MS * 1_000_000
            while (System.nanoTime() < deadline) {
                if (radioOn() == on) return true
                Thread.sleep(RADIO_STATE_POLL_MS)
            }
            return false
        }

        check(radioOn()) { "This SIM's radio is off." }
        val restarted =
            runCatching {
                call("setRadioForSubscriber", subId, false)
                if (!awaitRadio(on = false)) return@runCatching false
                Thread.sleep(RADIO_OFF_PAUSE_MS)
                (1..2).any {
                    call("setRadioForSubscriber", subId, true)
                    awaitRadio(on = true)
                }
            }.getOrDefault(false)
        if (restarted) return JSONObject().put("method", "radio").put("api", "setRadioForSubscriber")
        radioCycle()
        return JSONObject().put("method", "radio").put("api", "airplane mode").put("airplaneMode", true)
    }

    /** For an unknown subscription. setRadioPower only reaches the default SIM. */
    private fun cycleDefaultRadio(): JSONObject {
        // Switching on is attempted whatever switching off did. Only afterwards are the two results checked.
        val off = runCatching { call("setRadioPower", false) == true }
        Thread.sleep(RADIO_OFF_PAUSE_MS)
        val on = runCatching { call("setRadioPower", true) == true }
        check(on.getOrDefault(false)) { "The radio refused to turn back on: ${on.exceptionOrNull()?.message ?: "unknown error"}." }
        check(off.getOrDefault(false)) { "The radio refused to turn off: ${off.exceptionOrNull()?.message ?: "unknown error"}." }
        return JSONObject().put("method", "radio").put("api", "setRadioPower")
    }

    fun radioCycle(): JSONObject {
        inAirplaneMode {}
        return JSONObject().put("cycled", true)
    }

    /** Runs [block] with every radio off. Airplane mode goes off again even when [block] fails. */
    fun <T> inAirplaneMode(block: () -> T): T {
        Sys.sh("cmd connectivity airplane-mode enable")
        try {
            Thread.sleep(RADIO_OFF_PAUSE_MS)
            return block()
        } finally {
            Sys.sh("cmd connectivity airplane-mode disable")
        }
    }

    private fun specsToJson(specifiers: List<RadioAccessSpecifier>): JSONArray {
        val json = JSONArray()
        for (spec in specifiers) {
            json.put(
                JSONObject()
                    .put("ran", spec.radioAccessNetwork)
                    .put("bands", JSONArray((spec.bands ?: IntArray(0)).toList()))
                    .put("channels", JSONArray((spec.channels ?: IntArray(0)).toList())),
            )
        }
        return json
    }

    private fun JSONArray?.toIntArray(): IntArray = this?.toIntList()?.toIntArray() ?: IntArray(0)
}

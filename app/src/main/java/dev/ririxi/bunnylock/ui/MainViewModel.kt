package dev.ririxi.bunnylock.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.core.Ran
import dev.ririxi.bunnylock.core.toIntList
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Cells
import dev.ririxi.bunnylock.data.LockNotifier
import dev.ririxi.bunnylock.data.PrivilegedClient
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.RootClient
import dev.ririxi.bunnylock.data.RootException
import dev.ririxi.bunnylock.data.ShizukuClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ModemState(
    val groups: List<BandGroupState> = emptyList(),
    val nrMode: NrMode? = null,
    val lteLock: String? = null,
    val nrLock: String? = null,
    val legacyCellLock: String? = null,
    val plmnLock: String? = null,
    /** The modem answered about network selection, so the framework's stored value must not override it. */
    val plmnReported: Boolean = false,
    val cells: List<Cell> = emptyList(),
)

private const val MAX_HAL_CHANNELS = 32
private const val MAX_LOG_LINES = 80
private const val MAX_TOOL_OUTPUT_CHARS = 8000

private const val SAMSUNG_SA_ONLY = 0
private const val SAMSUNG_NSA_ONLY = 1
private const val SAMSUNG_SA_NSA = 2

/** QMI's network selection preference. 0 is automatic. */
private const val QMI_NET_SEL_MANUAL = 1

// QMI's NR cell lock types. 2, the one missing here, means no lock.
private const val QMI_NR_LOCK_PCI = 0
private const val QMI_NR_LOCK_FREQUENCY = 1
private const val QMI_NR_LOCK_SEVERAL_PCIS = 3
private const val QMI_NR_LOCK_GNB_LIST = 4

// The access technology numbers MediaTek's cell lock command takes and reports.
private const val MTK_ACT_GSM = 0
private const val MTK_ACT_UTRAN = 2
private const val MTK_ACT_LTE = 7
private const val MTK_ACT_NR = 11

private const val MTK_PCI_UNSET = 65535

// The states TelephonyManager.setNrDualConnectivityState takes. They are system API, so the SDK doesn't expose them.
private const val NR_DUAL_CONNECTIVITY_ENABLE = 1
private const val NR_DUAL_CONNECTIVITY_DISABLE = 2

private const val HAL_LOCK_SETTLE_MS = 8000L
private const val HAL_LOCK_RECHECK_MS = 6000L
private const val HAL_LOCK_RECHECKS = 2

private const val PIXEL_AT_NODE = "/dev/umts_router"

/** An AT node that starts like this sends commands through MediaTek's RIL passthrough instead of a modem tty. */
private const val RIL_NODE_PREFIX = "ril:"

private fun rilNode(sim: Int) = "$RIL_NODE_PREFIX$sim"

class MainViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private var helper: PrivilegedClient = RootClient(app)
    private val prefs = app.getSharedPreferences("bunnylock", Context.MODE_PRIVATE)

    /** SIMs whose 5G mode went through Samsung's service because QMI refused it, so it is read back from there too. */
    private val samsungNrModeReadbackSims = mutableSetOf<Int>()

    private val _state =
        MutableStateFlow(
            UiState(
                persist = prefs.getBoolean("persist", true),
                dynamicColor = prefs.getBoolean("dynamicColor", true),
                atNode = prefs.getString("atNode", "") ?: "",
            ),
        )
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val uiState get() = _state.value

    private inline fun updateState(transform: UiState.() -> UiState) = _state.update(transform)

    private var connecting: Job? = null

    // Shizuku hands over its binder a moment after the app starts, or whenever it is started later, so a failed attempt is redone then.
    private val stopWatchingShizuku = ShizukuClient.onBinderReceived(::reconnect)

    init {
        connect()
    }

    override fun onCleared() {
        stopWatchingShizuku()
        helper.stop()
    }

    fun connect() {
        if (connecting?.isActive == true) return
        connecting = viewModelScope.launch { connectNow() }
    }

    /** For a helper that showed up or went away on its own: waits out a running attempt, which may already have settled it. */
    private fun reconnect() {
        viewModelScope.launch {
            connecting?.join()
            if (uiState.root != RootState.Granted || !helper.running) connect()
        }
    }

    /** Without this the screens stay up over a dead Shizuku service and every action fails. */
    private fun helperLost() {
        helper.stop()
        updateState { copy(root = RootState.Denied("The Shizuku service stopped. Try again to restart it.")) }
    }

    private suspend fun connectNow() {
        updateState { copy(root = RootState.Checking) }
        helper.stop()
        try {
            val hello = startClient()
            val access =
                when {
                    helper is RootClient -> Access.ROOT
                    hello.optInt("uid", -1) == 0 -> Access.SHIZUKU_ROOT
                    else -> Access.SHIZUKU_ADB
                }
            val shellOnly = access == Access.SHIZUKU_ADB
            if (!shellOnly && !hello.optBoolean("qrtrLib")) {
                log("The QRTR library didn't load (${hello.optString("libError").asClause()}), so QMI won't work.")
            }
            showConnectStep("Checking what this phone's modem offers…")
            // Probing every vendor telephony service for a modem passthrough is slow on phones that have many.
            val device = DeviceInfo.from(helper.call("detect", 90_000))

            fun allowed(candidate: Backend) = device.allows(candidate, shellOnly)
            val suggestedBackend = device.suggested().takeIf(::allowed) ?: Backend.ANDROID
            val savedBackendName = prefs.getString("backend", null)
            val savedBackend = Backend.entries.firstOrNull { it.name == savedBackendName }
            val backend = savedBackend?.takeIf(::allowed) ?: suggestedBackend
            updateState {
                copy(root = RootState.Granted, access = access, device = device, autoBackend = suggestedBackend, backend = backend)
            }
            val node =
                when {
                    shellOnly && device.mtkOemHook -> rilNode(sim = 1)
                    uiState.atNode.isNotEmpty() -> uiState.atNode
                    device.pixelRouter -> PIXEL_AT_NODE
                    else -> device.atNodes.firstOrNull { it.startsWith("/dev/radio") } ?: device.atNodes.firstOrNull() ?: ""
                }
            updateState { copy(atNode = node) }
            val accessSummary =
                when {
                    access == Access.ROOT -> "Root access granted."
                    access == Access.SHIZUKU_ROOT -> "Connected through Shizuku running as root."
                    device.mtkOemHook -> "Connected through Shizuku with ADB access. MediaTek's RIL passthrough is available, so band and cell locking work without root."
                    else -> "Connected through Shizuku with ADB access, so only the Android radio HAL features are available."
                }
            log("$accessSummary Using ${uiState.backend?.label} (chipset: ${device.vendorLabel}).")
            loadSims()
            refreshNow()
            if (pendingUnlock) {
                pendingUnlock = false
                unlockEverything()
            }
        } catch (e: Exception) {
            val why = e.message?.asSentence() ?: "Root access was denied."
            log("Couldn't connect. $why")
            updateState { copy(root = RootState.Denied(why)) }
        }
    }

    private fun showConnectStep(text: String) = updateState { copy(connectStep = text) }

    /** Coming back from the Shizuku app after allowing bunnylock there shouldn't need a tap on "Try again". */
    fun resumed() {
        if (uiState.root is RootState.Denied && ShizukuClient.granted()) connect()
    }

    private var pendingUnlock = false

    /** "Unlock all" from the notification. Waits for the helper if the app was just started. */
    fun unlockFromNotification() {
        if (uiState.root == RootState.Granted && helper.running) unlockEverything() else pendingUnlock = true
    }

    /** Lists the locks that hold. One the phone has strayed from is left out. */
    private fun updateNotification() {
        val context = getApplication<Application>()
        val lines =
            buildList {
                uiState.groups.filter { it.locked && it.stray === null }.forEach { groupState ->
                    val bands =
                        groupState.current
                            .orEmpty()
                            .sorted()
                            .joinToString(" ") { groupState.group.bandLabel(it) }
                    add("${groupState.group.label}: $bands")
                }
                uiState.plmnLock?.let { add("Network: $it") }
                if (uiState.strayChannel === null) {
                    uiState.lteLock?.let { add("LTE cell: $it") }
                    uiState.nrLock?.let { add("NR cell: $it") }
                    uiState.legacyCellLock?.let { add("2G/3G cell: $it") }
                }
            }
        if (lines.isEmpty()) {
            prefs.edit().remove("lockedSince").apply()
            LockNotifier.cancel(context)
            return
        }
        val lockedSince =
            prefs.getLong("lockedSince", 0L).takeIf { it > 0 }
                ?: System.currentTimeMillis().also { prefs.edit().putLong("lockedSince", it).apply() }
        LockNotifier.show(context, lines, lockedSince)
    }

    /** Root first, then Shizuku when su isn't available or was refused. */
    private suspend fun startClient(): JSONObject {
        showConnectStep("Asking for root access…")
        val rootClient = RootClient(getApplication())
        val rootError =
            try {
                val hello = rootClient.start()
                helper = rootClient
                return hello
            } catch (e: Exception) {
                e.message?.asClause() ?: "root access was denied"
            }
        showConnectStep("Looking for Shizuku…")
        if (!ShizukuClient.awaitBinder()) {
            val shizukuProblem =
                if (ShizukuClient.installedVersion(getApplication()) === null) {
                    "Shizuku isn't installed. Grant root, or install and start Shizuku, then try again."
                } else {
                    "Shizuku is installed but hasn't connected to bunnylock, so it probably isn't running. Open Shizuku to check, then try again."
                }
            throw RootException("No root access ($rootError), and $shizukuProblem")
        }
        showConnectStep("Connecting to Shizuku. Allow bunnylock if it asks…")
        val shizukuClient = ShizukuClient(getApplication(), ::helperLost)
        try {
            val hello = shizukuClient.start()
            helper = shizukuClient
            return hello
        } catch (e: Exception) {
            shizukuClient.stop()
            throw RootException(
                "No root access ($rootError), and Shizuku didn't work either (${e.message?.asClause() ?: "no reason given"}).",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun loadSims() {
        val context = getApplication<Application>()
        val sims = ArrayList<SimInfo>()
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.forEach { subscription ->
                    val name = subscription.displayName?.toString() ?: "SIM ${subscription.simSlotIndex + 1}"
                    sims += SimInfo(subscription.simSlotIndex, subscription.subscriptionId, name)
                }
            }
        }
        if (sims.isEmpty()) sims += SimInfo(0, SubscriptionManager.getDefaultSubscriptionId(), "SIM 1")
        sims.sortBy { it.slot }
        updateState {
            val selectedSimPresent = sims.any { it.slot == sim - 1 }
            copy(sims = sims, sim = if (selectedSimPresent) sim else sims.first().slot + 1)
        }
    }

    private fun subId(): Int =
        uiState.sims.firstOrNull { it.slot == uiState.sim - 1 }?.subId ?: SubscriptionManager.getDefaultSubscriptionId()

    fun setSim(sim: Int) {
        updateState { copy(sim = sim) }
        refresh()
    }

    fun setBackend(backend: Backend) {
        if (!uiState.allows(backend)) return
        prefs.edit().putString("backend", backend.name).apply()
        updateState {
            copy(backend = backend, groups = emptyList(), lteLock = null, nrLock = null, legacyCellLock = null, strayChannel = null)
        }
        refresh()
    }

    fun setPersist(enabled: Boolean) {
        prefs.edit().putBoolean("persist", enabled).apply()
        updateState { copy(persist = enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamicColor", enabled).apply()
        updateState { copy(dynamicColor = enabled) }
    }

    fun setAtNode(node: String) {
        prefs.edit().putString("atNode", node).apply()
        updateState { copy(atNode = node) }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        updateState { copy(log = (listOf("$time  $message") + log).take(MAX_LOG_LINES)) }
    }

    private fun String.asSentence(): String {
        val text = trim().replaceFirstChar { it.uppercase() }
        return if (text.endsWith('.') || text.endsWith('?') || text.endsWith('!')) text else "$text."
    }

    /** For quoting a message inside a sentence: no final period, and no capital unless the first word is an acronym. */
    private fun String.asClause(): String {
        val text = trim().trimEnd('.')
        val startsWithCapitalisedWord = text.length > 1 && text[0].isUpperCase() && text[1].isLowerCase()
        return if (startsWithCapitalisedWord) text[0].lowercase() + text.substring(1) else text
    }

    private fun reason(e: Exception): String = e.message?.takeIf { it.isNotBlank() }?.asSentence() ?: "Something went wrong."

    /**
     * Runs one user-requested change against the helper and reports the outcome in the log and as an event.
     * [goal] completes the sentence "Couldn't …". [block] returns the message to report when it has something more
     * specific to say than [successMessage], otherwise null.
     */
    private fun launchAction(
        goal: String,
        successMessage: String,
        refreshAfter: Boolean = true,
        block: suspend () -> String?,
    ) = viewModelScope.launch {
        if (!helper.running) {
            _events.emit("The helper isn't running, so nothing was changed. Reconnecting.")
            reconnect()
            return@launch
        }
        updateState { copy(busy = true) }
        try {
            val message = block() ?: successMessage
            log(message)
            _events.emit(message)
            if (refreshAfter) refreshNow()
        } catch (e: Exception) {
            val message = "Couldn't $goal. ${reason(e)}"
            log(message)
            _events.emit(message)
        } finally {
            updateState { copy(busy = false) }
        }
    }

    /**
     * The AT port for MediaTek commands. The selected one is kept unless it can't serve: the Pixel port isn't a MediaTek
     * one, and ADB-level access can't open a tty. In that case the helper looks for a port that answers.
     */
    private suspend fun mtkNode(): String {
        val selected = uiState.atNode
        if (selected.startsWith(RIL_NODE_PREFIX)) return rilNode(uiState.sim)
        if (selected.isNotEmpty() && selected != PIXEL_AT_NODE && !uiState.limited) return selected
        // The helper answers JSON null when no port replied, and optString turns that into "null".
        val found = helper.call("mtk.findNode") { put("sim", uiState.sim) }.optString("node")
        check(found.isNotEmpty() && found != "null") { "No MediaTek AT port answered. Pick one in Tools." }
        setAtNode(found)
        return found
    }

    /**
     * A modem tty is shared with the phone's own radio software, which can swallow the reply. When a command fails
     * there and the RIL passthrough works, the command is redone through it and the passthrough stays selected.
     */
    private suspend fun callMediatek(
        command: String,
        build: JSONObject.() -> Unit = {},
    ): JSONObject {
        val node = mtkNode()
        return try {
            helper.call(command) {
                put("node", node)
                build()
            }
        } catch (e: RootException) {
            if (node.startsWith(RIL_NODE_PREFIX) || uiState.device?.mtkOemHook != true) throw e
            val ril = rilNode(uiState.sim)
            log("$node failed (${reason(e).asClause()}), so MediaTek commands go through the RIL from now on.")
            setAtNode(ril)
            helper.call(command) {
                put("node", ril)
                build()
            }
        }
    }

    private suspend fun restartRadio() = helper.call("radio.cycle", 30_000)

    /** Restarts only the selected SIM's radio so the other stays connected. Falls back to airplane mode. */
    private suspend fun restartSimRadio() {
        val result =
            try {
                helper.call("radio.cycleSim", 60_000) {
                    put("sim", uiState.sim)
                    put("subId", subId())
                }
            } catch (e: RootException) {
                log("Couldn't restart only SIM ${uiState.sim}'s radio (${reason(e).asClause()}), so airplane mode was toggled instead.")
                restartRadio()
                return
            }
        if (result.optBoolean("airplaneMode")) {
            log("SIM ${uiState.sim}'s radio didn't restart on its own, so airplane mode was toggled instead.")
        }
    }

    /**
     * [specs] is every lock bunnylock holds and is saved whole. A band lock is left out of the request while a cell lock
     * on its network stands in for it, so clearing the cell lock brings it back.
     */
    private suspend fun setChannels(specs: JSONArray) {
        val held = specs.objects()
        val cellLockRans = held.filter { it.isCellLock }.map { it.optInt("ran") }
        val sent = JSONArray(held.filter { it.isCellLock || it.optInt("ran") !in cellLockRans })
        val result =
            helper.call("aosp.setChannels") {
                put("subId", subId())
                put("specs", sent)
            }
        result.optString("diagnostic").takeIf { it.isNotBlank() }?.let { log("HAL channel lock: $it") }
        prefs.edit().putString(halSpecsKey(uiState.sim), specs.toString()).apply()
    }

    private fun channelSpec(
        ran: Int,
        bands: List<Int>,
        channels: List<Long>,
    ): JSONObject = JSONObject().put("ran", ran).put("bands", JSONArray(bands)).put("channels", JSONArray(channels))

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private fun JSONObject.intList(key: String): List<Int>? = optJSONArray(key)?.toIntList()

    /** A band lock pins channels too on phones that need them, so [bandSpecs] marks its entries to tell them from a cell lock. */
    private val JSONObject.isCellLock get() = !optBoolean("bandLock") && !intList("channels").isNullOrEmpty()

    /** Only the LTE and NR groups reach this and [ratOf]: they are all the radio HAL can lock. */
    private fun ranOf(group: Group) = if (group == Group.LTE) Ran.EUTRAN else Ran.NGRAN

    private fun ratOf(group: Group) = if (group == Group.LTE) Rat.LTE else Rat.NR

    /**
     * [supportedBands] falls back to the standard list for [group] when the modem doesn't report its own.
     * [currentBands] is null when no restriction is known.
     */
    private fun bandGroupState(
        group: Group,
        supportedBands: List<Int>?,
        currentBands: List<Int>?,
    ): BandGroupState {
        val knownBands = supportedBands?.takeIf { it.isNotEmpty() } ?: group.defaults()
        val supported = (knownBands + currentBands.orEmpty()).distinct().sorted()
        val current = currentBands?.toSet()
        return BandGroupState(group, supported, current, selected = current ?: supported.toSet())
    }

    fun refresh() =
        viewModelScope.launch {
            if (uiState.root == RootState.Granted && !helper.running) reconnect() else refreshNow()
        }

    private suspend fun refreshNow() {
        if (!helper.running) return
        updateState { copy(refreshing = true) }
        try {
            val sim = uiState.sim
            val subscriptionId = subId()
            val aospState = runCatching { helper.call("aosp.state") { put("subId", subscriptionId) } }.getOrNull()
            val modem =
                when (uiState.backend) {
                    Backend.QUALCOMM -> loadQualcomm(sim)
                    Backend.MEDIATEK -> loadMediatek()
                    Backend.PIXEL -> loadPixel(sim, aospState)
                    Backend.ANDROID, null -> loadHal(sim, aospState)
                }
            val plmnLock = if (modem.plmnReported) modem.plmnLock else halPlmnLock(aospState)
            val telephony =
                getApplication<Application>()
                    .getSystemService(TelephonyManager::class.java)
                    .let { if (subscriptionId >= 0) it.createForSubscriptionId(subscriptionId) else it }
            val cells = Cells.merge(modem.cells, Cells.scan(getApplication(), subscriptionId))
            val groups =
                when (uiState.backend) {
                    Backend.ANDROID, null -> withStrayBands(modem, cells)
                    else -> modem.groups
                }
            val strayChannel =
                when (uiState.backend) {
                    Backend.QUALCOMM, Backend.MEDIATEK -> null
                    Backend.PIXEL, Backend.ANDROID, null -> strayChannel(cells, channelSpecs(aospState, sim))
                }
            updateState {
                copy(
                    groups = groups,
                    strayChannel = strayChannel,
                    nrMode = modem.nrMode,
                    lteLock = modem.lteLock,
                    nrLock = modem.nrLock,
                    legacyCellLock = modem.legacyCellLock,
                    plmnLock = plmnLock,
                    registeredPlmn = telephony.networkOperator?.takeIf { it.length >= 5 },
                    registeredName = telephony.networkOperatorName?.takeIf { it.isNotBlank() },
                    homePlmn = telephony.simOperator?.takeIf { it.length >= 5 },
                    homeName = telephony.simOperatorName?.takeIf { it.isNotBlank() },
                    cells = cells,
                    allowedMask = aospState?.takeIf { it.has("allowed") }?.optLong("allowed"),
                    nrDc = aospState?.takeIf { it.has("nrDc") }?.optBoolean("nrDc"),
                )
            }
            updateNotification()
        } catch (e: Exception) {
            val message = "Couldn't refresh. ${reason(e)}"
            log(message)
            _events.emit(message)
        } finally {
            updateState { copy(refreshing = false) }
        }
    }

    private fun withStrayBands(
        modem: ModemState,
        cells: List<Cell>,
    ): List<BandGroupState> =
        modem.groups.map { groupState ->
            // A HAL band lock stands by while a cell lock holds its network, and that cell may be on another band.
            val cellLock = if (groupState.group == Group.LTE) modem.lteLock else modem.nrLock
            val standingBy = cellLock !== null
            val stray = if (groupState.locked && !standingBy) strayBand(cells, groupState.group, groupState.current.orEmpty()) else null
            groupState.copy(stray = stray)
        }

    private suspend fun loadSamsungNrMode(sim: Int): NrMode? {
        if (uiState.device?.samsungNrMode != true) return null
        val mode = runCatching { helper.call("samsung.nrMode") { put("sim", sim) }.optInt("mode", -1) }.getOrNull()
        return when (mode) {
            SAMSUNG_SA_NSA -> NrMode.BOTH
            SAMSUNG_NSA_ONLY -> NrMode.NSA
            SAMSUNG_SA_ONLY -> NrMode.SA
            else -> null
        }
    }

    private suspend fun loadQualcomm(sim: Int): ModemState {
        val qcState = helper.call("qc.state") { put("sim", sim) }
        val hardware = qcState.optJSONObject("hw") ?: JSONObject()
        val hardwareNr = hardware.intList("nr")
        val groups = mutableListOf(bandGroupState(Group.LTE, hardware.intList("lte"), qcState.intList("lte")))
        when {
            qcState.has("nrSa") || qcState.has("nrNsa") -> {
                groups += bandGroupState(Group.NR_SA, hardwareNr, qcState.intList("nrSa"))
                groups += bandGroupState(Group.NR_NSA, hardwareNr, qcState.intList("nrNsa"))
            }

            !hardwareNr.isNullOrEmpty() -> {
                groups += bandGroupState(Group.NR, hardwareNr, qcState.intList("nr"))
            }
        }
        groups += bandGroupState(Group.WCDMA, hardware.intList("wcdma"), qcState.intList("wcdma"))
        groups += bandGroupState(Group.GSM, hardware.intList("gsm"), qcState.intList("gsm"))
        val samsungNrMode = if (sim in samsungNrModeReadbackSims) loadSamsungNrMode(sim) else null
        val manualSelection = qcState.optJSONObject("netSel")?.takeIf { it.optInt("mode") == QMI_NET_SEL_MANUAL }
        return ModemState(
            groups = groups,
            nrMode = samsungNrMode ?: qcNrMode(qcState.optInt("nrMode", -1)),
            lteLock = qcState.optJSONObject("lteLock")?.takeIf { it.optBoolean("active") }?.let(::qcLteLockLabel),
            nrLock = qcState.optJSONObject("nrLock")?.takeIf { it.optBoolean("active") }?.let(::qcNrLockLabel),
            plmnLock = manualSelection?.let { qcPlmnLabel(it, qcState.optJSONObject("manualPlmn"), sim) },
            plmnReported = qcState.has("netSel"),
            cells = runCatching { Cells.fromQualcomm(helper.call("qc.serving") { put("sim", sim) }) }.getOrDefault(emptyList()),
        )
    }

    private fun qcNrMode(code: Int): NrMode? =
        when (code) {
            0 -> NrMode.BOTH
            1 -> NrMode.NSA
            2 -> NrMode.SA
            else -> null
        }

    private fun qcLteLockLabel(lock: JSONObject): String {
        val cellsByEarfcn = lock.getJSONArray("cells").objects().groupBy { it.optInt("earfcn") }
        return cellsByEarfcn.entries.joinToString("; ") { (earfcn, cells) ->
            "EARFCN $earfcn, PCI ${orList(cells.map { it.optInt("pci").toString() })}"
        }
    }

    private fun qcNrLockLabel(lock: JSONObject): String =
        when (val type = lock.optInt("type")) {
            QMI_NR_LOCK_PCI -> {
                "ARFCN ${lock.optLong("arfcn")}, PCI ${lock.optInt("pci")} on n${lock.optInt("band")} at ${lock.optInt("scs")} kHz"
            }

            QMI_NR_LOCK_FREQUENCY -> {
                val arfcns =
                    lock
                        .optJSONArray("arfcns")
                        ?.objects()
                        .orEmpty()
                        .map { it.optLong("arfcn").toString() }
                if (arfcns.isEmpty()) "Frequency only" else "ARFCN ${orList(arfcns)} (frequency only)"
            }

            QMI_NR_LOCK_SEVERAL_PCIS -> {
                if (lock.has("arfcn")) "ARFCN ${lock.optLong("arfcn")}, several PCIs" else "Several PCIs"
            }

            QMI_NR_LOCK_GNB_LIST -> {
                "Only allowed gNodeBs (${lock.optJSONArray("gnbs")?.length() ?: 0})"
            }

            else -> "Lock type $type"
        }

    /** Some firmware only says that selection is manual, not for which network. The one bunnylock last set is shown then. */
    private fun qcPlmnLabel(
        netSel: JSONObject,
        manualPlmn: JSONObject?,
        sim: Int,
    ): String {
        val source =
            manualPlmn ?: netSel.takeIf { it.has("mcc") }
                ?: return prefs.getString(plmnKey(sim), null)?.let(::formatPlmn) ?: "Manual selection"
        val mnc = source.optInt("mnc")
        val threeDigit = manualPlmn?.optBoolean("pcs") == true || mnc >= 100
        val mncText = if (threeDigit) "%03d".format(mnc) else "%02d".format(mnc)
        return "%03d %s".format(source.optInt("mcc"), mncText)
    }

    private fun plmnKey(sim: Int) = "plmn.sim$sim"

    private suspend fun loadMediatek(): ModemState {
        val mtkState = callMediatek("mtk.state")
        val supported = mtkState.optJSONObject("supported") ?: JSONObject()
        val current = mtkState.optJSONObject("current") ?: JSONObject()
        val groups = mutableListOf(bandGroupState(Group.LTE, supported.intList("lte"), current.intList("lte")))
        if (supported.has("nr")) groups += bandGroupState(Group.NR, supported.intList("nr"), current.intList("nr"))
        groups += bandGroupState(Group.WCDMA, supported.intList("wcdma"), current.intList("wcdma"))
        groups += bandGroupState(Group.GSM, supported.intList("gsm"), current.intList("gsm"))
        val cellLock = mtkState.optJSONObject("cellLock")?.takeIf { it.optBoolean("active") }
        val lockLabel = cellLock?.let(::mtkCellLockLabel)
        // A reply too short to name the technology is shown as an LTE lock, the kind bunnylock sets most.
        val lockedAct = cellLock?.optInt("act", MTK_ACT_LTE)
        val nrLocked = lockedAct == MTK_ACT_NR
        val legacyLocked = lockedAct == MTK_ACT_GSM || lockedAct == MTK_ACT_UTRAN
        return ModemState(
            groups = groups,
            lteLock = lockLabel.takeIf { !nrLocked && !legacyLocked },
            nrLock = lockLabel.takeIf { nrLocked },
            legacyCellLock = lockLabel.takeIf { legacyLocked },
        )
    }

    private fun mtkCellLockLabel(lock: JSONObject): String {
        val pci = lock.optInt("pci", -1).takeIf { it >= 0 && it != MTK_PCI_UNSET }
        val channelName =
            when (lock.optInt("act")) {
                MTK_ACT_LTE -> "EARFCN"
                MTK_ACT_UTRAN -> "UARFCN"
                else -> "ARFCN"
            }
        val pciText = if (pci === null) "" else ", PCI $pci"
        return "$channelName ${lock.optInt("arfcn")}$pciText"
    }

    private suspend fun loadPixel(
        sim: Int,
        aospState: JSONObject?,
    ): ModemState {
        val pixelState = helper.call("pixel.state") { put("sim", sim) }
        val specs = channelSpecs(aospState, sim)
        val lteBands = if (pixelState.optBoolean("lteLocked")) pixelState.intList("lte") else null
        val nrBands = if (pixelState.optBoolean("nrLocked")) pixelState.intList("nr") else null
        return ModemState(
            groups =
                listOf(
                    bandGroupState(Group.LTE, supportedBands = null, currentBands = lteBands),
                    bandGroupState(Group.NR, supportedBands = null, currentBands = nrBands),
                ),
            nrMode = pixelNrMode(pixelState.optString("nrMode")),
            lteLock = channelLock(specs, Ran.EUTRAN),
            nrLock = channelLock(specs, Ran.NGRAN),
        )
    }

    private fun pixelNrMode(value: String): NrMode? =
        when (value) {
            "11" -> NrMode.BOTH
            "01" -> NrMode.NSA
            "10" -> NrMode.SA
            else -> null
        }

    private suspend fun loadHal(
        sim: Int,
        aospState: JSONObject?,
    ): ModemState {
        val specs = channelSpecs(aospState, sim)
        return ModemState(
            nrMode = loadSamsungNrMode(sim),
            groups =
                listOf(
                    bandGroupState(Group.LTE, supportedBands = null, currentBands = channelBands(specs, Ran.EUTRAN)),
                    bandGroupState(Group.NR, supportedBands = null, currentBands = channelBands(specs, Ran.NGRAN)),
                ),
            lteLock = channelLock(specs, Ran.EUTRAN),
            nrLock = channelLock(specs, Ran.NGRAN),
        )
    }

    /**
     * What bunnylock last set is what counts as a lock. The list the radio HAL reports can't tell one apart:
     * Samsung's lists the first eight bands of each network while nothing is locked. Without a request of our own,
     * only reported entries that pin channels are taken as a lock.
     */
    private fun channelSpecs(
        aospState: JSONObject?,
        sim: Int,
    ): List<JSONObject> {
        val saved = prefs.getString(halSpecsKey(sim), null)
        if (saved !== null) return runCatching { JSONArray(saved).objects() }.getOrDefault(emptyList())
        return aospState?.optJSONArray("channels")?.objects()?.filter { !it.intList("channels").isNullOrEmpty() } ?: emptyList()
    }

    /** The locks bunnylock itself holds on the current SIM, never what the radio HAL reports. */
    private fun savedChannelSpecs(): List<JSONObject> = channelSpecs(aospState = null, sim = uiState.sim)

    /** The HAL only says that Android took a lock, so a serving cell that no locked band can reach is the one sign the modem ignores it. */
    private fun strayBand(
        cells: List<Cell>,
        group: Group,
        lockedBands: Collection<Int>,
    ): Int? {
        val rat = ratOf(group)
        return cells
            .filter { it.registered && it.rat == rat }
            .firstOrNull { cell ->
                val coveringBands = if (rat == Rat.LTE) Bands.lteBandsAt(cell.arfcn.toInt()) else Bands.nrBandsOf(cell.arfcn.toInt())
                coveringBands.isNotEmpty() && coveringBands.none { it in lockedBands }
            }?.band
    }

    private fun channelBands(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int>? = specs.lastOrNull { it.optInt("ran") == ran && !it.isCellLock }?.intList("bands")?.takeIf { it.isNotEmpty() }

    private fun lockedChannels(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int>? =
        specs
            .filter { it.optInt("ran") == ran && it.isCellLock }
            .mapNotNull { spec -> spec.intList("channels")?.takeIf { it.isNotEmpty() } }
            .lastOrNull()

    private fun channelName(ran: Int) = if (ran == Ran.EUTRAN) "EARFCN" else "ARFCN"

    private fun channelLock(
        specs: List<JSONObject>,
        ran: Int,
    ): String? = lockedChannels(specs, ran)?.let { "${channelName(ran)} ${orList(it.map(Int::toString))} (frequency only)" }

    /** e.g. "EARFCN 75": the serving cell's channel when a HAL frequency lock excludes it, so the modem ignores that lock too. */
    private fun strayChannel(
        cells: List<Cell>,
        specs: List<JSONObject>,
    ): String? =
        listOf(Ran.EUTRAN to Rat.LTE, Ran.NGRAN to Rat.NR).firstNotNullOfOrNull { (ran, rat) ->
            val locked = lockedChannels(specs, ran) ?: return@firstNotNullOfOrNull null
            cells.firstOrNull { it.registered && it.rat == rat && it.arfcn.toInt() !in locked }?.let { "${channelName(ran)} ${it.arfcn}" }
        }

    /** A modem that follows a HAL lock leaves the old cell within seconds, so the wait only grows while [findStray] still finds one. */
    private suspend fun settledCells(findStray: (List<Cell>) -> Any?): List<Cell> {
        delay(HAL_LOCK_SETTLE_MS)
        var cells = Cells.scan(getApplication(), subId())
        repeat(HAL_LOCK_RECHECKS) {
            if (findStray(cells) === null) return cells
            delay(HAL_LOCK_RECHECK_MS)
            cells = Cells.scan(getApplication(), subId())
        }
        return cells
    }

    private fun halPlmnLock(aospState: JSONObject?): String? {
        if (aospState?.optInt("selectionMode") != TelephonyManager.NETWORK_SELECTION_MODE_MANUAL) return null
        val manualPlmn = aospState.optString("manualPlmn")
        return if (manualPlmn.isEmpty()) "Manual selection" else formatPlmn(manualPlmn)
    }

    private fun updateGroup(
        group: Group,
        transform: BandGroupState.() -> BandGroupState,
    ) = updateState {
        copy(groups = groups.map { if (it.group == group) it.transform() else it })
    }

    fun toggleBand(
        group: Group,
        band: Int,
    ) = updateGroup(group) {
        copy(selected = if (band in selected) selected - band else selected + band)
    }

    fun selectAll(
        group: Group,
        all: Boolean,
    ) = updateGroup(group) {
        copy(selected = if (all) supported.toSet() else emptySet())
    }

    fun applyGroup(
        group: Group,
        unlock: Boolean = false,
    ) = launchAction(
        if (unlock) "unlock the ${group.label} bands" else "lock the ${group.label} bands",
        if (unlock) "${group.label} bands unlocked." else "${group.label} bands locked.",
    ) {
        val groupState = uiState.groups.first { it.group == group }
        val bands = if (unlock) groupState.supported else groupState.selected.sorted()
        require(bands.isNotEmpty()) { "Pick at least one band." }
        val allBandsSelected = bands.containsAll(groupState.supported)
        // An empty list lifts the restriction on every backend.
        val bandsToSend = if (allBandsSelected) emptyList() else bands
        val bandsJson = JSONArray(bandsToSend)
        when (uiState.backend) {
            Backend.QUALCOMM -> {
                val kind =
                    when (group) {
                        Group.LTE -> "lte"
                        Group.NR -> "nr"
                        Group.NR_SA -> "nrSa"
                        Group.NR_NSA -> "nrNsa"
                        Group.WCDMA -> "wcdma"
                        Group.GSM -> "gsm"
                    }
                val result =
                    helper.call("qc.setBands") {
                        put("sim", uiState.sim)
                        put("kind", kind)
                        put("bands", bandsJson)
                        put("persist", uiState.persist)
                    }
                result.optJSONArray("notes")?.takeIf { it.length() > 0 }?.getString(0)
            }

            Backend.MEDIATEK -> {
                val key =
                    when (group) {
                        Group.LTE -> "lte"
                        Group.WCDMA -> "wcdma"
                        Group.GSM -> "gsm"
                        else -> "nr"
                    }
                callMediatek("mtk.setBands") { put(key, bandsJson) }
                null
            }

            Backend.PIXEL -> {
                helper.call(if (group == Group.LTE) "pixel.setLte" else "pixel.setNr") { put("bands", bandsJson) }
                restartRadio()
                "${group.label} bands ${if (unlock) "unlocked" else "locked"}. The radio restarted to apply the change."
            }

            Backend.ANDROID, null -> {
                setBandsThroughHal(group, bands, allBandsSelected)
            }
        }
    }

    /**
     * Locks [bands] through the radio HAL, or lifts the band lock when [allBandsSelected]. Returns what the user should
     * know beyond plain success: that the lock needed a workaround, or that the phone doesn't follow it.
     */
    private suspend fun setBandsThroughHal(
        group: Group,
        bands: List<Int>,
        allBandsSelected: Boolean,
    ): String? {
        val override = mapOf(group to if (allBandsSelected) emptyList() else bands)
        val bandsOnlySpecs = bandSpecs(override)
        val bandsOnlyResult = runCatching { setChannels(bandsOnlySpecs) }
        var note: String? = null
        if (bandsOnlyResult.isFailure && !allBandsSelected && !needsChannels) {
            val failure = bandsOnlyResult.exceptionOrNull()
            val error = failure?.message?.takeIf { it.isNotBlank() } ?: failure?.javaClass?.simpleName ?: "unknown error"
            log("HAL band-lock diagnostic: request=$bandsOnlySpecs; error=$error; ${bandLockProbe(group, bands)}; retry=band+channel.")
            setChannels(bandSpecs(override, withChannels = true))
            needsChannels = true
            note = "This phone needs visible frequencies together with the selected bands, so bunnylock included them in the lock."
        } else {
            bandsOnlyResult.getOrThrow()
        }
        if (allBandsSelected) return null
        val cells = settledCells { strayBand(it, group, bands) }
        val registered = cells.filter { it.registered }
        // Some Shannon RILs read a bands-only lock as "no allowed channels", so the fall to 2G/3G is detected here.
        if (!needsChannels && registered.isNotEmpty() && registered.none { it.rat == Rat.LTE || it.rat == Rat.NR }) {
            needsChannels = true
            setChannels(bandSpecs(override, withChannels = true))
            return "This phone fell back to 2G or 3G with a bands-only lock, so the lock was redone with the visible frequencies. bunnylock will do that on this phone from now on."
        }
        return strayBand(cells, group, bands)?.let { ignoredLockNote(group, it) } ?: note
    }

    private fun ignoredLockNote(
        group: Group,
        stray: Int,
    ): String {
        val samsungHint = if (uiState.device?.samsungMenu == true) " Samsung's own band menu on the Bands screen may work instead." else ""
        return "Android accepted the ${group.label} lock, but the phone is still on ${group.bandLabel(stray)}. This modem doesn't seem to follow band locks sent this way.$samsungHint"
    }

    /** Set once this phone model refused a bands-only lock or dropped to 2G/3G under one. Its RIL then needs channels too. */
    private var needsChannels: Boolean
        get() = prefs.getBoolean(channelsKey(), false)
        set(value) = prefs.edit().putBoolean(channelsKey(), value).apply()

    private fun channelsKey() = "halChannels.${uiState.device?.manufacturer}.${uiState.device?.model}"

    private fun halSpecsKey(sim: Int) = "halSpecs.sim$sim"

    private fun visibleChannels(
        rat: Rat,
        bands: List<Int>,
    ): List<Long> =
        uiState.cells
            .filter { it.rat == rat && it.band in bands }
            .map { it.arfcn }
            .distinct()
            .take(MAX_HAL_CHANNELS)

    private fun bandLockProbe(
        group: Group,
        bands: List<Int>,
    ): String {
        val rat = ratOf(group)
        val matching = visibleChannels(rat, bands).joinToString().ifEmpty { "none" }
        val visible =
            uiState.cells
                .filter { it.rat == rat }
                .take(MAX_HAL_CHANNELS)
                .joinToString { cell ->
                    "${cell.bandLabel}@${cell.arfcn}/PCI${cell.pci}${if (cell.registered) "/serving" else ""}"
                }.ifEmpty { "none" }
        return "selected=${group.label}[${bands.joinToString()}], matching channels=$matching, visible cells=$visible"
    }

    /**
     * Every lock the radio HAL should hold once [override] replaces the bands of the groups it names: the LTE and NR
     * band locks, then the cell locks on the networks [override] leaves alone.
     */
    private fun bandSpecs(
        override: Map<Group, List<Int>>,
        withChannels: Boolean = needsChannels,
    ): JSONArray {
        val specs = JSONArray()
        for (group in listOf(Group.LTE, Group.NR)) {
            val groupState = uiState.groups.firstOrNull { it.group == group } ?: continue
            val bands = override[group] ?: groupState.current?.sorted().orEmpty()
            if (bands.isEmpty() || bands.containsAll(groupState.supported)) continue
            val channels = if (withChannels) visibleChannels(ratOf(group), bands) else emptyList()
            if (withChannels && channels.isEmpty()) {
                if (group in override) {
                    log("HAL band-lock diagnostic: ${bandLockProbe(group, bands)}; retry unavailable.")
                    error(
                        "This phone needs a visible cell on the selected ${group.label} bands to lock them. Refresh the cell list and try again.",
                    )
                }
                continue
            }
            specs.put(channelSpec(ranOf(group), bands, channels).put("bandLock", true))
        }
        // Applying bands replaces whatever is locked on that network. A cell lock on the other one stays.
        val replacedRans = override.keys.map(::ranOf)
        savedChannelSpecs().filter { it.isCellLock && it.optInt("ran") !in replacedRans }.forEach { specs.put(it) }
        return specs
    }

    fun setNetMode(mode: NetMode) =
        launchAction("change the network mode", "Network mode is now \"${mode.label}\".") {
            val subscriptionId = subId()
            try {
                helper.call("aosp.setAllowed") {
                    put("subId", subscriptionId)
                    put("mask", mode.mask)
                    put("slot", uiState.sim - 1)
                }
            } catch (e: Exception) {
                if (uiState.backend != Backend.QUALCOMM) throw e
                helper.call("qc.setRat") {
                    put("sim", uiState.sim)
                    put("mask", Masks.toQmi(mode.mask))
                    put("persist", uiState.persist)
                }
            }
            null
        }

    fun setNrMode(mode: NrMode) =
        launchAction("switch the 5G mode to ${mode.label}", "5G mode switched to ${mode.label}.") {
            val samsungMode =
                when (mode) {
                    NrMode.BOTH -> SAMSUNG_SA_NSA
                    NrMode.NSA -> SAMSUNG_NSA_ONLY
                    NrMode.SA -> SAMSUNG_SA_ONLY
                }
            when (uiState.backend) {
                Backend.QUALCOMM -> {
                    try {
                        // QMI numbers the modes in NrMode's declaration order: 0 both, 1 NSA only, 2 SA only.
                        helper.call("qc.setNrMode") {
                            put("sim", uiState.sim)
                            put("mode", mode.ordinal)
                            put("persist", uiState.persist)
                        }
                        samsungNrModeReadbackSims -= uiState.sim
                    } catch (e: Exception) {
                        if (uiState.device?.samsungNrMode != true) throw e
                        helper.call("samsung.setNrMode") {
                            put("sim", uiState.sim)
                            put("mode", samsungMode)
                        }
                        samsungNrModeReadbackSims += uiState.sim
                    }
                }

                Backend.PIXEL -> {
                    val value =
                        when (mode) {
                            NrMode.BOTH -> "11"
                            NrMode.NSA -> "01"
                            NrMode.SA -> "10"
                        }
                    helper.call("pixel.setNrMode") {
                        put("sim", uiState.sim)
                        put("value", value)
                    }
                    restartRadio()
                }

                Backend.ANDROID if uiState.device?.samsungNrMode == true -> {
                    helper.call("samsung.setNrMode") {
                        put("sim", uiState.sim)
                        put("mode", samsungMode)
                    }
                }

                else -> {
                    throw IllegalStateException("The ${uiState.backend?.label ?: "current"} backend can't change the 5G mode.")
                }
            }
            null
        }

    fun setNrDc(enabled: Boolean) =
        launchAction(
            if (enabled) "turn on NSA (EN-DC)" else "turn off NSA (EN-DC)",
            if (enabled) "NSA (EN-DC) is on." else "NSA (EN-DC) is off.",
        ) {
            val requestedState = if (enabled) NR_DUAL_CONNECTIVITY_ENABLE else NR_DUAL_CONNECTIVITY_DISABLE
            helper.call("aosp.setNrDc") {
                put("subId", subId())
                put("state", requestedState)
            }
            null
        }

    fun updateForm(transform: CellForm.() -> CellForm) = updateState { copy(form = form.transform()) }

    fun fillFrom(cell: Cell) =
        updateForm {
            when (cell.rat) {
                Rat.LTE -> {
                    copy(
                        lteEarfcn = cell.arfcn.toString(),
                        ltePcis = cell.pci.toString(),
                        rat = if (rat == LockRat.NSA) rat else LockRat.LTE,
                    )
                }

                Rat.NR -> {
                    val band = cell.band ?: Bands.nrBandsOf(cell.arfcn.toInt()).firstOrNull()
                    copy(
                        nrArfcn = cell.arfcn.toString(),
                        nrPcis = cell.pci.toString(),
                        nrBand = band,
                        nrScs = band?.let { Bands.nrDefaultScs(it) } ?: nrScs,
                        nrScsPicked = nrScsPicked && band === null,
                        rat = if (rat == LockRat.NSA) rat else LockRat.NR,
                    )
                }

                else -> {
                    this
                }
            }
        }

    private fun parseInts(text: String) = text.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }

    fun lockCells() =
        launchAction("lock the cell", "Cell locked.") {
            val form = uiState.form
            val lockLte = form.rat != LockRat.NR
            val lockNr = form.rat != LockRat.LTE
            val earfcn = if (lockLte) requireNotNull(form.lteEarfcn.toLongOrNull()) { "Enter the LTE EARFCN first." } else 0L
            val ltePcis = parseInts(form.ltePcis)
            val arfcn = if (lockNr) requireNotNull(form.nrArfcn.toLongOrNull()) { "Enter the NR-ARFCN first." } else 0L
            val nrPcis = parseInts(form.nrPcis)
            val nrBand = form.nrBand ?: form.nrCandidates.firstOrNull()

            when (uiState.backend) {
                Backend.QUALCOMM -> {
                    if (lockLte) {
                        require(ltePcis.isNotEmpty()) {
                            "The Qualcomm LTE lock needs at least one PCI. List several to allow a group of sectors."
                        }
                        helper.call("qc.lteLock") {
                            put("sim", uiState.sim)
                            put("earfcn", earfcn)
                            put("pcis", JSONArray(ltePcis))
                        }
                    }
                    if (lockNr) {
                        helper.call("qc.nrLock") {
                            put("sim", uiState.sim)
                            put("arfcn", arfcn)
                            put("scs", form.nrScs)
                            nrBand?.let { put("band", it) }
                            when (nrPcis.size) {
                                0 -> {
                                    put("type", "arfcn")
                                }

                                1 -> {
                                    put("type", "pci")
                                    put("pci", nrPcis[0])
                                }

                                else -> {
                                    put("type", "multi")
                                    put("pcis", JSONArray(nrPcis))
                                }
                            }
                        }
                    }
                    null
                }

                Backend.MEDIATEK -> {
                    if (lockLte) {
                        mtkCellLock(MTK_ACT_LTE, earfcn, ltePcis.firstOrNull())
                    } else {
                        mtkCellLock(MTK_ACT_NR, arfcn, nrPcis.firstOrNull())
                    }
                    restartSimRadio()
                    if (lockLte &&
                        lockNr
                    ) {
                        "LTE anchor locked. MediaTek can only hold one lock at a time, so NR follows the anchor."
                    } else {
                        null
                    }
                }

                Backend.PIXEL, Backend.ANDROID, null -> {
                    val pcisGiven = ltePcis.isNotEmpty() || nrPcis.isNotEmpty()
                    lockCellsThroughHal(lockLte, earfcn, lockNr, arfcn, nrBand, pcisGiven)
                }
            }
        }

    /**
     * Locks to a frequency through the radio HAL. [earfcn] and [arfcn] only count when their network is being locked.
     * Returns what the user should know when the lock ended up weaker than asked for, otherwise null.
     */
    private suspend fun lockCellsThroughHal(
        lockLte: Boolean,
        earfcn: Long,
        lockNr: Boolean,
        arfcn: Long,
        nrBand: Int?,
        pcisGiven: Boolean,
    ): String? {
        val lteBand = if (lockLte) requireNotNull(Bands.lteBandOf(earfcn.toInt())) { "EARFCN $earfcn isn't in any known LTE band." } else null
        val nrLockBand = if (lockNr) requireNotNull(nrBand) { "ARFCN $arfcn isn't in any known NR band." } else null
        val lockedRans = listOfNotNull(Ran.EUTRAN.takeIf { lockLte }, Ran.NGRAN.takeIf { lockNr })
        val bandLocks = savedChannelSpecs().filter { !it.isCellLock }

        fun cellSpec(
            ran: Int,
            band: Int,
            channel: Long,
            withBands: Boolean,
            withChannels: Boolean,
        ) = channelSpec(
            ran,
            bands = if (withBands) listOf(band) else emptyList(),
            channels = if (withChannels) listOf(channel) else emptyList(),
        )

        fun specs(
            withBands: Boolean,
            withChannels: Boolean,
        ) = JSONArray().apply {
            lteBand?.let { put(cellSpec(Ran.EUTRAN, it, earfcn, withBands, withChannels)) }
            nrLockBand?.let { put(cellSpec(Ran.NGRAN, it, arfcn, withBands, withChannels)) }
            // Without channels this is a band lock itself, so it replaces the one on its network instead of standing in for it.
            bandLocks.filter { withChannels || it.optInt("ran") !in lockedRans }.forEach { put(it) }
        }
        // Radio HALs differ: some want band + channel, some only the channel, some refuse channels entirely.
        val attempts = listOf(specs(withBands = true, withChannels = true), specs(withBands = false, withChannels = true))
        val channelLocked = attempts.any { runCatching { setChannels(it) }.isSuccess }
        if (!channelLocked) {
            setChannels(specs(withBands = true, withChannels = false))
            val bandsText = listOfNotNull(lteBand?.let { "B$it" }, nrLockBand?.let { "n$it" }).joinToString(" and ")
            return "This phone's radio software doesn't accept channel locks, so only the band ($bandsText) was locked."
        }
        val heldSpecs = savedChannelSpecs()
        val cells = settledCells { strayChannel(it, heldSpecs) }
        val stray = strayChannel(cells, heldSpecs)
        if (stray !==
            null
        ) {
            return "Android accepted the cell lock, but the phone is still on $stray. This modem doesn't seem to follow frequency locks sent this way."
        }
        return if (pcisGiven) "Locked to the frequency only. The radio HAL can't lock to a PCI." else null
    }

    private suspend fun mtkCellLock(
        act: Int,
        arfcn: Long,
        pci: Int?,
    ) = callMediatek("mtk.cellLock") {
        put("act", act)
        put("arfcn", arfcn)
        pci?.let { put("pci", it) }
        put("mode", 3)
    }

    fun unlockCells() =
        launchAction("clear the cell lock", "Cell lock cleared.") {
            when (uiState.backend) {
                Backend.QUALCOMM -> {
                    if (uiState.lteLock !== null) helper.call("qc.lteUnlock") { put("sim", uiState.sim) }
                    if (uiState.nrLock !== null) helper.call("qc.nrUnlock") { put("sim", uiState.sim) }
                }

                Backend.MEDIATEK -> {
                    callMediatek("mtk.cellUnlock")
                    restartSimRadio()
                }

                else -> {
                    setChannels(JSONArray(savedChannelSpecs().filter { !it.isCellLock }))
                }
            }
            null
        }

    fun setPlmnForm(
        mcc: String? = null,
        mnc: String? = null,
    ) = updateState {
        copy(plmnMcc = (mcc ?: plmnMcc).filter(Char::isDigit).take(3), plmnMnc = (mnc ?: plmnMnc).filter(Char::isDigit).take(3))
    }

    fun pickPlmn(numeric: String) = updateState { copy(plmnMcc = numeric.take(3), plmnMnc = numeric.drop(3)) }

    fun lockPlmn() =
        launchAction("lock the network", "Network locked.") {
            val mcc = uiState.plmnMcc
            val mnc = uiState.plmnMnc
            require(mcc.length == 3) { "The MCC needs 3 digits." }
            require(mnc.length in 2..3) { "The MNC needs 2 or 3 digits." }
            val numeric = mcc + mnc
            if (uiState.backend == Backend.QUALCOMM) {
                try {
                    helper.call("qc.setPlmn") {
                        put("sim", uiState.sim)
                        put("mcc", mcc.toInt())
                        put("mnc", mnc.toInt())
                        put("threeDigit", mnc.length == 3)
                        put("persist", uiState.persist)
                    }
                    prefs.edit().putString(plmnKey(uiState.sim), numeric).apply()
                    return@launchAction "SIM ${uiState.sim} is locked to ${formatPlmn(numeric)}."
                } catch (e: Exception) {
                    log(
                        "The QMI network lock didn't work (${e.message.orEmpty().asClause()}), so Android network selection is being tried instead.",
                    )
                }
            }
            // Android answers only once the modem has registered on the network or given up, hence the long timeout.
            helper.call("aosp.setPlmn", 180_000) {
                put("subId", subId())
                put("plmn", numeric)
                put("persist", true)
            }
            "SIM ${uiState.sim} is locked to ${formatPlmn(numeric)}."
        }

    fun unlockPlmn() =
        launchAction("switch back to automatic network selection", "Back to automatic network selection.") {
            clearPlmn()
            null
        }

    private suspend fun clearPlmn() {
        if (uiState.backend ==
            Backend.QUALCOMM
        ) {
            runCatching {
                helper.call("qc.clearPlmn") {
                    put("sim", uiState.sim)
                    put("persist", uiState.persist)
                }
            }
        }
        helper.call("aosp.setPlmnAuto", 60_000) { put("subId", subId()) }
        prefs.edit().remove(plmnKey(uiState.sim)).apply()
    }

    fun unlockEverything() =
        launchAction("unlock everything", "Everything is unlocked.") {
            if (uiState.anyPlmnLock) runCatching { clearPlmn() }
            when (uiState.backend) {
                Backend.QUALCOMM -> {
                    helper.call("qc.reset") {
                        put("sim", uiState.sim)
                        put("persist", uiState.persist)
                    }
                    runCatching { helper.call("qc.lteUnlock") { put("sim", uiState.sim) } }
                    runCatching { helper.call("qc.nrUnlock") { put("sim", uiState.sim) } }
                }

                Backend.MEDIATEK -> {
                    val empty = JSONArray()
                    val hasNr = uiState.groups.any { it.group == Group.NR }
                    callMediatek("mtk.setBands") {
                        put("gsm", empty)
                        put("wcdma", empty)
                        put("lte", empty)
                        if (hasNr) put("nr", empty)
                    }
                    runCatching { callMediatek("mtk.cellUnlock") }
                }

                Backend.PIXEL -> {
                    helper.call("pixel.setLte") { put("bands", JSONArray()) }
                    helper.call("pixel.setNr") { put("bands", JSONArray()) }
                    setChannels(JSONArray())
                    restartRadio()
                }

                Backend.ANDROID, null -> {
                    setChannels(JSONArray())
                }
            }
            null
        }

    fun radioCycle() =
        launchAction("restart the radio", "Radio restarted.") {
            restartRadio()
            null
        }

    fun reattach() =
        launchAction("re-attach SIM ${uiState.sim}", "SIM ${uiState.sim} is attached again.") {
            val result =
                helper.call("qc.reattach", 60_000) {
                    put("sim", uiState.sim)
                    put("subId", subId())
                }
            if (result.optString("method") == "radio") {
                "The modem wouldn't re-attach, so the radio for SIM ${uiState.sim} was restarted instead. Reason: ${result.optString(
                    "qmiError",
                ).asClause()}."
            } else {
                null
            }
        }

    fun rebootModem() =
        launchAction("reboot the modem", "Modem is rebooting.", refreshAfter = false) {
            if (!uiState.limited && uiState.device?.qrtr == true) {
                helper.call("qc.reboot", 60_000)
                return@launchAction null
            }
            val result =
                helper.call("aosp.rebootModem", 30_000) {
                    put("sim", uiState.sim)
                    put("subId", subId())
                    put("slot", uiState.sim - 1)
                }
            if (result.optString("method") != "radio") return@launchAction null
            log("Modem reboot fallback: ${result.optString("rebootError")}; api=${result.optString("api")}")
            "The radio HAL wouldn't reboot the modem, so the cellular radio was restarted instead."
        }

    fun samsungMenu() =
        launchAction("open the Samsung band menu", "Opened the Samsung band menu.", refreshAfter = false) {
            helper.call("samsung.menu", 40_000)
            null
        }

    fun sendAt(command: String) =
        launchAction("send the AT command", "The modem replied. See the output below.", refreshAfter = false) {
            val response =
                helper.call("at", 15_000) {
                    put("node", uiState.atNode)
                    put("command", command)
                    put("timeout", 6000)
                }
            updateState { copy(toolOutput = "> $command\n${response.optString("reply")}\n\n$toolOutput".take(MAX_TOOL_OUTPUT_CHARS)) }
            null
        }

    fun sendQmi(
        service: Int,
        msg: Int,
        payload: String,
    ) = launchAction("send QMI message 0x%04X".format(msg), "QMI message sent.", refreshAfter = false) {
        val response =
            helper.call("qc.raw") {
                put("sim", uiState.sim)
                put("service", service)
                put("msg", msg)
                put("payload", payload)
            }
        val entry = "> svc $service msg 0x%04X %s\n%s".format(msg, payload, response.toString(2))
        updateState { copy(toolOutput = "$entry\n\n$toolOutput".take(MAX_TOOL_OUTPUT_CHARS)) }
        "Sent message 0x%04X. The modem replied: %s.".format(msg, response.optString("error"))
    }

    fun clearOutput() = updateState { copy(toolOutput = "") }

    /** The subscriptions the app sees. A lock for one Android doesn't have is refused outright. */
    private fun simDiagnostics(): String {
        val context = getApplication<Application>()
        val phonePermission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val sims = uiState.sims.joinToString(", ") { "SIM ${it.slot + 1} (subscription ${it.subId})" }
        return "SIMs: $sims. Selected: SIM ${uiState.sim}. Phone permission: ${if (phonePermission) "granted" else "denied"}."
    }

    fun copyDiagnostics() {
        val device = uiState.device
        val text =
            buildString {
                appendLine("bunnylock diagnostics")
                appendLine(
                    "Device: ${device?.manufacturer ?: Build.MANUFACTURER} ${device?.model ?: Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                )
                appendLine("Chipset: ${device?.vendorLabel}, platform ${device?.platform}, SoC ${device?.socModel}")
                appendLine("Baseband: ${device?.baseband}")
                appendLine("RIL: ${device?.ril}")
                appendLine("Access: ${uiState.access?.label}, backend: ${uiState.backend?.label}")
                appendLine(simDiagnostics())
                appendLine("Shizuku: ${ShizukuClient.describe(getApplication())}")
                appendLine(
                    "QMI over QRTR: ${device?.qrtr}, HAL channel-lock API: ${if (device?.aospChannels == true) "present" else "absent"}, EN-DC control: ${device?.aospNrDc}",
                )
                appendLine("AT ports: ${device?.atNodes?.joinToString(", ")}")
                appendLine("Modem command passthrough: ${device?.mtkOemHookStatus}")
                device?.mtkOemHookChecked?.takeIf { it.isNotEmpty() }?.let { appendLine("Checked: ${it.joinToString("; ")}") }
                appendLine()
                appendLine("Log:")
                uiState.log.forEach { appendLine(it) }
            }
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("bunnylock diagnostics", text))
        _events.tryEmit("Diagnostics copied to the clipboard.")
    }
}

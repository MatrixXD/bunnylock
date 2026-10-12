package dev.ririxi.bunnylock.data

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import dev.ririxi.bunnylock.core.Bands
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Declared oldest first: the cell list relies on this order to put newer technologies on top. */
enum class Rat { GSM, WCDMA, LTE, NR }

data class Cell(
    val rat: Rat,
    val arfcn: Long,
    /** Physical cell id for LTE and NR. For WCDMA it is the PSC and for GSM the BSIC, or -1 when those are unknown. */
    val pci: Int,
    val band: Int?,
    val registered: Boolean,
    /** In dBm. For WCDMA and GSM it is the plain signal strength Android reports rather than an RSRP. */
    val rsrp: Int?,
    /** "android" or "modem" (QMI report, includes the NSA SCG). */
    val source: String,
    val note: String = "",
    /** MCC+MNC digits, e.g. "26201". Usually only known for the serving cell. */
    val plmn: String? = null,
    val operator: String? = null,
) {
    val key get() = "$rat:$arfcn:$pci"

    val bandLabel: String
        get() =
            when {
                band === null -> "?"
                rat == Rat.NR -> "n$band"
                rat == Rat.LTE -> "B$band"
                else -> "$band"
            }
}

object Cells {
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    // -200 dBm is below anything a modem reports, so cells without a reading come last.
    private val displayOrder =
        compareByDescending<Cell> { it.registered }
            .thenByDescending { it.rat.ordinal }
            .thenByDescending { it.rsrp ?: -200 }

    @SuppressLint("MissingPermission")
    suspend fun scan(
        context: Context,
        subId: Int,
    ): List<Cell> {
        val defaultTelephony = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val telephony = if (subId >= 0) defaultTelephony.createForSubscriptionId(subId) else defaultTelephony
        // The refresh callback isn't guaranteed to arrive, so the wait is capped and Android's cached list is used instead.
        val cellInfos = withTimeoutOrNull(4000) { freshCellInfo(telephony) } ?: cachedCellInfo(telephony)
        return cellInfos.mapNotNull(::toCell)
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshCellInfo(telephony: TelephonyManager): List<CellInfo> =
        suspendCancellableCoroutine { cont ->
            try {
                telephony.requestCellInfoUpdate(
                    callbackExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }

                        override fun onError(
                            errorCode: Int,
                            detail: Throwable?,
                        ) {
                            if (cont.isActive) cont.resume(cachedCellInfo(telephony))
                        }
                    },
                )
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    @SuppressLint("MissingPermission")
    private fun cachedCellInfo(telephony: TelephonyManager): List<CellInfo> =
        runCatching { telephony.allCellInfo }.getOrNull() ?: emptyList()

    private fun Int.availableOrNull(): Int? = takeIf { it != CellInfo.UNAVAILABLE }

    private fun plmnOf(
        mcc: String?,
        mnc: String?,
    ) = if (mcc.isNullOrEmpty() || mnc.isNullOrEmpty()) null else mcc + mnc

    private fun toCell(info: CellInfo): Cell? {
        val cell = baseCell(info) ?: return null
        val identity = info.cellIdentity
        val plmn =
            when (identity) {
                is CellIdentityLte -> plmnOf(identity.mccString, identity.mncString)
                is CellIdentityNr -> plmnOf(identity.mccString, identity.mncString)
                is CellIdentityWcdma -> plmnOf(identity.mccString, identity.mncString)
                is CellIdentityGsm -> plmnOf(identity.mccString, identity.mncString)
                else -> null
            }
        return cell.copy(plmn = plmn, operator = identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() })
    }

    // Some radio HALs report a wrong band (e.g. n1 for an n78 ARFCN), so the ARFCN table wins on disagreement.
    private fun lteBand(
        reported: IntArray,
        earfcn: Int,
    ): Int? = Bands.lteBandOf(earfcn) ?: reported.firstOrNull()

    private fun nrBand(
        reported: IntArray,
        arfcn: Int,
    ): Int? {
        val candidates = Bands.nrBandsOf(arfcn)
        return reported.firstOrNull { it in candidates } ?: candidates.firstOrNull() ?: reported.firstOrNull()
    }

    private fun baseCell(info: CellInfo): Cell? =
        when (info) {
            is CellInfoLte -> lteCell(info)
            is CellInfoNr -> nrCell(info)
            is CellInfoWcdma -> wcdmaCell(info)
            is CellInfoGsm -> gsmCell(info)
            else -> null
        }

    private fun lteCell(info: CellInfoLte): Cell? {
        val identity = info.cellIdentity
        val earfcn = identity.earfcn
        if (earfcn == CellInfo.UNAVAILABLE || identity.pci == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.LTE,
            earfcn.toLong(),
            identity.pci,
            lteBand(identity.bands, earfcn),
            registered = info.isRegistered,
            rsrp = info.cellSignalStrength.rsrp.availableOrNull(),
            source = "android",
        )
    }

    private fun nrCell(info: CellInfoNr): Cell? {
        val identity = info.cellIdentity as CellIdentityNr
        val signal = info.cellSignalStrength as CellSignalStrengthNr
        val nrarfcn = identity.nrarfcn
        if (nrarfcn == CellInfo.UNAVAILABLE || identity.pci == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.NR,
            nrarfcn.toLong(),
            identity.pci,
            nrBand(identity.bands, nrarfcn),
            registered = info.isRegistered,
            rsrp = signal.ssRsrp.availableOrNull(),
            source = "android",
        )
    }

    private fun wcdmaCell(info: CellInfoWcdma): Cell? {
        val identity = info.cellIdentity
        if (identity.uarfcn == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.WCDMA,
            identity.uarfcn.toLong(),
            pci = identity.psc.availableOrNull() ?: -1,
            band = null,
            registered = info.isRegistered,
            rsrp = info.cellSignalStrength.dbm.availableOrNull(),
            source = "android",
        )
    }

    private fun gsmCell(info: CellInfoGsm): Cell? {
        val identity = info.cellIdentity
        if (identity.arfcn == CellInfo.UNAVAILABLE) return null
        return Cell(
            Rat.GSM,
            identity.arfcn.toLong(),
            pci = identity.bsic.availableOrNull() ?: -1,
            band = null,
            registered = info.isRegistered,
            rsrp = info.cellSignalStrength.dbm.availableOrNull(),
            source = "android",
        )
    }

    private fun JSONObject.rsrpOrNull(): Int? = optDouble("rsrp", Double.NaN).takeIf { !it.isNaN() }?.toInt()

    /** The qc.serving report is the only source that exposes the NSA SCG. */
    fun fromQualcomm(report: JSONObject): List<Cell> {
        val cells = ArrayList<Cell>()
        report.optJSONObject("lte")?.let { lte ->
            val earfcn = lte.optLong("earfcn", -1)
            if (earfcn >= 0) {
                cells +=
                    Cell(
                        Rat.LTE,
                        earfcn,
                        lte.optInt("pci"),
                        Bands.lteBandOf(earfcn.toInt()),
                        registered = true,
                        rsrp = lte.rsrpOrNull(),
                        source = "modem",
                        note = "LTE anchor (PCell)",
                    )
            }
        }
        report.optJSONObject("nr")?.let { nr ->
            val arfcn = nr.optLong("arfcn", -1)
            if (arfcn > 0 && nr.has("pci")) {
                cells +=
                    Cell(
                        Rat.NR,
                        arfcn,
                        nr.getInt("pci"),
                        Bands.nrBandsOf(arfcn.toInt()).firstOrNull(),
                        registered = true,
                        rsrp = nr.rsrpOrNull(),
                        source = "modem",
                        note = if (report.has("lte")) "NSA SCG" else "SA PCell",
                    )
            }
        }
        report.optJSONArray("lteNeighbors")?.let { neighbours ->
            for (i in 0 until neighbours.length()) {
                val neighbour = neighbours.getJSONObject(i)
                val earfcn = neighbour.optLong("earfcn")
                cells +=
                    Cell(
                        Rat.LTE,
                        earfcn,
                        neighbour.optInt("pci"),
                        Bands.lteBandOf(earfcn.toInt()),
                        registered = false,
                        rsrp = neighbour.rsrpOrNull(),
                        source = "modem",
                        note = "neighbour",
                    )
            }
        }
        return cells
    }

    /**
     * Joins both lists in display order. A cell that appears twice (same RAT, channel and PCI) becomes one entry:
     * the values of its first appearance are kept, so [primary] wins, and later ones only fill in what is missing.
     */
    fun merge(
        primary: List<Cell>,
        secondary: List<Cell>,
    ): List<Cell> {
        val byKey = LinkedHashMap<String, Cell>()
        for (cell in primary + secondary) {
            val earlier = byKey[cell.key]
            byKey[cell.key] = earlier?.copy(
                registered = earlier.registered || cell.registered,
                rsrp = earlier.rsrp ?: cell.rsrp,
                note = earlier.note.ifEmpty { cell.note },
                band = earlier.band ?: cell.band,
                plmn = earlier.plmn ?: cell.plmn,
                operator = earlier.operator ?: cell.operator,
            ) ?: cell
        }
        return byKey.values.sortedWith(displayOrder)
    }
}

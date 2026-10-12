package dev.ririxi.bunnylock.root

import dev.ririxi.bunnylock.core.Bands
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Same AT commands as MediaTek's EngineerMode:
 *  - AT+EPBSE  : GSM / UMTS / LTE band masks (decimal 32-bit words)
 *  - AT+EPBSEH : NR band mask (hex words), plus GSM/UMTS/LTE echoed in hex
 *  - AT+EMMCHLCK=<on>,<act>,<gsm1900>,<arfcn>[,<pci>[,<mode>]] : channel / cell lock
 *    act: 0 GSM, 2 UMTS, 7 LTE, 11 NR
 *    mode: 0 idle only, 3 idle + connected
 */
object Mediatek {
    val CANDIDATES =
        listOf(
            "/dev/radio/pttycmd1",
            "/dev/radio/atci1",
            "/dev/radio/pttyeng",
            "/dev/radio/atci-serv-fw",
            "/dev/ttyC0",
            "/dev/radio/ptty2cmd1",
            "/dev/radio/atci2",
        )

    // +EPBSE words: GSM, UMTS, then eight LTE words (bands 1-32, 33-64 and so on).
    private const val GSM_WORD = 0
    private const val UMTS_WORD = 1
    private const val FIRST_LTE_WORD = 2
    private const val LTE_WORD_COUNT = 8
    private const val EPBSE_WORD_COUNT = FIRST_LTE_WORD + LTE_WORD_COUNT

    // The short form of +EPBSE stops after the first two LTE words.
    private const val EPBSE_SHORT_FIELD_COUNT = 4

    // +EPBSEH fields: GSM, UMTS, LTE, NR. Only the first three LTE words are sent in it.
    private const val EPBSEH_NR_FIELD = 3
    private const val EPBSEH_LTE_WORD_COUNT = 3
    private const val HEX_DIGITS_PER_WORD = 8

    /** Bit of each GSM band, by its MHz name, in the GSM word. */
    private val GSM_BITS = mapOf(900 to 1, 1800 to 3, 1900 to 4, 850 to 7)

    fun probe(node: String): Boolean = runCatching { At.send(node, "AT", 1500).lines().any { it == "OK" } }.getOrDefault(false)

    /** A modem tty when it answers (root), otherwise the RIL passthrough for [sim] (works through Shizuku too). */
    fun findNode(sim: Int = 1): String? =
        CANDIDATES.firstOrNull { At.exists(it) && probe(it) }
            ?: "${OemHook.PREFIX}$sim".takeIf { At.exists(it) && probe(it) }

    private class Epbse(
        val words: LongArray,
        val fieldCount: Int,
    ) {
        val hasExtendedLte get() = fieldCount > 5
    }

    private fun parseEpbse(reply: String): Epbse? {
        val line = reply.lines().firstOrNull { it.startsWith("+EPBSE:") } ?: return null
        val fields = line.removePrefix("+EPBSE:").split(',').map { it.trim() }
        val words = LongArray(EPBSE_WORD_COUNT)
        for (i in 0 until minOf(EPBSE_WORD_COUNT, fields.size)) words[i] = fields[i].toLongOrNull() ?: 0L
        return Epbse(words, fields.size)
    }

    /** The NR field is a run of 8-digit hex words. A shorter last word is padded with zeros on the right. */
    private fun parseEpbsehNr(reply: String): LongArray? {
        val line = reply.lines().firstOrNull { it.startsWith("+EPBSEH:") } ?: return null
        val hex =
            line
                .removePrefix("+EPBSEH:")
                .split(',')
                .getOrNull(EPBSEH_NR_FIELD)
                ?.trim()
                ?.trim('"') ?: return null
        if (hex.isEmpty()) return null
        return hex.chunked(HEX_DIGITS_PER_WORD).map { it.padEnd(HEX_DIGITS_PER_WORD, '0').toLong(16) }.toLongArray()
    }

    private fun gsmOf(word: Long) = GSM_BITS.filter { (_, bit) -> word and (1L shl bit) != 0L }.keys.sorted()

    private fun umtsOf(word: Long) = (0 until 32).filter { word and (1L shl it) != 0L }.map { it + 1 }

    private fun lteOf(words: LongArray) = Bands.fromWords(words.copyOfRange(FIRST_LTE_WORD, EPBSE_WORD_COUNT))

    private fun wordOf(bits: List<Int>): Long = bits.fold(0L) { word, bit -> word or (1L shl bit) }

    private fun gsmWord(bands: List<Int>): Long = wordOf(bands.mapNotNull { GSM_BITS[it] })

    private fun umtsWord(bands: List<Int>): Long = wordOf(bands.map { it - 1 })

    private fun bandsJson(words: LongArray): JSONObject =
        JSONObject()
            .put("gsm", JSONArray(gsmOf(words[GSM_WORD])))
            .put("wcdma", JSONArray(umtsOf(words[UMTS_WORD])))
            .put("lte", JSONArray(lteOf(words)))

    fun state(node: String): JSONObject {
        val out = JSONObject().put("node", node)
        val supported = parseEpbse(At.send(node, "AT+EPBSE=?"))
        val current = parseEpbse(At.send(node, "AT+EPBSE?"))
        if (supported !== null) {
            out.put("supported", bandsJson(supported.words))
            out.put("lteExtended", supported.hasExtendedLte)
        }
        if (current !== null) out.put("current", bandsJson(current.words))
        runCatching { parseEpbsehNr(At.send(node, "AT+EPBSEH=?")) }.getOrNull()?.let { nrWords ->
            out.getJSONObject("supported").put("nr", JSONArray(Bands.fromWords(nrWords)))
            out.put("nrWords", nrWords.size)
        }
        runCatching { parseEpbsehNr(At.send(node, "AT+EPBSEH?")) }.getOrNull()?.let { nrWords ->
            out.optJSONObject("current")?.put("nr", JSONArray(Bands.fromWords(nrWords)))
        }
        runCatching { out.put("cellLock", cellLockState(node)) }
        return out
    }

    private fun cellLockState(node: String): JSONObject {
        val line =
            At.send(node, "AT+EMMCHLCK?").lines().firstOrNull { it.startsWith("+EMMCHLCK:") }
                ?: return JSONObject().put("active", false)
        // +EMMCHLCK: <on>,<act>,<gsm1900>,<arfcn>,<pci>[,<mode>]
        val fields = line.removePrefix("+EMMCHLCK:").split(',').map { it.trim().toIntOrNull() ?: -1 }
        val lock = JSONObject().put("active", fields.getOrNull(0) == 1)
        if (fields.size >= 5) lock.put("act", fields[1]).put("arfcn", fields[3]).put("pci", fields[4])
        if (fields.size >= 6) lock.put("mode", fields[5])
        return lock
    }

    private fun readEpbse(
        node: String,
        command: String,
        problem: String,
    ): Epbse {
        val reply = At.send(node, command)
        return parseEpbse(reply) ?: throw IOException("$problem ($command replied: ${reply.lines().joinToString(" ")}).")
    }

    /** Any argument left null keeps the modem's current value. An empty list means "all supported". */
    fun setBands(
        node: String,
        gsm: List<Int>?,
        wcdma: List<Int>?,
        lte: List<Int>?,
        nr: List<Int>?,
    ): JSONObject {
        val supported = readEpbse(node, "AT+EPBSE=?", "The AT port $node doesn't support band locking")
        val current = readEpbse(node, "AT+EPBSE?", "Couldn't read the current bands from $node")
        val words = current.words.copyOf()
        if (gsm !== null) words[GSM_WORD] = if (gsm.isEmpty()) supported.words[GSM_WORD] else gsmWord(gsm)
        if (wcdma !== null) words[UMTS_WORD] = if (wcdma.isEmpty()) supported.words[UMTS_WORD] else umtsWord(wcdma)
        if (lte !== null) {
            val lteWords =
                if (lte.isEmpty()) {
                    supported.words.copyOfRange(FIRST_LTE_WORD, EPBSE_WORD_COUNT)
                } else {
                    Bands
                        .toWords(lte, LTE_WORD_COUNT)
                }
            lteWords.copyInto(words, destinationOffset = FIRST_LTE_WORD)
        }
        if (gsm !== null || wcdma !== null || lte !== null) At.ok(node, epbseCommand(words, supported.hasExtendedLte))
        if (nr !== null) {
            val supportedNr =
                parseEpbsehNr(At.send(node, "AT+EPBSEH=?"))
                    ?: throw IOException("This modem doesn't support AT+EPBSEH, so it may not have 5G.")
            val nrWords = if (nr.isEmpty()) supportedNr else Bands.toWords(nr, supportedNr.size)
            At.ok(node, epbsehCommand(words, nrWords))
        }
        return state(node)
    }

    private fun epbseCommand(
        words: LongArray,
        extendedLte: Boolean,
    ): String {
        val fieldCount = if (extendedLte) EPBSE_WORD_COUNT else EPBSE_SHORT_FIELD_COUNT
        return "AT+EPBSE=" + (0 until fieldCount).joinToString(",") { words[it].toString() }
    }

    /** +EPBSEH sets every technology at once, so the GSM, UMTS and LTE words go in next to the NR ones. */
    private fun epbsehCommand(
        words: LongArray,
        nrWords: LongArray,
    ): String {
        val gsm = hex32(words[GSM_WORD])
        val umts = hex32(words[UMTS_WORD])
        val lte = (FIRST_LTE_WORD until FIRST_LTE_WORD + EPBSEH_LTE_WORD_COUNT).joinToString("") { hex32(words[it]) }
        val nr = nrWords.joinToString("") { hex32(it) }
        return "AT+EPBSEH=\"$gsm\",\"$umts\",\"$lte\",\"$nr\""
    }

    private fun hex32(word: Long): String = "%08x".format(word and 0xFFFFFFFFL)

    /** Without a [pci] this locks the channel only, and [mode] isn't sent. */
    fun cellLock(
        node: String,
        act: Int,
        arfcn: Long,
        pci: Int?,
        mode: Int,
    ): JSONObject {
        // <on> = 1, <gsm1900> = 0
        val channelLock = "AT+EMMCHLCK=1,$act,0,$arfcn"
        val command = if (pci === null) channelLock else "$channelLock,$pci,$mode"
        At.ok(node, command)
        return cellLockState(node)
    }

    fun cellUnlock(node: String): JSONObject {
        At.ok(node, "AT+EMMCHLCK=0")
        return cellLockState(node)
    }
}

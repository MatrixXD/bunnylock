package dev.ririxi.bunnylock.root

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

/** libbunnyqrtr.so is loaded by RootServer. */
object Qrtr {
    @JvmStatic external fun open(
        service: Int,
        timeoutMs: Int,
    ): Int

    @JvmStatic external fun transact(
        fd: Int,
        request: ByteArray,
        timeoutMs: Int,
    ): ByteArray

    @JvmStatic external fun close(fd: Int)
}

class QmiException(
    message: String,
) : IOException(message)

// A QMI message on QRTR: type (u8), transaction id (u16), message id (u16), body length (u16), then the body.
private const val QMI_HEADER_SIZE = 7
private const val QMI_BODY_LENGTH_OFFSET = 5
private const val QMI_TYPE_REQUEST: Byte = 0

// The body is a run of TLVs: id (u8), length (u16), value.
private const val TLV_HEADER_SIZE = 3

// Every response carries this TLV: result (u16, 0 on success) and error code (u16).
private const val TLV_RESULT = 0x02

class Tlvs {
    private val out = ByteArrayOutputStream()

    fun add(
        id: Int,
        value: ByteArray,
    ): Tlvs {
        out.write(id)
        out.write(LittleEndian.u16(value.size))
        out.write(value)
        return this
    }

    fun u8(
        id: Int,
        value: Int,
    ) = add(id, byteArrayOf(value.toByte()))

    fun u32(
        id: Int,
        value: Long,
    ) = add(id, LittleEndian.u32(value))

    fun bytes(): ByteArray = out.toByteArray()
}

/** Every integer in QMI is little-endian. */
object LittleEndian {
    fun u16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    fun u32(value: Long) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    fun u64(value: Long) = ByteArray(8) { (value shr (8 * it)).toByte() }
}

/** Reads little-endian fields one after another, and fails when the modem sent fewer bytes than the layout needs. */
class Reader(
    private val data: ByteArray,
) {
    private var offset = 0

    val remaining get() = data.size - offset

    private fun need(byteCount: Int) {
        if (offset + byteCount > data.size) throw QmiException("The modem's reply was shorter than expected.")
    }

    private fun unsigned(byteCount: Int): Long {
        need(byteCount)
        var value = 0L
        for (i in 0 until byteCount) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        offset += byteCount
        return value
    }

    fun u8(): Int = unsigned(1).toInt()

    fun u16(): Int = unsigned(2).toInt()

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long = unsigned(4)

    fun i32(): Int = u32().toInt()

    fun u64(): Long = unsigned(8)

    fun bytes(byteCount: Int): ByteArray {
        need(byteCount)
        val result = data.copyOfRange(offset, offset + byteCount)
        offset += byteCount
        return result
    }

    fun skip(byteCount: Int) {
        need(byteCount)
        offset += byteCount
    }
}

private fun u16At(
    bytes: ByteArray,
    offset: Int,
): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/** A TLV that claims to run past the end of the message ends the parse. The ones before it are kept. */
private fun parseTlvs(message: ByteArray): Map<Int, ByteArray> {
    val end = minOf(message.size, QMI_HEADER_SIZE + u16At(message, QMI_BODY_LENGTH_OFFSET))
    val tlvs = LinkedHashMap<Int, ByteArray>()
    var position = QMI_HEADER_SIZE
    while (position + TLV_HEADER_SIZE <= end) {
        val id = message[position].toInt() and 0xFF
        val length = u16At(message, position + 1)
        val valueStart = position + TLV_HEADER_SIZE
        if (valueStart + length > end) break
        tlvs[id] = message.copyOfRange(valueStart, valueStart + length)
        position = valueStart + length
    }
    return tlvs
}

class QmiResponse(
    val raw: ByteArray,
) {
    val tlvs: Map<Int, ByteArray> = parseTlvs(raw)
    val result: Int
    val error: Int

    init {
        val resultTlv = tlvs[TLV_RESULT]
        if (resultTlv !== null && resultTlv.size >= 4) {
            val reader = Reader(resultTlv)
            result = reader.u16()
            error = reader.u16()
        } else {
            result = -1
            error = -1
        }
    }

    val ok get() = result == 0

    fun tlv(id: Int): ByteArray? = tlvs[id]

    fun check(what: String): QmiResponse {
        if (!ok) throw QmiException("The modem refused $what: ${errorName(error)}.")
        return this
    }

    companion object {
        fun errorName(code: Int): String =
            when (code) {
                -1 -> "no result from the modem"
                0x00 -> "no error (0x00)"
                0x01 -> "a malformed request (0x01)"
                0x02 -> "out of memory (0x02)"
                0x03 -> "an internal error (0x03)"
                0x05 -> "no free slot for another app, which restarting the phone fixes (0x05)"
                0x0E -> "call failed (0x0E)"
                0x10 -> "not provisioned (0x10)"
                0x11 -> "a missing value (0x11)"
                0x13 -> "a value that's too long (0x13)"
                0x19 -> "not supported by this modem (0x19)"
                0x1A -> "no change needed (0x1A)"
                0x1E -> "an invalid profile type (0x1E)"
                0x1F -> "an invalid service type (0x1F)"
                0x29 -> "an invalid QoS ID (0x29)"
                0x2F -> "an unknown error (0x2F)"
                0x30 -> "an invalid value (0x30)"
                0x34 -> "not ready yet (0x34)"
                0x3C -> "an invalid state change (0x3C)"
                0x4A -> "info not available (0x4A)"
                0x52 -> "access denied (0x52)"
                0x5A -> "not possible in the modem's current state (0x5A)"
                0x5E -> "not supported (0x5E)"
                else -> "error 0x%04X".format(code)
            }
    }
}

class QmiClient(
    service: Int,
    private val timeoutMs: Int = 3000,
) : Closeable {
    private val fd = Qrtr.open(service, timeoutMs)
    private var transactionId = (System.nanoTime() and 0x7FFF).toInt()

    fun send(
        msgId: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Int = this.timeoutMs,
    ): QmiResponse {
        transactionId = (transactionId + 1) and 0xFFFF
        if (transactionId == 0) transactionId = 1
        val header =
            byteArrayOf(QMI_TYPE_REQUEST) +
                LittleEndian.u16(transactionId) +
                LittleEndian.u16(msgId) +
                LittleEndian.u16(payload.size)
        return QmiResponse(Qrtr.transact(fd, header + payload, timeoutMs))
    }

    fun send(
        msgId: Int,
        tlvs: Tlvs,
    ) = send(msgId, tlvs.bytes())

    override fun close() = Qrtr.close(fd)
}

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

fun String.unhex(): ByteArray {
    val digits = filter { !it.isWhitespace() && it != ':' && it != ',' }
    require(digits.length % 2 == 0) { "The hex payload has an odd number of digits." }
    return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

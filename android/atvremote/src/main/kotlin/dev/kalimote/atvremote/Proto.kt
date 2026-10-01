package dev.kalimote.atvremote

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal protobuf wire-format codec. The Android TV remote protocol only uses
 * varints and length-delimited fields, so a full protobuf runtime is not needed.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun tag(field: Int, wire: Int) = writeVarint(out, ((field shl 3) or wire).toLong())

    fun varint(field: Int, value: Long): ProtoWriter = apply {
        tag(field, 0)
        writeVarint(out, value)
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, 2)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun writeVarint(out: OutputStream, value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
    }
}

/** A decoded message: field number to the list of raw values (Long or ByteArray). */
class ProtoMessage(bytes: ByteArray) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= bytes.size) throw ProtocolException("Truncated varint")
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw ProtocolException("Varint too long")
            }
        }
        while (pos < bytes.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> readVarint()
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > bytes.size) throw ProtocolException("Truncated field")
                    bytes.copyOfRange(pos, pos + len).also { pos += len }
                }
                1 -> bytes.copyOfRange(pos, pos + 8).also { pos += 8 }
                5 -> bytes.copyOfRange(pos, pos + 4).also { pos += 4 }
                else -> throw ProtocolException("Unsupported wire type")
            }
            fields.getOrPut(field) { mutableListOf() }.add(value)
        }
    }

    fun has(field: Int) = fields.containsKey(field)

    fun long(field: Int, default: Long = 0): Long = fields[field]?.lastOrNull() as? Long ?: default

    fun int(field: Int, default: Int = 0): Int = (fields[field]?.lastOrNull() as? Long)?.toInt() ?: default

    fun bool(field: Int): Boolean = long(field) != 0L

    fun bytes(field: Int): ByteArray? = fields[field]?.lastOrNull() as? ByteArray

    fun string(field: Int, default: String = ""): String =
        bytes(field)?.toString(Charsets.UTF_8) ?: default

    fun message(field: Int): ProtoMessage? = bytes(field)?.let { ProtoMessage(it) }
}

class ProtocolException(message: String) : java.io.IOException(message)

/** Reads/writes varint-length-prefixed frames. */
object Framing {
    fun write(out: OutputStream, payload: ByteArray) {
        val buf = ByteArrayOutputStream(payload.size + 5)
        ProtoWriter.writeVarint(buf, payload.size.toLong())
        buf.write(payload)
        out.write(buf.toByteArray())
        out.flush()
    }

    fun read(input: InputStream): ByteArray {
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed")
            len = len or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw ProtocolException("Frame length too long")
        }
        if (len > 1 shl 20) throw ProtocolException("Frame too large")
        val data = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(data, read, len - read)
            if (n < 0) throw EOFException("Connection closed")
            read += n
        }
        return data
    }
}

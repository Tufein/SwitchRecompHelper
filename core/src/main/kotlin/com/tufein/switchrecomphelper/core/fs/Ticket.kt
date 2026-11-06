package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.SwitchFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal parser for .tik tickets in an NSP: just the rights id and the (still titlekek-encrypted) title key. */
object Ticket {
    class Parsed(val rightsId: ByteArray, val encryptedTitleKey: ByteArray)

    fun parse(data: ByteArray): Parsed {
        if (data.size < 8) throw SwitchFormatException("Ticket is too small")
        val sigType = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        val (sig, pad) = when (sigType) {
            0x10000, 0x10003 -> 0x200 to 0x3C
            0x10001, 0x10004 -> 0x100 to 0x3C
            0x10002, 0x10005 -> 0x3C to 0x40
            else -> throw SwitchFormatException("Unknown ticket signature type 0x${sigType.toString(16)}")
        }
        val body = 4 + sig + pad
        if (data.size < body + 0x170) throw SwitchFormatException("Ticket is truncated")
        if (data[body + 0x141].toInt() != 0) {
            throw SwitchFormatException("Personalized (console-bound) tickets are not supported; add the title key via title.keys")
        }
        return Parsed(data.copyOfRange(body + 0x160, body + 0x170), data.copyOfRange(body + 0x40, body + 0x50))
    }
}

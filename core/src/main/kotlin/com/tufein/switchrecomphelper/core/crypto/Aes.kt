package com.tufein.switchrecomphelper.core.crypto

import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readFully
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesUtil {
    fun ecbDecrypt(key: ByteArray, data: ByteArray): ByteArray = ecb(Cipher.DECRYPT_MODE, key, data)
    fun ecbEncrypt(key: ByteArray, data: ByteArray): ByteArray = ecb(Cipher.ENCRYPT_MODE, key, data)

    private fun ecb(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    /**
     * AES-128-XTS as used by Nintendo for NCA headers: the 32-byte [key] is data key || tweak key,
     * and the tweak is the sector number as a *big-endian* 128-bit integer (standard XTS is little-endian).
     */
    fun xtsDecrypt(key: ByteArray, data: ByteArray, firstSector: Long, sectorSize: Int = 0x200): ByteArray =
        xts(Cipher.DECRYPT_MODE, key, data, firstSector, sectorSize)

    fun xtsEncrypt(key: ByteArray, data: ByteArray, firstSector: Long, sectorSize: Int = 0x200): ByteArray =
        xts(Cipher.ENCRYPT_MODE, key, data, firstSector, sectorSize)

    private fun xts(mode: Int, key: ByteArray, data: ByteArray, firstSector: Long, sectorSize: Int): ByteArray {
        require(key.size == 32) { "XTS key must be 32 bytes" }
        require(data.size % 16 == 0 && sectorSize % 16 == 0) { "XTS data must be a multiple of 16 bytes" }
        val dataCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(mode, SecretKeySpec(key, 0, 16, "AES"))
        }
        val tweakCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 16, 16, "AES"))
        }
        val out = ByteArray(data.size)
        var sector = firstSector
        var pos = 0
        val block = ByteArray(16)
        while (pos < data.size) {
            val len = minOf(sectorSize, data.size - pos)
            val tweakIn = ByteArray(16)
            for (i in 0 until 8) tweakIn[15 - i] = (sector ushr (8 * i)).toByte()
            val t = tweakCipher.doFinal(tweakIn)
            var b = 0
            while (b < len) {
                for (i in 0 until 16) block[i] = (data[pos + b + i].toInt() xor t[i].toInt()).toByte()
                val res = dataCipher.doFinal(block)
                for (i in 0 until 16) out[pos + b + i] = (res[i].toInt() xor t[i].toInt()).toByte()
                mulAlpha(t)
                b += 16
            }
            pos += len
            sector++
        }
        return out
    }

    /** Multiply the XTS tweak by x in GF(2^128) (little-endian convention). */
    private fun mulAlpha(t: ByteArray) {
        var carry = 0
        for (i in 0 until 16) {
            val c = (t[i].toInt() ushr 7) and 1
            t[i] = ((t[i].toInt() shl 1) or carry).toByte()
            carry = c
        }
        if (carry != 0) t[0] = (t[0].toInt() xor 0x87).toByte()
    }

    /** Builds the 16-byte NCA section counter: [upperIv] (8 bytes) || big-endian (absoluteOffset / 16). */
    fun ncaCounter(upperIv: ByteArray, absoluteOffset: Long): ByteArray {
        val iv = ByteArray(16)
        upperIv.copyInto(iv, 0, 0, 8)
        val blocks = absoluteOffset ushr 4
        for (i in 0 until 8) iv[15 - i] = (blocks ushr (8 * i)).toByte()
        return iv
    }
}

/**
 * Transparent AES-128-CTR decryption of an NCA section.
 * [counterBase] is the absolute offset inside the NCA of the first byte of [inner]; the counter is derived from it.
 */
class AesCtrSource(
    private val inner: RandomAccessSource,
    key: ByteArray,
    private val upperIv: ByteArray,
    private val counterBase: Long,
) : RandomAccessSource {
    override val size: Long get() = inner.size
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

    @Synchronized
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val n = minOf(length.toLong(), size - position).toInt()
        val alignedStart = position and 15L.inv()
        val head = (position - alignedStart).toInt()
        val buf = ByteArray(head + n)
        inner.readFully(alignedStart, buf)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(AesUtil.ncaCounter(upperIv, counterBase + alignedStart)))
        val plain = cipher.doFinal(buf)
        System.arraycopy(plain, head, dst, offset, n)
        return n
    }
}

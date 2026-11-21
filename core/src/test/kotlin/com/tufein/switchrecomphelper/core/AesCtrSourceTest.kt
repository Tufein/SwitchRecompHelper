package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.crypto.AesCtrSource
import com.tufein.switchrecomphelper.core.crypto.AesUtil
import com.tufein.switchrecomphelper.core.io.ByteArraySource
import com.tufein.switchrecomphelper.core.io.readFully
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals

class AesCtrSourceTest {
    private val key = ByteArray(16) { (it * 7 + 1).toByte() }
    private val upper = ByteArray(8) { (it + 9).toByte() }

    /** Reference: the keystream of the 16-byte blocks that contain [absoluteStart], applied from the block start. */
    private fun reference(plain: ByteArray, absoluteStart: Long): ByteArray {
        val aligned = absoluteStart and 15L.inv()
        val head = (absoluteStart - aligned).toInt()
        val padded = ByteArray(head + plain.size).also { plain.copyInto(it, head) }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(AesUtil.ncaCounter(upper, aligned)))
        return c.doFinal(padded).copyOfRange(head, head + plain.size)
    }

    @Test
    fun decryptsFromAnyNcaOffsetNotOnlyAlignedOnes() {
        val rnd = Random(3)
        val cipherText = ByteArray(4096).also(rnd::nextBytes)
        for (base in listOf(0L, 0x200L, 5L, 0x1234L + 7)) {
            val src = AesCtrSource(ByteArraySource(cipherText), key, upper, base)
            repeat(40) {
                val at = rnd.nextInt(cipherText.size - 1)
                val len = 1 + rnd.nextInt(minOf(300, cipherText.size - at))
                val got = ByteArray(len)
                src.readFully(at.toLong(), got)
                // Decrypting the ciphertext is the same operation as encrypting it (CTR).
                val expected = reference(cipherText.copyOfRange(at, at + len), base + at)
                assertContentEquals(expected, got, "base $base, read at $at, length $len")
            }
        }
    }
}

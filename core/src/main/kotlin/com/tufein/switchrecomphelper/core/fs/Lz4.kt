package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.SwitchFormatException

/**
 * LZ4 *block* format decoder (no frame header), as used for the segments of an NSO.
 * Every read and copy is bounds-checked: the input comes from an untrusted dump.
 */
object Lz4 {
    /** Decompresses [length] bytes at [offset] of [src] into exactly [outputSize] bytes. */
    fun decompressBlock(src: ByteArray, offset: Int, length: Int, outputSize: Int, what: String = "LZ4 data"): ByteArray {
        if (offset < 0 || length < 0 || offset > src.size - length || outputSize < 0) {
            throw SwitchFormatException("$what: invalid range")
        }
        val out = ByteArray(outputSize)
        val end = offset + length
        var ip = offset
        var op = 0
        fun corrupt(): Nothing = throw SwitchFormatException("$what is corrupt (LZ4 decompression failed)")

        while (ip < end) {
            val token = src[ip++].toInt() and 0xFF

            var literals = token ushr 4
            if (literals == 15) {
                while (true) {
                    if (ip >= end) corrupt()
                    val b = src[ip++].toInt() and 0xFF
                    literals += b
                    if (literals > outputSize) corrupt()
                    if (b != 255) break
                }
            }
            if (literals > end - ip || literals > outputSize - op) corrupt()
            System.arraycopy(src, ip, out, op, literals)
            ip += literals
            op += literals

            if (ip == end) break // the last sequence has literals only

            if (end - ip < 2) corrupt()
            val distance = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            if (distance == 0 || distance > op) corrupt()

            var match = token and 0x0F
            if (match == 15) {
                while (true) {
                    if (ip >= end) corrupt()
                    val b = src[ip++].toInt() and 0xFF
                    match += b
                    if (match > outputSize) corrupt()
                    if (b != 255) break
                }
            }
            match += 4
            if (match > outputSize - op) corrupt()
            var from = op - distance
            if (distance >= match) {
                System.arraycopy(out, from, out, op, match)
                op += match
            } else {
                // Overlapping copy (e.g. run-length): must go byte by byte.
                repeat(match) { out[op++] = out[from++] }
            }
        }
        if (op != outputSize) throw SwitchFormatException("$what is corrupt (decompressed to $op bytes, expected $outputSize)")
        return out
    }
}

package com.tufein.switchrecomphelper.core.fs

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.io.readFully
import com.tufein.switchrecomphelper.core.io.slice
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NCZ = compressed NCA (NSZ/XCZ). Layout: the first 0x4000 bytes of the NCA verbatim, an "NCZSECTN" table
 * (with the section keys, which we do not need because the body is stored decrypted), then the body from
 * NCA offset 0x4000 on, either as one zstd stream or as independent zstd blocks ("NCZBLOCK").
 */
object Ncz {
    const val HEADER_SIZE = 0x4000L

    /** The decrypted NCA body; position 0 corresponds to NCA offset 0x4000. [contentSize] is the full NCA size. */
    fun openBody(ncz: RandomAccessSource, contentSize: Long): RandomAccessSource {
        val total = contentSize - HEADER_SIZE
        if (total <= 0) throw SwitchFormatException("NCZ content size is invalid")
        val head = ncz.readBytes(HEADER_SIZE, 0x10)
        if (String(head, 0, 8, Charsets.US_ASCII) != "NCZSECTN") throw SwitchFormatException("Not an NCZ file (no NCZSECTN table)")
        val count = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(8)
        if (count < 1 || count > 1024) throw SwitchFormatException("NCZ section table is corrupt")
        val bodyStart = HEADER_SIZE + 0x10 + count * 0x40
        if (bodyStart + 8 > ncz.size) throw SwitchFormatException("NCZ file is truncated")
        val isBlock = String(ncz.readBytes(bodyStart, 8), Charsets.US_ASCII) == "NCZBLOCK"
        return if (isBlock) BlockSource(ncz, bodyStart) else StreamSource(ncz.slice(bodyStart, ncz.size - bodyStart), total)
    }

    /** Independent zstd blocks: real random access, one block cached. */
    private class BlockSource(private val src: RandomAccessSource, start: Long) : RandomAccessSource {
        private val blockSize: Long
        private val numBlocks: Int
        override val size: Long
        private val offsets: LongArray
        private val sizes: IntArray
        private var cachedIndex = -1
        private var cached = ByteArray(0)

        init {
            val h = ByteBuffer.wrap(src.readBytes(start, 0x18)).order(ByteOrder.LITTLE_ENDIAN)
            val exponent = h.get(0x0B).toInt() and 0xFF
            numBlocks = h.getInt(0x0C)
            size = h.getLong(0x10)
            if (exponent !in 12..30 || numBlocks < 1 || numBlocks > MAX_BLOCKS || size <= 0) {
                throw SwitchFormatException("NCZ block header is corrupt")
            }
            blockSize = 1L shl exponent
            if (size > blockSize * numBlocks || size <= blockSize * (numBlocks - 1)) throw SwitchFormatException("NCZ block header is inconsistent")
            val table = ByteBuffer.wrap(src.readBytes(start + 0x18, numBlocks * 4)).order(ByteOrder.LITTLE_ENDIAN)
            sizes = IntArray(numBlocks) { table.getInt(it * 4) }
            offsets = LongArray(numBlocks)
            var o = start + 0x18 + numBlocks * 4L
            for (i in 0 until numBlocks) {
                if (sizes[i] <= 0 || sizes[i] > blockSize) throw SwitchFormatException("NCZ block $i has an invalid size")
                offsets[i] = o
                o += sizes[i]
            }
            if (o > src.size) throw SwitchFormatException("NCZ file is truncated")
        }

        private fun block(i: Int): ByteArray {
            if (i == cachedIndex) return cached
            val rawLen = if (i == numBlocks - 1) (size - blockSize * i).toInt() else blockSize.toInt()
            val comp = ByteArray(sizes[i])
            src.readFully(offsets[i], comp)
            val out = if (sizes[i] >= rawLen) {
                comp.copyOf(rawLen) // stored uncompressed
            } else {
                ByteArray(rawLen).also {
                    val n = Zstd.decompressByteArray(it, 0, rawLen, comp, 0, comp.size)
                    if (Zstd.isError(n) || n != rawLen.toLong()) throw SwitchFormatException("NCZ block $i is corrupt")
                }
            }
            cachedIndex = i
            cached = out
            return out
        }

        @Synchronized
        override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            var done = 0
            while (done < n) {
                val p = position + done
                val b = block((p / blockSize).toInt())
                val inBlock = (p % blockSize).toInt()
                val c = minOf(n - done, b.size - inBlock)
                System.arraycopy(b, inBlock, dst, offset + done, c)
                done += c
            }
            return n
        }

        private companion object {
            const val MAX_BLOCKS = 1 shl 24
        }
    }

    /**
     * One zstd stream: forward-only. A read behind the current position restarts decompression, so callers
     * should read in increasing order (the extractor sorts its work that way).
     */
    private class StreamSource(private val compressed: RandomAccessSource, override val size: Long) : RandomAccessSource {
        private var stream: InputStream? = null
        private var pos = 0L
        private val skipBuf = ByteArray(1 shl 20)

        private class SourceStream(val src: RandomAccessSource) : InputStream() {
            var p = 0L
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = src.read(p, b, off, len)
                if (n <= 0) return -1
                p += n
                return n
            }
        }

        private fun reopen() {
            stream?.close()
            stream = ZstdInputStream(BufferedInputStream(SourceStream(compressed), 1 shl 20))
            pos = 0
        }

        @Synchronized
        override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || position >= size) return -1
            if (stream == null || position < pos) reopen()
            val s = stream!!
            try {
                while (pos < position) {
                    val n = s.read(skipBuf, 0, minOf(skipBuf.size.toLong(), position - pos).toInt())
                    if (n <= 0) throw SwitchFormatException("NCZ stream ended early (truncated dump?)")
                    pos += n
                }
                val want = minOf(length.toLong(), size - position).toInt()
                var done = 0
                while (done < want) {
                    val n = s.read(dst, offset + done, want - done)
                    if (n <= 0) break
                    done += n
                    pos += n
                }
                if (done == 0) throw SwitchFormatException("NCZ stream ended early (truncated dump?)")
                return done
            } catch (e: java.io.IOException) {
                // zstd reports corrupt/truncated data as an IOException; make it a user-presentable error and force a clean restart
                stream?.runCatching { close() }
                stream = null
                throw SwitchFormatException("NCZ data is corrupt or truncated (${e.message})")
            }
        }
    }
}

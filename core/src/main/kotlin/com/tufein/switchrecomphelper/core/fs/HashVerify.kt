package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readFully
import java.security.MessageDigest

/** One level of an IVFC (HierarchicalIntegrity) tree: [size] bytes at [offset] in the section, hashed in blocks of 2^[blockOrder]. */
class IvfcLevel(val offset: Long, val size: Long, val blockOrder: Int)

/**
 * Checks the SHA-256 hash tree of a section while the data is read. Every block that is handed out has been hashed
 * and compared with the hash one level up; the top level is compared with the master hash of the FS header.
 * Verified blocks are kept in a small cache, so sequential reads of many small files hash each block once.
 *
 * Nintendo's rule for the last block: it is zero-padded to the full block size before hashing (IVFC). The
 * HierarchicalSha256 layer hashes only the bytes that exist (see [Sha256Verifier]).
 */
internal class BlockCache(private val maxBytes: Long = 8L shl 20) {
    private val map = LinkedHashMap<Long, ByteArray>(64, 0.75f, true)
    private var bytes = 0L

    fun get(key: Long): ByteArray? = map[key]

    fun put(key: Long, block: ByteArray) {
        map.put(key, block)?.let { bytes -= it.size }
        bytes += block.size
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            bytes -= it.next().value.size
            it.remove()
        }
    }
}

internal fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

/**
 * Hash-verified view of an IVFC RomFS. [levels] are the info levels from the FS header (the last one is the data
 * itself, [levels].size - 1 hash levels sit before it), [master] the 32-byte master hash.
 */
class IvfcVerifier(
    private val section: RandomAccessSource,
    private val levels: List<IvfcLevel>,
    private val master: ByteArray,
) {
    private val cache = BlockCache()

    /** The RomFS image; every read is verified. */
    val data: RandomAccessSource = object : RandomAccessSource {
        override val size: Long = levels.last().size

        @Synchronized
        override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || position >= size) return -1
            val want = minOf(length.toLong(), size - position).toInt()
            var done = 0
            val last = levels.size - 1
            val blockSize = 1L shl levels[last].blockOrder
            while (done < want) {
                val p = position + done
                val b = p / blockSize
                val block = block(last, b)
                val inBlock = (p % blockSize).toInt()
                val n = minOf(want - done, (blockSize - inBlock).toInt())
                System.arraycopy(block, inBlock, dst, offset + done, n)
                done += n
            }
            return done
        }
    }

    init {
        if (levels.size < 2) throw SwitchFormatException("RomFS hash layout is invalid (${levels.size} levels)")
        if (master.size != 32) throw SwitchFormatException("RomFS master hash has the wrong size")
        for ((i, lv) in levels.withIndex()) {
            if (lv.blockOrder !in MIN_ORDER..MAX_ORDER) throw SwitchFormatException("RomFS level $i has an invalid block size (2^${lv.blockOrder})")
            if (lv.offset < 0 || lv.size < 0 || lv.offset > section.size || lv.size > section.size - lv.offset) {
                throw SwitchFormatException("RomFS level $i lies outside the section (truncated or corrupt dump?)")
            }
        }
        if (levels.last().size <= 0) throw SwitchFormatException("RomFS data size is zero")
        if (levels[0].size > (1L shl levels[0].blockOrder)) throw SwitchFormatException("RomFS top hash level does not fit in one block")
        for (i in 1 until levels.size) {
            val blocks = (levels[i].size + (1L shl levels[i].blockOrder) - 1) shr levels[i].blockOrder
            if (blocks * 32 > levels[i - 1].size) throw SwitchFormatException("RomFS hash level ${i - 1} is too small for level $i")
        }
    }

    /** Block [index] of [level], zero-padded to the block size, verified against the level above (or the master hash). */
    private fun block(level: Int, index: Long): ByteArray {
        val key = (level.toLong() shl 48) or index
        cache.get(key)?.let { return it }
        val order = levels[level].blockOrder
        val blockSize = 1 shl order
        val start = index shl order
        val lv = levels[level]
        if (index < 0 || start >= lv.size) throw SwitchFormatException("RomFS hash block $index of level $level is out of range")
        val raw = ByteArray(blockSize)
        section.readFully(lv.offset + start, raw, 0, minOf(blockSize.toLong(), lv.size - start).toInt())
        val actual = sha256(raw)
        if (level == 0) {
            if (!actual.contentEquals(master)) throw mismatch(level, index)
        } else {
            // The hash of block [index] of this level is entry [index] in the level above.
            val entryPos = index * 32
            val parentOrder = levels[level - 1].blockOrder
            val parent = block(level - 1, entryPos shr parentOrder)
            val at = (entryPos and ((1L shl parentOrder) - 1)).toInt()
            if (!actual.contentEquals(parent.copyOfRange(at, at + 32))) throw mismatch(level, index)
        }
        cache.put(key, raw)
        return raw
    }

    private fun mismatch(level: Int, index: Long) =
        SwitchFormatException("RomFS hash check failed (level $level, block $index): the dump is corrupt, or the keys are wrong. Turn off the hash check to extract anyway.")

    companion object {
        const val MIN_ORDER = 5
        const val MAX_ORDER = 30
    }
}

/**
 * HierarchicalSha256 (PFS0 sections such as ExeFS): the hash table holds one SHA-256 per [blockSize] bytes of the
 * data, the master hash is the SHA-256 of the whole table. The last block is hashed over the bytes it has.
 */
class Sha256Verifier(
    private val table: RandomAccessSource,
    private val dataSource: RandomAccessSource,
    private val blockSize: Int,
    master: ByteArray,
) {
    private val hashes: ByteArray
    private val cache = BlockCache()

    val data: RandomAccessSource = object : RandomAccessSource {
        override val size: Long = dataSource.size

        @Synchronized
        override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
            if (position < 0 || position >= size) return -1
            val want = minOf(length.toLong(), size - position).toInt()
            var done = 0
            while (done < want) {
                val p = position + done
                val b = p / blockSize
                val block = block(b)
                val inBlock = (p % blockSize).toInt()
                val n = minOf(want - done, block.size - inBlock)
                System.arraycopy(block, inBlock, dst, offset + done, n)
                done += n
            }
            return done
        }
    }

    init {
        if (blockSize < 32 || blockSize and (blockSize - 1) != 0) throw SwitchFormatException("PFS0 hash block size is invalid ($blockSize)")
        if (table.size > MAX_TABLE) throw SwitchFormatException("PFS0 hash table is implausibly large")
        hashes = table.readBytesAll()
        if (!sha256(hashes).contentEquals(master)) {
            throw SwitchFormatException("PFS0 hash table does not match the master hash: the dump is corrupt, or the keys are wrong. Turn off the hash check to extract anyway.")
        }
        val blocks = (dataSource.size + blockSize - 1) / blockSize
        if (blocks * 32 > hashes.size) throw SwitchFormatException("PFS0 hash table is too small for the data")
    }

    private fun block(index: Long): ByteArray {
        cache.get(index)?.let { return it }
        val start = index * blockSize
        val n = minOf(blockSize.toLong(), dataSource.size - start).toInt()
        val raw = ByteArray(n)
        dataSource.readFully(start, raw)
        val expected = hashes.copyOfRange((index * 32).toInt(), (index * 32 + 32).toInt())
        if (!sha256(raw).contentEquals(expected)) {
            throw SwitchFormatException("PFS0 hash check failed (block $index): the dump is corrupt. Turn off the hash check to extract anyway.")
        }
        cache.put(index, raw)
        return raw
    }

    private companion object {
        const val MAX_TABLE = 64L shl 20
    }
}

private fun RandomAccessSource.readBytesAll(): ByteArray {
    val out = ByteArray(size.toInt())
    readFully(0, out)
    return out
}

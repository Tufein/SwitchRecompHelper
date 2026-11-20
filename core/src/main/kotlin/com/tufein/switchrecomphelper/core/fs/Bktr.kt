package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.UnsupportedSectionException
import com.tufein.switchrecomphelper.core.crypto.AesCtrSource
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.io.readFully
import com.tufein.switchrecomphelper.core.io.slice
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One entry of a [BucketTree] lookup: the entry bytes and the virtual range it covers, [start] until [end]. */
class BucketEntry(val start: Long, val end: Long, val raw: ByteArray) {
    fun s64(at: Int): Long = ByteBuffer.wrap(raw, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
    fun s32(at: Int): Int = ByteBuffer.wrap(raw, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

/**
 * Bucket tree ("BKTR") table of a patch section: a 0x10-byte header, one 16 KiB L1 node with the virtual start of
 * every entry set, and the entry sets (16 KiB each, a node header followed by fixed-size entries sorted by virtual
 * offset). Only the one-level layout is supported; a table so large that it needs L2 nodes is rejected.
 */
class BucketTree private constructor(
    private val table: RandomAccessSource,
    private val entrySize: Int,
    private val starts: LongArray,
    /** Virtual size covered by the tree. */
    val endOffset: Long,
) {
    private val entriesPerSet = (NODE_SIZE - NODE_HEADER) / entrySize
    private val entrySetBase = HEADER_SIZE + NODE_SIZE.toLong()
    private var cachedSet = -1
    private var cachedNode = ByteArray(0)

    /** Entry that covers virtual offset [va]. Throws if [va] is outside the tree. */
    @Synchronized
    fun find(va: Long): BucketEntry {
        if (va < 0 || va >= endOffset || starts.isEmpty() || va < starts[0]) throw SwitchFormatException("Patch table has no entry for offset 0x${va.toString(16)}")
        // Last entry set whose first entry is at or before va.
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= va) lo = mid else hi = mid - 1
        }
        val set = lo
        val node = readSet(set)
        val count = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        if (count < 1 || count > entriesPerSet) throw SwitchFormatException("Patch table entry set $set is corrupt")
        // Last entry in this set whose virtual offset is at or before va.
        var a = 0
        var b = count - 1
        while (a < b) {
            val mid = (a + b + 1) ushr 1
            if (entryVa(node, mid) <= va) a = mid else b = mid - 1
        }
        val start = entryVa(node, a)
        if (start > va) throw SwitchFormatException("Patch table entry set $set starts after its first entry")
        val end = when {
            a + 1 < count -> entryVa(node, a + 1)
            set + 1 < starts.size -> starts[set + 1]
            else -> endOffset
        }
        if (end <= start) throw SwitchFormatException("Patch table entries are not sorted")
        val raw = node.copyOfRange(NODE_HEADER + a * entrySize, NODE_HEADER + (a + 1) * entrySize)
        return BucketEntry(start, end, raw)
    }

    private fun entryVa(node: ByteArray, index: Int): Long =
        ByteBuffer.wrap(node, NODE_HEADER + index * entrySize, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun readSet(set: Int): ByteArray {
        if (set == cachedSet) return cachedNode
        val node = table.readBytes(entrySetBase + set.toLong() * NODE_SIZE, NODE_SIZE)
        val bb = ByteBuffer.wrap(node).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != set) throw SwitchFormatException("Patch table entry set $set has the wrong index")
        cachedSet = set
        cachedNode = node
        return node
    }

    companion object {
        const val MAGIC = 0x52544B42 // "BKTR"
        const val HEADER_SIZE = 0x10
        const val NODE_SIZE = 0x4000
        private const val NODE_HEADER = 0x10
        private const val OFFSETS_PER_L1 = (NODE_SIZE - NODE_HEADER) / 8

        /**
         * Opens the table in [table] (which starts with the BKTR header). [entrySize] is 0x14 for relocation entries
         * and 0x10 for subsection (AES-CTR) entries.
         */
        fun open(table: RandomAccessSource, entrySize: Int, what: String): BucketTree {
            if (table.size < HEADER_SIZE + NODE_SIZE) throw SwitchFormatException("$what: patch table is truncated")
            val header = ByteBuffer.wrap(table.readBytes(0, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
            if (header.getInt(0) != MAGIC) throw SwitchFormatException("$what: patch table has a bad signature (wrong keys or corrupt dump?)")
            if (header.getInt(4) > 1) throw UnsupportedSectionException("$what: patch table version ${header.getInt(4)} is not supported")
            val entryCount = header.getInt(8)
            if (entryCount < 0) throw SwitchFormatException("$what: patch table entry count is negative")
            val perSet = (NODE_SIZE - NODE_HEADER) / entrySize
            val setCount = (entryCount + perSet - 1) / perSet
            if (entryCount == 0) return BucketTree(table, entrySize, LongArray(0), 0)
            if (setCount > OFFSETS_PER_L1) throw UnsupportedSectionException("$what: patch table is too large (needs L2 nodes, not supported yet)")

            val l1 = ByteBuffer.wrap(table.readBytes(HEADER_SIZE.toLong(), NODE_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
            if (l1.getInt(0) != 0) throw SwitchFormatException("$what: patch table root node is corrupt")
            if (l1.getInt(4) != setCount) throw SwitchFormatException("$what: patch table root node does not match its entry count")
            val endOffset = l1.getLong(8)
            val starts = LongArray(setCount) { l1.getLong(NODE_HEADER + it * 8) }
            for (i in starts.indices) {
                if (starts[i] < 0 || (i > 0 && starts[i] <= starts[i - 1])) throw SwitchFormatException("$what: patch table offsets are not sorted")
            }
            if (endOffset <= starts.last()) throw SwitchFormatException("$what: patch table end offset is invalid")
            if (table.size < HEADER_SIZE + NODE_SIZE.toLong() * (1 + setCount)) throw SwitchFormatException("$what: patch table is truncated")
            return BucketTree(table, entrySize, starts, endOffset)
        }
    }
}

/**
 * Patch section data as seen by the relocation layer: the subsections of the patch data each have their own
 * AES-CTR generation. [cipherData] is the raw (encrypted) patch data, [counterBase] the NCA offset of the section.
 */
class AesCtrExSource(
    private val cipherData: RandomAccessSource,
    private val table: BucketTree,
    private val key: ByteArray,
    private val secureValue: Int,
    private val counterBase: Long,
) : RandomAccessSource {
    override val size: Long = cipherData.size

    init {
        if (table.endOffset > cipherData.size) throw SwitchFormatException("Patch subsection table extends past the patch data")
    }

    @Synchronized
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val want = minOf(length.toLong(), size - position).toInt()
        var done = 0
        while (done < want) {
            val cur = position + done
            val e = table.find(cur)
            val piece = minOf((want - done).toLong(), e.end - cur).toInt()
            val encrypted = e.raw[8].toInt() == 0
            if (!encrypted) {
                cipherData.readFully(cur, dst, offset + done, piece)
            } else {
                val gen = e.s32(0xC)
                // Same IV layout as a normal section: secure value and generation in the upper 8 bytes, big-endian.
                val upper = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(secureValue).putInt(gen).array()
                // The keystream is aligned to 16 bytes: decrypt from the aligned offset and keep the bytes asked for.
                val aligned = cur and 15L.inv()
                val head = (cur - aligned).toInt()
                val buf = ByteArray(head + piece)
                AesCtrSource(cipherData.slice(aligned, buf.size.toLong()), key, upper, counterBase + aligned).readFully(0, buf)
                System.arraycopy(buf, head, dst, offset + done, piece)
            }
            done += piece
        }
        return done
    }
}

/**
 * The patched RomFS image (the indirect layer): each relocation entry maps a virtual range to either the base
 * section (storage 0) or the patch data (storage 1).
 */
class IndirectSource(
    private val base: RandomAccessSource,
    private val patch: RandomAccessSource,
    private val table: BucketTree,
) : RandomAccessSource {
    override val size: Long = table.endOffset

    @Synchronized
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val want = minOf(length.toLong(), size - position).toInt()
        var done = 0
        while (done < want) {
            val cur = position + done
            val e = table.find(cur)
            val piece = minOf((want - done).toLong(), e.end - cur).toInt()
            val phys = e.s64(8) + (cur - e.start)
            val src = when (val storage = e.s32(0x10)) {
                0 -> base
                1 -> patch
                else -> throw SwitchFormatException("Patch relocation uses unknown storage $storage")
            }
            src.readFully(phys, dst, offset + done, piece)
            done += piece
        }
        return done
    }
}

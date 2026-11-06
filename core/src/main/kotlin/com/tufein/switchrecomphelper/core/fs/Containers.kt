package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.io.slice
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FsEntry(val name: String, val offset: Long, val size: Long)

/**
 * Flat partition filesystem. Used for HFS0 (gamecard partitions, 0x40-byte entries)
 * and PFS0 (ExeFS / NSP, 0x18-byte entries).
 */
class PartitionFs private constructor(private val source: RandomAccessSource, val entries: List<FsEntry>) {
    fun find(name: String): FsEntry? = entries.firstOrNull { it.name == name }
    fun open(entry: FsEntry): RandomAccessSource = source.slice(entry.offset, entry.size)

    companion object {
        fun parseHfs0(source: RandomAccessSource): PartitionFs = parse(source, "HFS0", 0x40)
        fun parsePfs0(source: RandomAccessSource): PartitionFs = parse(source, "PFS0", 0x18)

        private fun parse(source: RandomAccessSource, magic: String, entrySize: Int): PartitionFs {
            val head = source.readBytes(0, 0x10)
            if (String(head, 0, 4, Charsets.US_ASCII) != magic) {
                throw SwitchFormatException("Not a $magic partition (bad magic). Wrong keys or not a valid dump?")
            }
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.getInt(4)
            val strSize = bb.getInt(8)
            if (count < 0 || count > 0x10000 || strSize < 0 || strSize > 0x100000) {
                throw SwitchFormatException("$magic header is corrupt (count=$count, string table=$strSize)")
            }
            val tableSize = count * entrySize
            val table = source.readBytes(0x10, tableSize + strSize)
            val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            val dataStart = 0x10L + tableSize + strSize
            val entries = ArrayList<FsEntry>(count)
            for (i in 0 until count) {
                val base = i * entrySize
                val offset = tb.getLong(base)
                val size = tb.getLong(base + 8)
                val nameOff = tb.getInt(base + 16)
                if (nameOff < 0 || nameOff >= strSize) throw SwitchFormatException("$magic entry $i has an invalid name offset")
                var end = tableSize + nameOff
                while (end < table.size && table[end] != 0.toByte()) end++
                val name = String(table, tableSize + nameOff, end - (tableSize + nameOff), Charsets.UTF_8)
                if (offset < 0 || size < 0 || offset > source.size - dataStart || size > source.size - dataStart - offset) {
                    throw SwitchFormatException("$magic entry '$name' lies outside the file (truncated dump?)")
                }
                entries.add(FsEntry(name, dataStart + offset, size))
            }
            return PartitionFs(source, entries)
        }
    }
}

class RomFsFile(val path: String, val offset: Long, val size: Long)

object RomFs {
    private const val NONE = -1

    /** Lists every file in a RomFS image. [source] must start at the RomFS header. */
    fun list(source: RandomAccessSource): List<RomFsFile> {
        try {
            return listInternal(source)
        } catch (e: IndexOutOfBoundsException) {
            throw SwitchFormatException("RomFS metadata is corrupt")
        }
    }

    private fun listInternal(source: RandomAccessSource): List<RomFsFile> {
        val h = ByteBuffer.wrap(source.readBytes(0, 0x50)).order(ByteOrder.LITTLE_ENDIAN)
        if (h.getLong(0) != 0x50L) throw SwitchFormatException("Not a RomFS image (bad header size). Wrong keys?")
        val dirMetaOff = h.getLong(0x18)
        val dirMetaSize = h.getLong(0x20)
        val fileMetaOff = h.getLong(0x38)
        val fileMetaSize = h.getLong(0x40)
        val dataOff = h.getLong(0x48)
        for (v in longArrayOf(dirMetaOff, dirMetaSize, fileMetaOff, fileMetaSize, dataOff)) {
            if (v < 0 || v > source.size) throw SwitchFormatException("RomFS header is corrupt")
        }
        if (dirMetaSize > MAX_META || fileMetaSize > MAX_META) throw SwitchFormatException("RomFS metadata is implausibly large")
        val dirs = ByteBuffer.wrap(source.readBytes(dirMetaOff, dirMetaSize.toInt())).order(ByteOrder.LITTLE_ENDIAN)
        val files = ByteBuffer.wrap(source.readBytes(fileMetaOff, fileMetaSize.toInt())).order(ByteOrder.LITTLE_ENDIAN)

        val result = ArrayList<RomFsFile>()
        val visited = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, String>>()
        stack.addLast(0 to "")
        while (stack.isNotEmpty()) {
            val (dirOff, dirPath) = stack.removeLast()
            if (!visited.add(dirOff)) throw SwitchFormatException("RomFS directory loop detected")
            var childDir = dirs.getInt(dirOff + 8)
            var childFile = dirs.getInt(dirOff + 12)

            var guard = 0
            while (childFile != NONE) {
                if (++guard > files.limit()) throw SwitchFormatException("RomFS file list loop detected")
                val sibling = files.getInt(childFile + 4)
                val off = files.getLong(childFile + 8)
                val size = files.getLong(childFile + 16)
                val name = readName(files, childFile + 0x20, files.getInt(childFile + 0x1C))
                if (off < 0 || size < 0 || dataOff + off < 0 || dataOff + off > source.size || size > source.size - dataOff - off) {
                    throw SwitchFormatException("RomFS file '$name' lies outside the image (truncated dump?)")
                }
                result.add(RomFsFile(dirPath + name, dataOff + off, size))
                childFile = sibling
            }
            guard = 0
            while (childDir != NONE) {
                if (++guard > dirs.limit()) throw SwitchFormatException("RomFS directory list loop detected")
                val sibling = dirs.getInt(childDir + 4)
                val name = readName(dirs, childDir + 0x18, dirs.getInt(childDir + 0x14))
                stack.addLast(childDir to "$dirPath$name/")
                childDir = sibling
            }
        }
        return result
    }

    private const val MAX_META = 256L * 1024 * 1024

    private fun readName(buf: ByteBuffer, offset: Int, len: Int): String {
        if (len < 0 || offset < 0 || offset + len > buf.limit()) throw SwitchFormatException("RomFS entry name is out of range")
        val b = ByteArray(len)
        for (i in 0 until len) b[i] = buf.get(offset + i)
        return String(b, Charsets.UTF_8)
    }
}

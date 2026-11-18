package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.toHex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class NsoSegment(
    val name: String,
    val fileOffset: Int,
    val memoryOffset: Int,
    /** Size in memory, i.e. after decompression. */
    val size: Int,
    /** Size as stored in the file. */
    val storedSize: Int,
    val compressed: Boolean,
    /** The header asks the loader to check [hash] (SHA-256 of the decompressed segment). */
    val checkHash: Boolean,
    val hash: ByteArray,
)

/** The 0x100-byte header of an NSO executable (main, rtld, sdk, subsdk*). */
class NsoHeader internal constructor(
    internal val raw: ByteArray,
    val segments: List<NsoSegment>,
    val moduleNameOffset: Int,
    val moduleNameSize: Int,
    /** Module id ("build id"), the key that Atmosphère patches, cheats and recomp configs use for an executable. */
    val buildId: ByteArray,
) {
    val buildIdHex: String get() = buildId.toHex().uppercase()

    /** Size of the normalised, uncompressed NSO that [Nso.decompress] produces. */
    val uncompressedFileSize: Long get() = Nso.HEADER_SIZE.toLong() + moduleNameSize + segments.sumOf { it.size.toLong() }
}

class DecompressedNso(
    val bytes: ByteArray,
    /** One entry per segment (.text, .rodata, .data): true = SHA-256 matched, false = no hash check requested by the header. */
    val segmentHashChecked: List<Boolean>,
    val sha256: String,
)

/**
 * NSO executables in ExeFS store their segments LZ4-compressed. Static recompilers, disassemblers and
 * loaders want the plain segments, so we also produce an uncompressed NSO: same header with the
 * compression flags cleared, the module name directly after the header, then .text, .rodata and .data
 * back to back. Segment hashes from the header are checked, so a corrupt dump fails instead of producing
 * a subtly wrong executable.
 */
object Nso {
    const val HEADER_SIZE = 0x100
    private const val MAX_SEGMENT = 512 * 1024 * 1024
    private val SEGMENT_NAMES = listOf(".text", ".rodata", ".data")

    fun isNso(source: RandomAccessSource): Boolean =
        source.size >= HEADER_SIZE && String(source.readBytes(0, 4), Charsets.US_ASCII) == "NSO0"

    fun parseHeader(source: RandomAccessSource, what: String = "NSO"): NsoHeader {
        if (source.size < HEADER_SIZE) throw SwitchFormatException("$what is too small to be an NSO")
        val raw = source.readBytes(0, HEADER_SIZE)
        if (String(raw, 0, 4, Charsets.US_ASCII) != "NSO0") throw SwitchFormatException("$what is not an NSO (bad magic)")
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val flags = bb.getInt(0x0C)
        val segments = SEGMENT_NAMES.mapIndexed { i, name ->
            val h = 0x10 + i * 0x10
            val fileOffset = bb.getInt(h)
            val memoryOffset = bb.getInt(h + 4)
            val size = bb.getInt(h + 8)
            val compressed = flags and (1 shl i) != 0
            val storedSize = if (compressed) bb.getInt(0x60 + i * 4) else size
            if (size < 0 || size > MAX_SEGMENT || storedSize < 0 || storedSize > MAX_SEGMENT || fileOffset < HEADER_SIZE ||
                fileOffset.toLong() + storedSize > source.size
            ) {
                throw SwitchFormatException("$what: segment $name lies outside the file (truncated or corrupt dump?)")
            }
            NsoSegment(name, fileOffset, memoryOffset, size, storedSize, compressed, flags and (1 shl (i + 3)) != 0, raw.copyOfRange(0xA0 + i * 0x20, 0xC0 + i * 0x20))
        }
        val nameOffset = bb.getInt(0x1C)
        val nameSize = bb.getInt(0x2C)
        if (nameSize < 0 || nameSize > 0x1000 || (nameSize > 0 && (nameOffset < HEADER_SIZE || nameOffset.toLong() + nameSize > source.size))) {
            throw SwitchFormatException("$what: module name lies outside the file")
        }
        return NsoHeader(raw, segments, nameOffset, nameSize, raw.copyOfRange(0x40, 0x60))
    }

    /** Builds the uncompressed NSO and checks every segment hash the header asks for. */
    fun decompress(source: RandomAccessSource, header: NsoHeader, what: String = "NSO"): DecompressedNso {
        val total = header.uncompressedFileSize
        if (total > Int.MAX_VALUE) throw SwitchFormatException("$what is too large to decompress")
        val out = ByteArray(total.toInt())
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        header.raw.copyInto(out)
        var pos = HEADER_SIZE
        if (header.moduleNameSize > 0) {
            source.readBytes(header.moduleNameOffset.toLong(), header.moduleNameSize).copyInto(out, pos)
        }
        bb.putInt(0x1C, if (header.moduleNameSize > 0) pos else 0)
        pos += header.moduleNameSize

        val checked = ArrayList<Boolean>(3)
        val sha = MessageDigest.getInstance("SHA-256")
        for ((i, seg) in header.segments.withIndex()) {
            val stored = source.readBytes(seg.fileOffset.toLong(), seg.storedSize)
            val plain = if (seg.compressed) Lz4.decompressBlock(stored, 0, stored.size, seg.size, "$what ${seg.name}") else stored
            if (plain.size != seg.size) throw SwitchFormatException("$what ${seg.name} has the wrong size")
            if (seg.checkHash) {
                sha.reset()
                if (!MessageDigest.isEqual(sha.digest(plain), seg.hash)) {
                    throw SwitchFormatException("$what ${seg.name}: SHA-256 does not match the NSO header (corrupt dump or wrong keys?)")
                }
            }
            checked.add(seg.checkHash)
            plain.copyInto(out, pos)
            bb.putInt(0x10 + i * 0x10, pos) // file offset
            bb.putInt(0x60 + i * 4, seg.size) // stored size == size now
            pos += seg.size
        }
        // Clear the three "segment is compressed" bits, keep the "check hash" bits.
        bb.putInt(0x0C, bb.getInt(0x0C) and 0x7.inv())
        sha.reset()
        return DecompressedNso(out, checked, sha.digest(out).toHex())
    }
}

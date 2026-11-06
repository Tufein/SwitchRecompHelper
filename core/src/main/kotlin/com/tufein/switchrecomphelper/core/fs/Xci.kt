package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.io.slice
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Gamecard image (XCI): header, then a root HFS0 holding the secure/normal/update/logo partitions. */
class Xci(source: RandomAccessSource) {
    private val root: PartitionFs
    private val rootSource: RandomAccessSource

    init {
        // Some tools prepend a 0x1000-byte certificate block; offsets in the header are then relative to its end.
        val base = when {
            hasHead(source, 0x100) -> 0L
            hasHead(source, 0x1100) -> 0x1000L
            else -> throw SwitchFormatException("Not an XCI file (no HEAD magic). Is this a trimmed/converted dump or another format?")
        }
        val h = ByteBuffer.wrap(source.readBytes(base + 0x100, 0x100)).order(ByteOrder.LITTLE_ENDIAN)
        val rootOffset = h.getLong(0x30)
        val rootSize = h.getLong(0x38)
        rootSource = source.slice(base + rootOffset, rootSize)
        root = PartitionFs.parseHfs0(rootSource)
    }

    val partitionNames: List<String> get() = root.entries.map { it.name }

    fun partition(name: String): PartitionFs? {
        val e = root.find(name) ?: return null
        return PartitionFs.parseHfs0(root.open(e))
    }

    private fun hasHead(source: RandomAccessSource, pos: Long): Boolean {
        if (source.size < pos + 4) return false
        return String(source.readBytes(pos, 4), Charsets.US_ASCII) == "HEAD"
    }
}

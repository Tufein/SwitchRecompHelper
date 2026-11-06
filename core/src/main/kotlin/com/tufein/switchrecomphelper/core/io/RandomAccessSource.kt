package com.tufein.switchrecomphelper.core.io

import com.tufein.switchrecomphelper.core.SwitchFormatException
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional, read-only byte source. Dumps are many GB, so everything works on ranges, never whole files. */
interface RandomAccessSource {
    val size: Long

    /** Reads up to [length] bytes at [position]. Returns bytes read, or -1 at/after end. */
    fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int
}

class SliceSource(
    private val parent: RandomAccessSource,
    private val start: Long,
    override val size: Long,
) : RandomAccessSource {
    init {
        if (start < 0 || size < 0 || start > parent.size || size > parent.size - start) {
            throw SwitchFormatException("Data range 0x${start.toString(16)}+0x${size.toString(16)} lies outside the file (truncated or corrupt dump?)")
        }
    }

    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size) return -1
        val n = minOf(length.toLong(), size - position).toInt()
        return parent.read(start + position, dst, offset, n)
    }
}

/** Source backed by a [FileChannel] (a File on the JVM, or a ParcelFileDescriptor's stream on Android). */
class FileChannelSource(private val channel: FileChannel, closeable: Closeable? = null) : RandomAccessSource, Closeable {
    private val extra = closeable
    override val size: Long = channel.size()

    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(dst, offset, length), position)

    override fun close() {
        channel.close()
        extra?.close()
    }

    companion object {
        fun open(file: File): FileChannelSource {
            val raf = RandomAccessFile(file, "r")
            return FileChannelSource(raf.channel, raf)
        }
    }
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= data.size) return -1
        val n = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), dst, offset, n)
        return n
    }
}

fun RandomAccessSource.slice(start: Long, size: Long): RandomAccessSource = SliceSource(this, start, size)

fun RandomAccessSource.readFully(position: Long, dst: ByteArray, offset: Int = 0, length: Int = dst.size - offset) {
    var done = 0
    while (done < length) {
        val n = read(position + done, dst, offset + done, length - done)
        if (n <= 0) throw SwitchFormatException("Unexpected end of data at 0x${(position + done).toString(16)} (truncated dump?)")
        done += n
    }
}

fun RandomAccessSource.readBytes(position: Long, length: Int): ByteArray {
    if (length < 0) throw SwitchFormatException("Invalid length $length")
    return ByteArray(length).also { readFully(position, it) }
}

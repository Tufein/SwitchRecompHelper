package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.fs.Nca
import com.tufein.switchrecomphelper.core.fs.PartitionFs
import com.tufein.switchrecomphelper.core.fs.RomFs
import com.tufein.switchrecomphelper.core.fs.Xci
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readFully
import com.tufein.switchrecomphelper.core.io.slice
import java.io.OutputStream

/** Where extracted files go. Paths are relative, '/'-separated and already validated. */
interface OutputSink {
    fun create(relativePath: String): OutputStream
}

interface ProgressListener {
    fun status(message: String) {}
    fun progress(doneBytes: Long, totalBytes: Long) {}
    val cancelled: Boolean get() = false
}

class ExtractOptions(val exeFs: Boolean = true, val romFs: Boolean = true)

class TitleResult(val programId: Long, val folder: String, val exeFsFiles: Int, val romFsFiles: Int)

class ExtractionResult(val titles: List<TitleResult>, val totalFiles: Int, val totalBytes: Long)

class SwitchExtractor(private val keys: KeySet) {

    private class Planned(val path: String, val data: RandomAccessSource)

    /** Extracts ExeFS and RomFS of every Program NCA in the XCI's secure partition. */
    fun extract(
        xciSource: RandomAccessSource,
        sink: OutputSink,
        options: ExtractOptions = ExtractOptions(),
        listener: ProgressListener = object : ProgressListener {},
    ): ExtractionResult {
        if (!options.exeFs && !options.romFs) throw SwitchFormatException("Select ExeFS and/or RomFS")
        if (!keys.hasHeaderKey) throw SwitchFormatException("prod.keys has no usable header_key")

        listener.status("Reading XCI")
        val xci = Xci(xciSource)
        val secure = xci.partition("secure")
            ?: throw SwitchFormatException("No 'secure' partition found (partitions: ${xci.partitionNames.joinToString()})")

        val planned = ArrayList<Planned>()
        val titles = ArrayList<TitleResult>()
        val usedFolders = HashSet<String>()

        for (entry in secure.entries) {
            if (!entry.name.endsWith(".nca")) continue
            if (listener.cancelled) throw ExtractionCancelledException()
            val nca = Nca(secure.open(entry), keys)
            if (nca.contentType != Nca.TYPE_PROGRAM) continue

            listener.status("Program NCA ${entry.name}")
            var folder = "%016x".format(nca.programId)
            if (!usedFolders.add(folder)) {
                folder += "_" + entry.name.removeSuffix(".nca")
                usedFolders.add(folder)
            }
            var exeCount = 0
            var romCount = 0
            for (section in nca.sections) {
                if (section.isPfs0 && options.exeFs && exeCount == 0) {
                    val pfs = PartitionFs.parsePfs0(nca.openFilesystem(section))
                    // The logo section is also a PFS0; ExeFS is the one with the executable.
                    if (pfs.find("main") == null && pfs.find("main.npdm") == null) continue
                    for (f in pfs.entries) {
                        planned.add(Planned("$folder/exefs/${safeName(f.name)}", pfs.open(f)))
                        exeCount++
                    }
                } else if (section.isRomFs && options.romFs && romCount == 0) {
                    val image = nca.openFilesystem(section)
                    for (f in RomFs.list(image)) {
                        planned.add(Planned("$folder/romfs/${safePath(f.path)}", image.slice(f.offset, f.size)))
                        romCount++
                    }
                }
            }
            titles.add(TitleResult(nca.programId, folder, exeCount, romCount))
        }
        if (titles.isEmpty()) throw SwitchFormatException("No Program NCA found in the secure partition")

        val total = planned.sumOf { it.data.size }
        var done = 0L
        listener.progress(0, total)
        val buffer = ByteArray(1 shl 20)
        for ((i, p) in planned.withIndex()) {
            listener.status("(${i + 1}/${planned.size}) ${p.path}")
            sink.create(p.path).use { out ->
                var pos = 0L
                while (pos < p.data.size) {
                    if (listener.cancelled) throw ExtractionCancelledException()
                    val n = minOf(buffer.size.toLong(), p.data.size - pos).toInt()
                    p.data.readFully(pos, buffer, 0, n)
                    out.write(buffer, 0, n)
                    pos += n
                    done += n
                    listener.progress(done, total)
                }
            }
        }
        return ExtractionResult(titles, planned.size, total)
    }

    companion object {
        /** Names come from an untrusted dump: refuse anything that could escape the output directory. */
        fun safeName(name: String): String {
            if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) {
                throw SwitchFormatException("Refusing unsafe file name '${name.filter { it >= ' ' }}' in dump")
            }
            return name
        }

        fun safePath(path: String): String = path.split('/').joinToString("/") { safeName(it) }
    }
}

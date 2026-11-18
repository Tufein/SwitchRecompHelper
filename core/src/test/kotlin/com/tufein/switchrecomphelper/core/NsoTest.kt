package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.fs.Lz4
import com.tufein.switchrecomphelper.core.fs.Nso
import com.tufein.switchrecomphelper.core.io.ByteArraySource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Minimal greedy LZ4 block compressor, only for building test fixtures. */
object TestLz4 {
    fun compress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val table = HashMap<Int, Int>()
        var anchor = 0
        var i = 0
        val end = input.size - 5 // the last 5 bytes are always literals
        fun key(p: Int) = ByteBuffer.wrap(input, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun length(v: Int) { var r = v; while (r >= 255) { out.write(255); r -= 255 }; out.write(r) }
        while (i + 4 <= end) {
            val k = key(i)
            val cand = table.put(k, i)
            if (cand != null && i - cand <= 0xFFFF && key(cand) == k) {
                var len = 4
                while (i + len < end && input[cand + len] == input[i + len]) len++
                val lit = i - anchor
                out.write((minOf(lit, 15) shl 4) or minOf(len - 4, 15))
                if (lit >= 15) length(lit - 15)
                out.write(input, anchor, lit)
                out.write((i - cand) and 0xFF); out.write((i - cand) ushr 8)
                if (len - 4 >= 15) length(len - 4 - 15)
                i += len
                anchor = i
            } else {
                i++
            }
        }
        val lit = input.size - anchor
        out.write(minOf(lit, 15) shl 4)
        if (lit >= 15) length(lit - 15)
        out.write(input, anchor, lit)
        return out.toByteArray()
    }
}

/** Builds an NSO the way the Switch SDK lays it out: header, module name, then the (compressed) segments. */
object TestNso {
    val buildId = ByteArray(0x20) { if (it < 0x14) (0x10 + it).toByte() else 0 }

    fun build(text: ByteArray, ro: ByteArray, data: ByteArray, compress: Boolean = true, hashes: Boolean = true, moduleName: String = "main"): ByteArray {
        val segs = listOf(text, ro, data)
        val stored = segs.map { if (compress) TestLz4.compress(it) else it }
        val name = moduleName.toByteArray()
        val h = ByteBuffer.allocate(Nso.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.put("NSO0".toByteArray())
        h.putInt(0x0C, (if (compress) 0x7 else 0) or (if (hashes) 0x38 else 0))
        var fileOff = Nso.HEADER_SIZE + name.size
        var memOff = 0
        for (i in 0 until 3) {
            h.putInt(0x10 + i * 0x10, fileOff)
            h.putInt(0x14 + i * 0x10, memOff)
            h.putInt(0x18 + i * 0x10, segs[i].size)
            h.putInt(0x60 + i * 4, stored[i].size)
            h.position(0xA0 + i * 0x20); h.put(MessageDigest.getInstance("SHA-256").digest(segs[i]))
            fileOff += stored[i].size
            memOff += (segs[i].size + 0xFFF) and 0xFFF.inv()
        }
        h.putInt(0x1C, Nso.HEADER_SIZE); h.putInt(0x2C, name.size)
        h.position(0x40); h.put(buildId)
        val out = ByteArrayOutputStream()
        out.write(h.array()); out.write(name)
        stored.forEach { out.write(it) }
        return out.toByteArray()
    }
}

class NsoTest {
    private val rnd = java.util.Random(21)

    /** Compressible "code": repeated instruction-like patterns with some noise. */
    private fun code(size: Int): ByteArray {
        val pattern = ByteArray(64).also(rnd::nextBytes)
        return ByteArray(size) { i -> if (i % 97 == 0) rnd.nextInt().toByte() else pattern[i % pattern.size] }
    }

    private val text = code(40_000)
    private val ro = ByteArray(3000) { 'A'.code.toByte() } + "hello".toByteArray() + ByteArray(500).also(rnd::nextBytes)
    private val data = ByteArray(777).also(rnd::nextBytes)

    @Test
    fun lz4RoundTripsLiteralsMatchesAndOverlaps() {
        val cases = listOf(
            ByteArray(0),
            "abc".toByteArray(),
            ByteArray(10_000) { 7 }, // long overlapping run (distance 1)
            ByteArray(300).also(rnd::nextBytes), // literals only, > 15 and > 255
            code(70_000) + ByteArray(1000).also(rnd::nextBytes) + code(5000), // long matches
        )
        for (c in cases) {
            val comp = TestLz4.compress(c)
            assertContentEquals(c, Lz4.decompressBlock(comp, 0, comp.size, c.size), "size ${c.size}")
        }
    }

    @Test
    fun lz4RejectsCorruptInput() {
        val c = code(5000)
        val comp = TestLz4.compress(c)
        assertFailsWith<SwitchFormatException> { Lz4.decompressBlock(comp, 0, comp.size - 3, c.size) } // truncated
        assertFailsWith<SwitchFormatException> { Lz4.decompressBlock(comp, 0, comp.size, c.size - 1) } // too long for output
        assertFailsWith<SwitchFormatException> { Lz4.decompressBlock(comp, 0, comp.size, c.size + 1) } // too short
        // A match that points before the start of the output.
        val bad = byteArrayOf(0x10, 'x'.code.toByte(), 0x05, 0x00, 0x00)
        assertFailsWith<SwitchFormatException> { Lz4.decompressBlock(bad, 0, bad.size, 100) }
    }

    @Test
    fun nsoIsDecompressedAndVerified() {
        val file = TestNso.build(text, ro, data)
        val src = ByteArraySource(file)
        assertTrue(Nso.isNso(src))
        val header = Nso.parseHeader(src)
        assertEquals(TestNso.buildId.toHex().uppercase(), header.buildIdHex)
        val out = Nso.decompress(src, header)
        assertEquals(listOf(true, true, true), out.segmentHashChecked)
        assertEquals(header.uncompressedFileSize, out.bytes.size.toLong())

        // The result is itself a valid, uncompressed NSO with the same segments.
        val again = Nso.parseHeader(ByteArraySource(out.bytes))
        assertTrue(again.segments.none { it.compressed })
        assertTrue(again.segments.all { it.checkHash })
        for ((seg, expected) in again.segments.zip(listOf(text, ro, data))) {
            assertContentEquals(expected, out.bytes.copyOfRange(seg.fileOffset, seg.fileOffset + seg.size), seg.name)
        }
        assertEquals(header.segments.map { it.memoryOffset }, again.segments.map { it.memoryOffset })
        assertEquals("main", String(out.bytes, again.moduleNameOffset, again.moduleNameSize))
        // Decompressing an already uncompressed NSO is the identity.
        assertContentEquals(out.bytes, Nso.decompress(ByteArraySource(out.bytes), again).bytes)
    }

    @Test
    fun nsoWithoutHashFlagsIsNotChecked() {
        val file = TestNso.build(text, ro, data, hashes = false)
        val out = Nso.decompress(ByteArraySource(file), Nso.parseHeader(ByteArraySource(file)))
        assertEquals(listOf(false, false, false), out.segmentHashChecked)
    }

    @Test
    fun nsoHashMismatchFails() {
        val file = TestNso.build(text, ro, data)
        file[0xC0] = (file[0xC0] + 1).toByte() // .rodata hash
        val e = assertFailsWith<SwitchFormatException> { Nso.decompress(ByteArraySource(file), Nso.parseHeader(ByteArraySource(file))) }
        assertTrue(".rodata" in e.message!!, e.message)
    }

    @Test
    fun nsoWithSegmentOutsideFileIsRejected() {
        val file = TestNso.build(text, ro, data)
        assertFailsWith<SwitchFormatException> { Nso.parseHeader(ByteArraySource(file.copyOf(file.size - 10))) }
    }

    private val img = TestImage(31)
    private val keys = KeySet.parse(img.keyText)
    private fun id() = "%016x".format(img.programId)
    private val mainNso = TestNso.build(text, ro, data)
    private val exefs = linkedMapOf("main" to mainNso, "main.npdm" to ByteArray(100).also(rnd::nextBytes))
    private val romfs = mapOf("a.txt" to "hi".toByteArray())

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private fun checkOutput(sink: MemorySink) {
        // ExeFS stays byte-identical; the uncompressed copy goes next to it.
        assertContentEquals(mainNso, sink.bytes("${id()}/exefs/main"))
        val unc = sink.bytes("${id()}/exefs_uncompressed/main")
        assertContentEquals(Nso.decompress(ByteArraySource(mainNso), Nso.parseHeader(ByteArraySource(mainNso))).bytes, unc)
        assertNull(sink.files["${id()}/exefs_uncompressed/main.npdm"])

        val toml = String(sink.bytes("${id()}/${RecompInfo.FILE_NAME}"))
        assertTrue("program_id = \"${id()}\"" in toml, toml)
        assertTrue("sha256 = \"${sha(mainNso)}\"" in toml, toml)
        assertTrue("sha256 = \"${sha(exefs["main.npdm"]!!)}\"" in toml, toml)
        assertTrue("build_id = \"${TestNso.buildId.toHex().uppercase()}\"" in toml, toml)
        assertTrue("uncompressed_sha256 = \"${sha(unc)}\"" in toml, toml)
        assertTrue("segment_hashes_checked = [true, true, true]" in toml, toml)
    }

    @Test
    fun xciExtractionWritesUncompressedNsoAndRecompInfo() {
        val program = img.nca(0, listOf(Triple(1, img.pfs0(exefs), 3), Triple(0, img.romfs(romfs), 3)))
        val sink = MemorySink()
        val result = SwitchExtractor(keys).extract(ByteArraySource(img.xci(mapOf("p.nca" to program))), sink)
        checkOutput(sink)
        assertEquals(1, result.titles.single().uncompressedNsoFiles)
        assertEquals(sink.files.size, result.totalFiles)
    }

    @Test
    fun nszStreamExtractionWritesUncompressedNso() {
        // Single-stream NCZ is forward-only: the second read of the NSO restarts decompression and must still work.
        val built = img.programBuilt(exefsIn = exefs, romfsIn = romfs)
        val nsz = img.nsp(linkedMapOf("p.ncz" to img.ncz(built)))
        val sink = MemorySink()
        SwitchExtractor(keys).extract(ByteArraySource(nsz), sink)
        checkOutput(sink)
    }

    @Test
    fun uncompressedNsoAndRecompInfoCanBeTurnedOff() {
        val program = img.nca(0, listOf(Triple(1, img.pfs0(exefs), 3), Triple(0, img.romfs(romfs), 3)))
        val sink = MemorySink()
        SwitchExtractor(keys).extract(
            ByteArraySource(img.xci(mapOf("p.nca" to program))), sink,
            ExtractOptions(uncompressedNso = false, recompInfo = false),
        )
        assertEquals(exefs.size + romfs.size, sink.files.size)
        assertFalse(sink.files.keys.any { "uncompressed" in it || it.endsWith(RecompInfo.FILE_NAME) })
    }

    @Test
    fun corruptNsoFailsTheExtraction() {
        val bad = mainNso.copyOf().also { it[0xA0] = (it[0xA0] + 1).toByte() } // .text hash
        val program = img.nca(0, listOf(Triple(1, img.pfs0(mapOf("main" to bad)), 3)))
        val e = assertFailsWith<SwitchFormatException> {
            SwitchExtractor(keys).extract(ByteArraySource(img.xci(mapOf("p.nca" to program))), MemorySink())
        }
        assertTrue(".text" in e.message!!, e.message)
    }

    @Test
    fun tomlEscapesNamesFromTheDump() {
        val toml = RecompInfo.toml(1, "we\"ird\\\n.nca", emptyList())
        assertTrue("source = \"we\\\"ird\\\\\\u000a.nca\"" in toml, toml)
    }
}

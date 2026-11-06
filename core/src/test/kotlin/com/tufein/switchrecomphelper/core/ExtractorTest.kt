package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.crypto.AesUtil
import com.tufein.switchrecomphelper.core.io.ByteArraySource
import com.tufein.switchrecomphelper.core.io.readFully
import com.tufein.switchrecomphelper.core.io.slice
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemorySink : OutputSink {
    val files = linkedMapOf<String, ByteArrayOutputStream>()
    override fun create(relativePath: String): OutputStream = ByteArrayOutputStream().also { files[relativePath] = it }
    fun bytes(path: String) = files[path]?.toByteArray() ?: error("missing $path; have ${files.keys}")
}

class ExtractorTest {
    private val img = TestImage()
    private val keys = KeySet.parse(img.keyText)

    private fun hexId() = "%016x".format(img.programId)

    @Test
    fun extractsExeFsAndRomFs() {
        val sink = MemorySink()
        val result = SwitchExtractor(keys).extract(ByteArraySource(img.standardXci()), sink)
        for ((n, d) in img.exefsFiles) assertContentEquals(d, sink.bytes("${hexId()}/exefs/$n"), n)
        for ((n, d) in img.romfsFiles) assertContentEquals(d, sink.bytes("${hexId()}/romfs/$n"), n)
        assertEquals(img.exefsFiles.size + img.romfsFiles.size, sink.files.size) // logo section must not be treated as ExeFS
        assertEquals(1, result.titles.size)
        assertEquals(sink.files.size, result.totalFiles)
    }

    @Test
    fun handlesCertificatePrefix() {
        val sink = MemorySink()
        SwitchExtractor(keys).extract(ByteArraySource(img.standardXci(prefix = 0x1000)), sink)
        assertContentEquals(img.exefsFiles["main"], sink.bytes("${hexId()}/exefs/main"))
    }

    @Test
    fun optionsSelectParts() {
        val sink = MemorySink()
        SwitchExtractor(keys).extract(ByteArraySource(img.standardXci()), sink, ExtractOptions(exeFs = false, romFs = true))
        assertTrue(sink.files.keys.all { "/romfs/" in it })
        val sink2 = MemorySink()
        SwitchExtractor(keys).extract(ByteArraySource(img.standardXci()), sink2, ExtractOptions(exeFs = true, romFs = false))
        assertTrue(sink2.files.keys.all { "/exefs/" in it })
    }

    @Test
    fun wrongHeaderKeyGivesClearError() {
        val bad = KeySet.parse(img.keyText.replace("header_key = ", "header_key = 00"))
        // 33 bytes -> unusable
        assertFailsWith<SwitchFormatException> { SwitchExtractor(bad).extract(ByteArraySource(img.standardXci()), MemorySink()) }
        val other = KeySet.parse("header_key = " + "11".repeat(32) + "\nmaster_key_00 = " + "22".repeat(16))
        val e = assertFailsWith<SwitchFormatException> { SwitchExtractor(other).extract(ByteArraySource(img.standardXci()), MemorySink()) }
        assertTrue("header_key" in e.message!!, e.message)
    }

    @Test
    fun missingMasterKeyIsReported() {
        val k = KeySet.parse("header_key = ${img.headerKey.toHex()}")
        val e = assertFailsWith<SwitchFormatException> { SwitchExtractor(k).extract(ByteArraySource(img.standardXci()), MemorySink()) }
        assertTrue("master_key_00" in e.message!!, e.message)
    }

    @Test
    fun truncatedDumpFailsCleanly() {
        val xci = img.standardXci()
        assertFailsWith<SwitchFormatException> {
            SwitchExtractor(keys).extract(ByteArraySource(xci.copyOf(xci.size - 20000)), MemorySink())
        }
    }

    @Test
    fun notAnXci() {
        assertFailsWith<SwitchFormatException> { SwitchExtractor(keys).extract(ByteArraySource(ByteArray(0x20000)), MemorySink()) }
    }

    @Test
    fun cancellationStopsExtraction() {
        val l = object : ProgressListener {
            override val cancelled = true
        }
        assertFailsWith<ExtractionCancelledException> {
            SwitchExtractor(keys).extract(ByteArraySource(img.standardXci()), MemorySink(), listener = l)
        }
    }

    @Test
    fun unsafeNamesAreRejected() {
        for (bad in listOf("../x", "a/../b", "/abs", "a//b", "a\\b", "")) {
            assertFailsWith<SwitchFormatException>(bad) { SwitchExtractor.safePath(bad) }
        }
        assertEquals("a/b.txt", SwitchExtractor.safePath("a/b.txt"))
    }

    @Test
    fun maliciousRomFsNameIsRejected() {
        val evil = TestImage(7)
        val program = evil.nca(0, listOf(Triple(0, evil.romfs(mapOf("ok" to byteArrayOf(1))).let { patchName(it, "ok", "..") }, 3)))
        val xci = evil.xci(mapOf("a.nca" to program))
        assertFailsWith<SwitchFormatException> { SwitchExtractor(KeySet.parse(evil.keyText)).extract(ByteArraySource(xci), MemorySink()) }
    }

    private fun patchName(romfs: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val idx = String(romfs, Charsets.ISO_8859_1).indexOf(from, 0x50 + 4 + 0x18)
        return romfs.copyOf().also { to.toByteArray().copyInto(it, idx) }
    }

    @Test
    fun keyParsing() {
        val k = KeySet.parse("# c\nFoo = zz\nbar=00112233445566778899aabbccddeeff\n  baz  =  0a \nnoequals\n")
        assertEquals(2, k.size)
    }

    @Test
    fun xtsMatchesIeee1619Vector1() {
        // IEEE 1619-2007 vector 1: zero keys, sector 0, 32 zero bytes of plaintext (one 32-byte "sector").
        val ct = "917cf69ebd68b2ec9b9fe9a3eadda692cd43d2f59598ed858c02c2652fbf922e".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pt = AesUtil.xtsDecrypt(ByteArray(32), ct, 0, sectorSize = 32)
        assertContentEquals(ByteArray(32), pt)
    }

    @Test
    fun xtsRoundTripAcrossSectors() {
        val key = ByteArray(32) { it.toByte() }
        val data = ByteArray(0x600) { (it * 7).toByte() }
        assertContentEquals(data, AesUtil.xtsDecrypt(key, AesUtil.xtsEncrypt(key, data, 3), 3))
    }

    @Test
    fun largeFilesSpanSeveralPipelineBuffers() {
        val big = TestImage(3)
        val data = ByteArray(9 * 1024 * 1024 + 123).also { java.util.Random(5).nextBytes(it) }
        val program = big.nca(0, listOf(Triple(0, big.romfs(mapOf("big.bin" to data, "small" to byteArrayOf(9))), 3)))
        val sink = MemorySink()
        SwitchExtractor(KeySet.parse(big.keyText)).extract(ByteArraySource(big.xci(mapOf("p.nca" to program))), sink)
        assertContentEquals(data, sink.bytes("${"%016x".format(big.programId)}/romfs/big.bin"))
    }

    @Test
    fun writeFailureIsReportedAndDoesNotHang() {
        val failing = object : OutputSink {
            override fun create(relativePath: String): OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw java.io.IOException("disk full")
                override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("disk full")
            }
        }
        val e = assertFailsWith<java.io.IOException> {
            SwitchExtractor(keys).extract(ByteArraySource(img.standardXci()), failing)
        }
        assertEquals("disk full", e.message)
    }

    @Test
    fun createFailureIsUnwrapped() {
        val failing = object : OutputSink {
            override fun create(relativePath: String): OutputStream = throw java.io.IOException("cannot create")
        }
        val e = assertFailsWith<java.io.IOException> {
            SwitchExtractor(keys).extract(ByteArraySource(img.standardXci()), failing)
        }
        assertEquals("cannot create", e.message)
    }

    @Test
    fun ctrReadsAtArbitraryOffsetsMatchSequentialRead() {
        val big = TestImage(4)
        val payload = ByteArray(100_000).also { java.util.Random(6).nextBytes(it) }
        val nca = com.tufein.switchrecomphelper.core.fs.Nca(
            ByteArraySource(big.nca(0, listOf(Triple(0, big.romfs(mapOf("f" to payload)), 3)))),
            KeySet.parse(big.keyText),
        )
        val section = nca.openFilesystem(nca.sections[0])
        val file = com.tufein.switchrecomphelper.core.fs.RomFs.list(section).single()
        val rnd = java.util.Random(8)
        repeat(200) {
            val off = rnd.nextInt(payload.size - 1)
            val len = 1 + rnd.nextInt(minOf(5000, payload.size - off))
            val buf = ByteArray(len)
            section.slice(file.offset, file.size).readFully(off.toLong(), buf)
            assertContentEquals(payload.copyOfRange(off, off + len), buf, "off=$off len=$len")
        }
    }
}

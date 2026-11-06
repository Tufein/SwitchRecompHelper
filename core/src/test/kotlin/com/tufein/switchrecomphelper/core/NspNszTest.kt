package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NspNszTest {
    private val img = TestImage(11)
    private val keys = KeySet.parse(img.keyText)
    private fun hexId() = "%016x".format(img.programId)

    private fun assertExtracted(sink: MemorySink, exefs: Map<String, ByteArray> = img.exefsFiles, romfs: Map<String, ByteArray> = img.romfsFiles) {
        for ((n, d) in exefs) assertContentEquals(d, sink.bytes("${hexId()}/exefs/$n"), n)
        for ((n, d) in romfs) assertContentEquals(d, sink.bytes("${hexId()}/romfs/$n"), n)
        assertEquals(exefs.size + romfs.size, sink.files.size)
    }

    private fun extract(file: ByteArray, k: KeySet = keys, sink: MemorySink = MemorySink(), l: ProgressListener = object : ProgressListener {}): MemorySink {
        SwitchExtractor(k).extract(ByteArraySource(file), sink, listener = l)
        return sink
    }

    private fun metaNca() = img.build(1, emptyList(), 0x0100AABBCCDD0800L, firstSectionAt = 0x4000).nca

    @Test
    fun nspWithTicketUsesTitleKeyCrypto() {
        val nsp = img.nsp(
            linkedMapOf(
                "a.nca" to img.programBuilt(useTitleKey = true).nca,
                "b.cnmt.nca" to metaNca(),
                "a.tik" to img.ticket(),
                "a.cert" to ByteArray(0x700),
            ),
        )
        assertExtracted(extract(nsp))
    }

    @Test
    fun nspWithoutTicketReportsMissingTitleKey() {
        val nsp = img.nsp(linkedMapOf("a.nca" to img.programBuilt(useTitleKey = true).nca))
        val e = assertFailsWith<SwitchFormatException> { extract(nsp) }
        assertTrue("title key" in e.message!!, e.message)
    }

    @Test
    fun titleKeysFromKeyFileWork() {
        val tik = img.ticket()
        val enc = tik.copyOfRange(0x140 + 0x40, 0x140 + 0x50).toHex()
        val k = KeySet.parse(img.keyText + "\n${img.rightsId.toHex()} = $enc\n")
        val nsp = img.nsp(linkedMapOf("a.nca" to img.programBuilt(useTitleKey = true).nca))
        assertExtracted(extract(nsp, k))
    }

    @Test
    fun nspWithStandardCryptoNeedsNoTicket() {
        val nsp = img.nsp(linkedMapOf("a.nca" to img.programBuilt().nca))
        assertExtracted(extract(nsp))
    }

    @Test
    fun personalizedTicketIsIgnoredWithWarning() {
        val bad = img.ticket().also { it[0x140 + 0x141] = 1 }
        val msgs = ArrayList<String>()
        val l = object : ProgressListener {
            override fun status(message: String) { msgs.add(message) }
        }
        val nsp = img.nsp(linkedMapOf("a.nca" to img.programBuilt().nca, "a.tik" to bad))
        assertExtracted(extract(nsp, l = l))
        assertTrue(msgs.any { "Personalized" in it }, msgs.toString())
    }

    @Test
    fun nszStreamCompressedExtractsExeFsAndRomFs() {
        // compressible data so zstd really compresses; several MiB so the stream is read across many buffers
        val romfs = linkedMapOf(
            "a.txt" to "hello".toByteArray(),
            "big/pattern.bin" to ByteArray(6 * 1024 * 1024 + 77) { (it / 64 % 7).toByte() },
            "big/random.bin" to ByteArray(300_000).also { java.util.Random(2).nextBytes(it) },
            "z/last" to ByteArray(1000) { it.toByte() },
        )
        val built = img.programBuilt(useTitleKey = true, romfsIn = romfs)
        val nsz = img.nsp(linkedMapOf("a.ncz" to img.ncz(built), "a.tik" to img.ticket(), "m.cnmt.nca" to metaNca()))
        assertExtracted(extract(nsz), romfs = romfs)
    }

    @Test
    fun nszBlockCompressedExtractsExeFsAndRomFs() {
        val romfs = linkedMapOf(
            "pattern.bin" to ByteArray(2 * 1024 * 1024 + 5) { (it / 100 % 5).toByte() },
            "random.bin" to ByteArray(200_000).also { java.util.Random(3).nextBytes(it) },
            "tiny" to byteArrayOf(1, 2, 3),
        )
        val built = img.programBuilt(romfsIn = romfs)
        val nsz = img.nsp(linkedMapOf("a.ncz" to img.ncz(built, blockExponent = 16)))
        assertExtracted(extract(nsz), romfs = romfs)
    }

    @Test
    fun xczSecurePartitionWithNczWorks() {
        val built = img.programBuilt()
        val xcz = img.xci(linkedMapOf("a.ncz" to img.ncz(built)))
        assertExtracted(extract(xcz))
    }

    @Test
    fun corruptNczBodyFailsCleanly() {
        val built = img.programBuilt()
        val ncz = img.ncz(built, blockExponent = 16)
        val bad = ncz.copyOf(ncz.size - 100)
        assertFailsWith<SwitchFormatException> { extract(img.nsp(linkedMapOf("a.ncz" to bad))) }
        val streamBad = img.ncz(built).let { it.copyOf(it.size - 50) }
        assertFailsWith<SwitchFormatException> { extract(img.nsp(linkedMapOf("a.ncz" to streamBad))) }
    }

    @Test
    fun patchSectionIsSkippedButExeFsStillExtracted() {
        val built = img.build(
            0,
            listOf(Triple(1, img.pfs0(img.exefsFiles), 3), Triple(0, img.romfs(img.romfsFiles), 4)),
            firstSectionAt = 0x4000,
        )
        val msgs = ArrayList<String>()
        val l = object : ProgressListener {
            override fun status(message: String) { msgs.add(message) }
        }
        val sink = extract(img.nsp(linkedMapOf("a.nca" to built.nca)), l = l)
        assertExtracted(sink, romfs = emptyMap())
        assertTrue(msgs.any { "patch" in it }, msgs.toString())
    }

    @Test
    fun updateOnlyPackageGivesClearError() {
        val nsp = img.nsp(linkedMapOf("m.cnmt.nca" to metaNca()))
        val e = assertFailsWith<SwitchFormatException> { extract(nsp) }
        assertTrue("Program" in e.message!!, e.message)
    }
}

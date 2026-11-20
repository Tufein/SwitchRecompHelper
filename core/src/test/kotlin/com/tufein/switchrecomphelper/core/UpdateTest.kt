package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.fs.BucketTree
import com.tufein.switchrecomphelper.core.fs.Nca
import com.tufein.switchrecomphelper.core.fs.RomFs
import com.tufein.switchrecomphelper.core.io.ByteArraySource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.io.readFully
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Update (BKTR patch) support: a base game and an update built from it, the update applied to the base RomFS.
 * The fixtures build both the base and the patch, so these tests check the arithmetic of the bucket trees, the
 * subsection layer and the relocation, not agreement with a real console.
 */
class UpdateTest {
    private val img = TestImage(41)
    private val keys = KeySet.parse(img.keyText)
    private val baseId = img.programId
    private val updateId = baseId or 0x800L

    private val baseFiles = linkedMapOf(
        "a.txt" to "hello".toByteArray(),
        "sub/b.bin" to ByteArray(70000) { (it * 31 + 7).toByte() },
        "sub/deep/c.dat" to ByteArray(1) { 9 },
    )
    private val newFiles = linkedMapOf(
        "a.txt" to "HELLO".toByteArray(),
        "sub/b.bin" to ByteArray(70000) { (it * 31 + 7).toByte() }.also { it[35000] = 0x5A },
        "sub/deep/c.dat" to ByteArray(1) { 9 },
    )
    private val exefs = mapOf("main" to ByteArray(3000) { (it * 3).toByte() }, "main.npdm" to ByteArray(200) { 1 })
    private val newExefs = mapOf("main" to ByteArray(3100) { (it * 5).toByte() }, "main.npdm" to ByteArray(200) { 2 })
    private val logo = mapOf("NintendoLogo.png" to ByteArray(10))

    private val baseRomfsSec = img.ivfcSec(img.romfs(baseFiles))
    private val baseNca = img.assemble(
        0,
        listOf(img.pfs0Sec(img.pfs0(exefs)), baseRomfsSec, img.pfs0Sec(img.pfs0(logo))),
        baseId,
    ).nca
    private val patchSec = img.patchSec(baseRomfsSec.plain, img.romfs(newFiles))
    private val updateNca = img.assemble(0, listOf(img.pfs0Sec(img.pfs0(newExefs)), patchSec), updateId).nca

    private fun files(source: com.tufein.switchrecomphelper.core.io.RandomAccessSource): Map<String, ByteArray> =
        RomFs.list(source).associate { f -> f.path to source.readBytes(f.offset, f.size.toInt()) }

    @Test
    fun patchedRomFsIsTheNewContent() {
        val base = Nca(ByteArraySource(baseNca), keys)
        val upd = Nca(ByteArraySource(updateNca), keys)
        val baseSection = base.sections.first { it.isRomFs }
        val updSection = upd.sections.first { it.isRomFs }
        assertTrue(updSection.isPatch)
        val image = upd.openPatchedFilesystem(updSection, base.openSection(baseSection))
        val got = files(image)
        assertEquals(newFiles.keys, got.keys)
        for ((name, data) in newFiles) assertContentEquals(data, got[name], name)
    }

    @Test
    fun patchUsesBothTheBaseAndThePatchData() {
        // Chunks equal to the base at the same offset come from the base; the rest is stored in the patch.
        val newSection = img.ivfcSec(img.romfs(newFiles)).plain
        val same = (0 until newSection.size / 0x200).count { k ->
            baseRomfsSec.plain.copyOfRange(k * 0x200, (k + 1) * 0x200).contentEquals(newSection.copyOfRange(k * 0x200, (k + 1) * 0x200))
        }
        assertTrue(same > 0 && same < newSection.size / 0x200, "expected a mix, got $same shared chunks")
        val upd = Nca(ByteArraySource(updateNca), keys)
        val base = Nca(ByteArraySource(baseNca), keys)
        // The whole patched section, before the RomFS layer is taken off it.
        val section = upd.openPatchedSection(upd.sections.first { it.isRomFs }, base.openSection(base.sections.first { it.isRomFs }))
        assertEquals(newSection.size.toLong(), section.size)
        val all = ByteArray(newSection.size)
        section.readFully(0, all)
        assertContentEquals(newSection, all)
    }

    @Test
    fun patchedSectionReadsAreCorrectAtAnyOffset() {
        // Reads that do not start on a 16-byte boundary (RomFS metadata is read like this) must decrypt correctly.
        val newSection = img.ivfcSec(img.romfs(newFiles)).plain
        val upd = Nca(ByteArraySource(updateNca), keys)
        val base = Nca(ByteArraySource(baseNca), keys)
        val section = upd.openPatchedSection(upd.sections.first { it.isRomFs }, base.openSection(base.sections.first { it.isRomFs }))
        val rnd = Random(5)
        repeat(300) {
            val at = rnd.nextInt(newSection.size - 1)
            val len = minOf(1 + rnd.nextInt(700), newSection.size - at)
            val got = ByteArray(len)
            section.readFully(at.toLong(), got)
            assertContentEquals(newSection.copyOfRange(at, at + len), got, "read at $at, length $len")
        }
    }

    @Test
    fun relocationTableWithSeveralEntrySetsWorks() {
        // ~1200 chunks of 512 bytes: more than one 16 KiB entry set of relocation entries.
        val big = ByteArray(500_000) { (it * 13 + it / 251).toByte() }
        val oldRomfs = img.romfs(linkedMapOf("big.bin" to big))
        val newBig = big.copyOf().also { it[300_000] = (it[300_000] + 1).toByte() }
        val newRomfs = img.romfs(linkedMapOf("big.bin" to newBig))
        val oldSec = img.ivfcSec(oldRomfs)
        val base = img.assemble(0, listOf(img.pfs0Sec(img.pfs0(exefs)), oldSec), baseId).nca
        val upd = img.assemble(0, listOf(img.patchSec(oldSec.plain, newRomfs)), updateId).nca
        val baseNcaObj = Nca(ByteArraySource(base), keys)
        val updNcaObj = Nca(ByteArraySource(upd), keys)
        val image = updNcaObj.openPatchedFilesystem(
            updNcaObj.sections.first { it.isRomFs },
            baseNcaObj.openSection(baseNcaObj.sections.first { it.isRomFs }),
        )
        val got = files(image)
        assertEquals(setOf("big.bin"), got.keys)
        assertContentEquals(newBig, got["big.bin"])
    }

    @Test
    fun updateIsAppliedFromTheUpdatePartition() {
        val input = img.xci(mapOf("base.nca" to baseNca), updates = mapOf("upd.nca" to updateNca))
        val sink = MemorySink()
        val result = SwitchExtractor(keys).extract(ByteArraySource(input), sink)
        val title = result.titles.single()
        assertTrue(title.updateApplied)
        val folder = "%016x".format(baseId)
        assertEquals(folder, title.folder)
        for ((n, d) in newExefs) assertContentEquals(d, sink.bytes("$folder/exefs/$n"), n)
        for ((n, d) in newFiles) assertContentEquals(d, sink.bytes("$folder/romfs/$n"), n)
        // Each file is written once: the update's ExeFS replaces the base game's, the RomFS is not doubled.
        assertEquals(newExefs.size, sink.files.keys.count { it.startsWith("$folder/exefs/") })
        assertEquals(newFiles.size, sink.files.keys.count { it.startsWith("$folder/romfs/") })
    }

    @Test
    fun updateWithoutBaseGameIsReported() {
        val input = img.xci(emptyMap(), updates = mapOf("upd.nca" to updateNca))
        val listener = object : ProgressListener {
            val messages = ArrayList<String>()
            override fun status(message: String) { messages.add(message) }
        }
        val e = assertFailsWith<SwitchFormatException> {
            SwitchExtractor(keys).extract(ByteArraySource(input), MemorySink(), listener = listener)
        }
        assertTrue(e.message!!.contains("base game"), e.message)
        assertTrue(listener.messages.any { it.contains("base game is not in this file") })
    }

    @Test
    fun corruptPatchDataFailsTheHashCheck() {
        // Flip one bit of the patch data that holds the first byte of a.txt (changed, so it is stored in the patch).
        val newRomfs = img.romfs(newFiles)
        val newSection = img.ivfcSec(newRomfs).plain
        val baseSection = baseRomfsSec.plain
        val dataOff = ByteBuffer.wrap(img.ivfcSec(newRomfs).fsHeader).order(ByteOrder.LITTLE_ENDIAN).getLong(0x18 + 5 * 0x18).toInt()
        // The RomFS header says where the file data starts (its DataOffset field, 0x48).
        val fileData = dataOff + ByteBuffer.wrap(newRomfs).order(ByteOrder.LITTLE_ENDIAN).getLong(0x48).toInt()
        val chunk = fileData / 0x200
        fun same(k: Int) = baseSection.copyOfRange(k * 0x200, (k + 1) * 0x200).contentEquals(newSection.copyOfRange(k * 0x200, (k + 1) * 0x200))
        assertTrue(!same(chunk), "a.txt should be stored in the patch")
        // Patch data holds the patched chunks in order; this chunk's first byte is at (patched chunks before it) * 0x200 + offset.
        val patchPos = (0 until chunk).count { !same(it) } * 0x200L + fileData % 0x200
        val upd = Nca(ByteArraySource(updateNca), keys)
        val patchStart = upd.sections.first { it.isRomFs }.start
        val at = (patchStart + patchPos).toInt()
        val tampered = updateNca.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }
        val input = img.xci(mapOf("base.nca" to baseNca), updates = mapOf("upd.nca" to tampered))

        val e = assertFailsWith<SwitchFormatException> { SwitchExtractor(keys).extract(ByteArraySource(input), MemorySink()) }
        assertTrue(e.message!!.contains("hash check"), e.message)

        // With the hash check off, the same dump extracts (with the wrong byte, which is the point of the check).
        val sink = MemorySink()
        SwitchExtractor(keys).extract(ByteArraySource(input), sink, ExtractOptions(verifyHashes = false))
        assertTrue(sink.files.keys.any { it.endsWith("romfs/a.txt") })
    }

    @Test
    fun tablesNeedingLevelTwoNodesAreUnsupported() {
        // An entry count that would need L2 nodes: 2000000 relocation entries of 0x14 bytes.
        val table = ByteArray(0x10 + 0x4000).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0x52544B42).putInt(4, 1).putInt(8, 2_000_000)
        }
        assertFailsWith<com.tufein.switchrecomphelper.core.UnsupportedSectionException> {
            BucketTree.open(ByteArraySource(table), 0x14, "test")
        }
    }
}

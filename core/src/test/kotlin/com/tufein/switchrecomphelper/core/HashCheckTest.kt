package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The hash check of RomFS (IVFC) and ExeFS (HierarchicalSha256). Sections are stored unencrypted here, so a byte of
 * the dump can be changed exactly where a test wants it.
 */
class HashCheckTest {
    private val img = TestImage(55)
    private val keys = KeySet.parse(img.keyText)
    private val romfs = linkedMapOf("sub/b.bin" to ByteArray(40_000) { (it * 17 + 3).toByte() }, "a.txt" to "hello".toByteArray())
    private val exefs = linkedMapOf("main" to ByteArray(5000) { (it * 11).toByte() })

    private fun programNca(): ByteArray = img.nca(
        0,
        listOf(Triple(1, img.pfs0(exefs), 1), Triple(0, img.romfs(romfs), 1)),
    )

    /** Changes one byte in the middle of [needle]'s first occurrence in [bytes]. */
    private fun corrupt(bytes: ByteArray, needle: ByteArray): ByteArray {
        val at = indexOf(bytes, needle)
        assertTrue(at >= 0, "needle not found in the dump")
        return bytes.copyOf().also { it[at + needle.size / 2] = (it[at + needle.size / 2].toInt() xor 0x40).toByte() }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun romFsByteChangeIsReportedAsHashMismatch() {
        val dump = corrupt(programNca(), romfs["sub/b.bin"]!!.copyOfRange(100, 132))
        val input = img.xci(mapOf("p.nca" to dump))
        val e = assertFailsWith<SwitchFormatException> { SwitchExtractor(keys).extract(ByteArraySource(input), MemorySink()) }
        assertTrue(e.message!!.contains("RomFS hash check failed"), e.message)
    }

    @Test
    fun exeFsByteChangeIsReportedAsHashMismatch() {
        val dump = corrupt(programNca(), exefs["main"]!!.copyOfRange(100, 132))
        val input = img.xci(mapOf("p.nca" to dump))
        val e = assertFailsWith<SwitchFormatException> { SwitchExtractor(keys).extract(ByteArraySource(input), MemorySink()) }
        assertTrue(e.message!!.contains("PFS0 hash check failed"), e.message)
    }

    @Test
    fun turningTheCheckOffExtractsTheSameDumpAnyway() {
        val dump = corrupt(programNca(), romfs["sub/b.bin"]!!.copyOfRange(100, 132))
        val input = img.xci(mapOf("p.nca" to dump))
        val sink = MemorySink()
        SwitchExtractor(keys).extract(ByteArraySource(input), sink, ExtractOptions(verifyHashes = false))
        val got = sink.bytes("${"%016x".format(img.programId)}/romfs/sub/b.bin")
        assertTrue(!got.contentEquals(romfs["sub/b.bin"]), "the changed byte should come through")
    }

    @Test
    fun intactDumpPassesTheCheck() {
        val input = img.xci(mapOf("p.nca" to programNca()))
        val sink = MemorySink()
        SwitchExtractor(keys).extract(ByteArraySource(input), sink)
        assertContentEquals(romfs["sub/b.bin"], sink.bytes("${"%016x".format(img.programId)}/romfs/sub/b.bin"))
        assertContentEquals(exefs["main"], sink.bytes("${"%016x".format(img.programId)}/exefs/main"))
    }
}

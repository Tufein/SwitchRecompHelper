package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.crypto.AesUtil
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One NCA section as the builder lays it out: its 0x200-byte FS header, the decrypted bytes ([plain], also used
 * for NCZ bodies) and how the bytes are stored in the NCA. [body] overrides the default storage (AES-CTR with the
 * section IV for encryption type 3, plain otherwise) and receives the NCA offset of the section and its IV.
 */
class Sec(
    val fsHeader: ByteArray,
    val plain: ByteArray,
    val enc: Int,
    val body: ((start: Long, upper: ByteArray) -> ByteArray)? = null,
)

/** Builds a synthetic (and obviously fake) XCI with known keys, following the on-disk layout the parser reads. */
class TestImage(seed: Long = 1) {
    private val rnd = Random(seed)
    val headerKey = ByteArray(32).also(rnd::nextBytes)
    val masterKey = ByteArray(16).also(rnd::nextBytes)
    val kaekApp = ByteArray(16).also(rnd::nextBytes)
    private val ctrKey = ByteArray(16).also(rnd::nextBytes)
    private val titlekek = ByteArray(16).also(rnd::nextBytes)

    val keyText: String
        get() = "header_key = ${headerKey.toHex()}\nmaster_key_00 = ${masterKey.toHex()}\n" +
            "; comment\nkey_area_key_application_00 = ${kaekApp.toHex()}\ntitlekek_00 = ${titlekek.toHex()}\n"

    val exefsFiles = linkedMapOf(
        "main" to ByteArray(5000).also(rnd::nextBytes),
        "main.npdm" to ByteArray(333).also(rnd::nextBytes),
        "rtld" to ByteArray(17).also(rnd::nextBytes),
    )
    val romfsFiles = linkedMapOf(
        "a.txt" to "hello".toByteArray(),
        "sub/b.bin" to ByteArray(70000).also(rnd::nextBytes),
        "sub/deep/c.dat" to ByteArray(1).also(rnd::nextBytes),
        "empty" to ByteArray(0),
    )
    val programId = 0x0100AABBCCDD0000L

    private fun le(size: Int, f: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(f).array()
    private fun pad(b: ByteArray, align: Int) = b.copyOf((b.size + align - 1) / align * align)

    fun pfs0(files: Map<String, ByteArray>, hfs: Boolean = false): ByteArray {
        val entrySize = if (hfs) 0x40 else 0x18
        val names = ByteArrayOutputStream()
        val nameOffsets = files.keys.map { n -> names.size().also { names.write(n.toByteArray()); names.write(0) } }
        val strTab = pad(names.toByteArray(), 0x20)
        val out = ByteArrayOutputStream()
        out.write(le(0x10) { put((if (hfs) "HFS0" else "PFS0").toByteArray()); putInt(files.size); putInt(strTab.size); putInt(0) })
        var off = 0L
        files.values.forEachIndexed { i, d ->
            out.write(le(entrySize) { putLong(off); putLong(d.size.toLong()); putInt(nameOffsets[i]) })
            off += d.size
        }
        out.write(strTab)
        files.values.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun align(x: Int, a: Int) = (x + a - 1) / a * a

    fun romfs(files: Map<String, ByteArray>): ByteArray {
        // directory tree
        class Dir(val name: String, val parent: Dir?) { val dirs = linkedMapOf<String, Dir>(); val files = linkedMapOf<String, ByteArray>(); var off = 0 }
        val root = Dir("", null)
        for ((p, d) in files) {
            val parts = p.split('/')
            var cur = root
            parts.dropLast(1).forEach { cur = cur.dirs.getOrPut(it) { Dir(it, cur) } }
            cur.files[parts.last()] = d
        }
        val allDirs = ArrayList<Dir>()
        fun collect(d: Dir) { allDirs.add(d); d.dirs.values.forEach(::collect) }
        collect(root)
        var o = 0
        for (d in allDirs) { d.off = o; o += 0x18 + align(d.name.toByteArray().size, 4) }
        val dirMeta = ByteBuffer.allocate(o).order(ByteOrder.LITTLE_ENDIAN)
        val fileOffsets = HashMap<Pair<Dir, String>, Int>()
        var fo = 0
        for (d in allDirs) for (n in d.files.keys) { fileOffsets[d to n] = fo; fo += 0x20 + align(n.toByteArray().size, 4) }
        val fileMeta = ByteBuffer.allocate(fo).order(ByteOrder.LITTLE_ENDIAN)
        val data = ByteArrayOutputStream()
        for (d in allDirs) {
            val sibs = d.parent?.dirs?.values?.toList()
            val sib = sibs?.getOrNull(sibs.indexOf(d) + 1)?.off ?: -1
            val child = d.dirs.values.firstOrNull()?.off ?: -1
            val cf = d.files.keys.firstOrNull()?.let { fileOffsets[d to it] } ?: -1
            val nb = d.name.toByteArray()
            dirMeta.position(d.off)
            dirMeta.putInt(d.parent?.off ?: 0).putInt(sib).putInt(child).putInt(cf).putInt(-1).putInt(nb.size).put(nb)
            val names = d.files.keys.toList()
            names.forEachIndexed { i, n ->
                val bytes = d.files[n]!!
                while (data.size() % 16 != 0) data.write(0)
                val doff = data.size()
                data.write(bytes)
                val nsib = names.getOrNull(i + 1)?.let { fileOffsets[d to it] } ?: -1
                val nbs = n.toByteArray()
                fileMeta.position(fileOffsets[d to n]!!)
                fileMeta.putInt(d.off).putInt(nsib).putLong(doff.toLong()).putLong(bytes.size.toLong()).putInt(-1).putInt(nbs.size).put(nbs)
            }
        }
        val dirHash = ByteArray(4) { 0xFF.toByte() }
        val fileHash = ByteArray(4) { 0xFF.toByte() }
        var cur = 0x50
        val dhOff = cur; cur += dirHash.size
        val dmOff = cur; cur += dirMeta.capacity()
        val fhOff = cur; cur += fileHash.size
        val fmOff = cur; cur += fileMeta.capacity()
        val dataOff = align(cur, 16)
        val out = ByteArrayOutputStream()
        out.write(le(0x50) {
            putLong(0x50); putLong(dhOff.toLong()); putLong(dirHash.size.toLong()); putLong(dmOff.toLong()); putLong(dirMeta.capacity().toLong())
            putLong(fhOff.toLong()); putLong(fileHash.size.toLong()); putLong(fmOff.toLong()); putLong(fileMeta.capacity().toLong()); putLong(dataOff.toLong())
        })
        out.write(dirHash); out.write(dirMeta.array()); out.write(fileHash); out.write(fileMeta.array())
        while (out.size() < dataOff) out.write(0)
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    private fun ctr(data: ByteArray, sectionStart: Long, upper: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ctrKey, "AES"), IvParameterSpec(AesUtil.ncaCounter(upper, sectionStart)))
        return c.doFinal(data)
    }

    class Built(val nca: ByteArray, /** decrypted bytes of the NCA from offset 0x4000 (only meaningful if firstSectionAt == 0x4000) */ val plainBody: ByteArray)

    fun nca(type: Int, sections: List<Triple<Int, ByteArray, Int>>, titleId: Long = programId): ByteArray =
        build(type, sections, titleId).nca

    /** Rights id used for title-key crypto builds. */
    val rightsId = ByteArray(16) { (0xA0 + it).toByte() }

    /** Encrypted title key as it appears in a ticket (titlekek-encrypted section key). */
    private val encTitleKey: ByteArray get() = AesUtil.ecbEncrypt(titlekek, ctrKey)

    fun ticket(): ByteArray {
        val t = ByteArray(0x2C0)
        ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x10004)
        val body = 0x140
        encTitleKey.copyInto(t, body + 0x40)
        t[body + 0x141] = 0
        rightsId.copyInto(t, body + 0x160)
        return t
    }

    /**
     * Section for a PFS0 payload with HierarchicalSha256 hashes (block size 0x200): the hash table sits at the
     * start of the section, the PFS0 after it. The master hash covers the table.
     */
    fun pfs0Sec(payload: ByteArray, enc: Int = 3): Sec {
        val blocks = (payload.size + 0x1FF) / 0x200
        val table = ByteArray(blocks * 32)
        for (b in 0 until blocks) {
            val part = payload.copyOfRange(b * 0x200, minOf(payload.size, (b + 1) * 0x200))
            sha256(part).copyInto(table, b * 32)
        }
        val dataOff = align(maxOf(table.size, 1), 0x200)
        val plain = ByteArray(dataOff + align(payload.size, 0x200)).also { table.copyInto(it, 0); payload.copyInto(it, dataOff) }
        val fh = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN)
        fh.putShort(2).put(1).put(2).put(enc.toByte())
        sha256(table).copyInto(fh.array(), 0x8)
        fh.putInt(0x28, 0x200).putInt(0x2C, 2)
        fh.putLong(0x30, 0).putLong(0x38, table.size.toLong())
        fh.putLong(0x40, dataOff.toLong()).putLong(0x48, payload.size.toLong())
        return Sec(fh.array(), plain, enc)
    }

    /**
     * Section for a RomFS payload with IVFC hashes: five hash levels of 2^14-byte blocks above the data, all
     * hashes correct, so the section verifies. Level 0 (the top) is what the master hash covers.
     */
    fun ivfcSec(payload: ByteArray, enc: Int = 3): Sec {
        val order = 14
        val block = 1 shl order
        // Hash tables from the data upwards: tables[0] hashes the data, tables[k] hashes tables[k-1].
        val tables = ArrayList<ByteArray>()
        var child = payload
        repeat(5) {
            val blocks = (child.size + block - 1) / block
            val table = ByteArray(blocks * 32)
            for (b in 0 until blocks) sha256(paddedBlock(child, b, block)).copyInto(table, b * 32)
            tables.add(table)
            child = table
        }
        // Level i (0 = top) holds tables[4 - i]; the data is level 5.
        val levels = (0 until 5).map { tables[4 - it] } + listOf(payload)
        val offsets = LongArray(6)
        var pos = 0L
        for (i in 0 until 6) {
            offsets[i] = pos
            pos += ((levels[i].size + block - 1) / block * block).toLong()
        }
        val plain = ByteArray(pos.toInt())
        for (i in 0 until 6) levels[i].copyInto(plain, offsets[i].toInt())
        val master = sha256(paddedBlock(levels[0], 0, block))
        val fh = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN)
        fh.putShort(2).put(0).put(3).put(enc.toByte())
        fh.position(0x8)
        fh.put("IVFC".toByteArray()); fh.putInt(0x20000); fh.putInt(0x20); fh.putInt(7)
        for (i in 0 until 6) {
            fh.putLong(offsets[i]); fh.putLong(levels[i].size.toLong()); fh.putInt(order); fh.putInt(0)
        }
        fh.position(0xC8); fh.put(master)
        return Sec(fh.array(), plain, enc)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    /** Block [index] of [data], zero-padded to [size] as the hash tree sees it. */
    private fun paddedBlock(data: ByteArray, index: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        val from = index * size
        System.arraycopy(data, from, out, 0, minOf(size, data.size - from))
        return out
    }

    /** [useTitleKey]: set a rights id so the section key comes from a ticket/title.keys instead of the key area. */
    fun build(
        type: Int,
        sections: List<Triple<Int, ByteArray, Int>>,
        titleId: Long = programId,
        firstSectionAt: Long = 0xC00,
        useTitleKey: Boolean = false,
    ): Built {
        // sections: (fsType, payload, encryptionType)
        val secs = sections.map { (fsType, payload, enc) -> if (fsType == 1) pfs0Sec(payload, enc) else ivfcSec(payload, enc) }
        return assemble(type, secs, titleId, firstSectionAt, useTitleKey)
    }

    /** Lays out ready-made sections in an NCA: header, key area, then the sections in order. */
    fun assemble(
        type: Int,
        secs: List<Sec>,
        titleId: Long = programId,
        firstSectionAt: Long = 0xC00,
        useTitleKey: Boolean = false,
    ): Built {
        val hdr = ByteArray(0xC00)
        "NCA3".toByteArray().copyInto(hdr, 0x200)
        hdr[0x205] = type.toByte(); hdr[0x206] = 1; hdr[0x207] = 0; hdr[0x220] = 0 // key generation 1 -> master_key_00
        ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).putLong(0x210, titleId)
        val keyArea = ByteArray(0x40).also { ctrKey.copyInto(it, 0x20) }
        AesUtil.ecbEncrypt(kaekApp, keyArea).copyInto(hdr, 0x300)
        if (useTitleKey) rightsId.copyInto(hdr, 0x230)
        var pos = firstSectionAt
        val body = ByteArrayOutputStream()
        body.write(ByteArray((firstSectionAt - 0xC00).toInt()))
        val plainOut = ByteArrayOutputStream()
        secs.forEachIndexed { i, sec ->
            val upper = ByteArray(8) { (i * 8 + it + 1).toByte() }
            val fh = sec.fsHeader.copyOf()
            upper.reversedArray().copyInto(fh, 0x140)
            fh.copyInto(hdr, 0x400 + i * 0x200)
            val stored = sec.body?.invoke(pos, upper) ?: if (sec.enc == 3) ctr(sec.plain, pos, upper) else sec.plain
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(0x240 + i * 0x10, (pos / 0x200).toInt()); bb.putInt(0x244 + i * 0x10, ((pos + stored.size) / 0x200).toInt())
            body.write(stored)
            plainOut.write(sec.plain)
            pos += stored.size
        }
        ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).putLong(0x208, pos)
        return Built(AesUtil.xtsEncrypt(headerKey, hdr, 0) + body.toByteArray(), plainOut.toByteArray())
    }

    /**
     * Update (patch) section, BKTR style. [baseSection] is the decrypted section of the base game and [newRomfs] the
     * complete new RomFS payload. The new section is an IVFC section of [newRomfs]. Every 512-byte chunk that equals
     * the base at the same offset is taken from the base (relocation storage 0); all other chunks are stored in the
     * patch data (storage 1). The patch data is split into 0x2000-byte subsections with their own generation, and
     * every third subsection is stored in plain text. The relocation table lives in the patch data too.
     */
    fun patchSec(baseSection: ByteArray, newRomfs: ByteArray): Sec {
        val template = ivfcSec(newRomfs)
        val newSection = template.plain
        val chunk = 0x200
        val patchChunks = ByteArrayOutputStream()
        val relocations = ArrayList<ByteArray>()
        var patchPos = 0L
        for (k in 0 until newSection.size / chunk) {
            val va = k.toLong() * chunk
            val piece = newSection.copyOfRange(k * chunk, (k + 1) * chunk)
            val fromBase = k * chunk + chunk <= baseSection.size &&
                baseSection.copyOfRange(k * chunk, (k + 1) * chunk).contentEquals(piece)
            if (fromBase) {
                relocations.add(le(0x14) { putLong(va); putLong(va); putInt(0) })
            } else {
                relocations.add(le(0x14) { putLong(va); putLong(patchPos); putInt(1) })
                patchChunks.write(piece)
                patchPos += chunk
            }
        }
        val chunkBytes = patchChunks.toByteArray()
        val relocTable = bucketTable(relocations, 0x14, newSection.size.toLong())
        val dataPlain = chunkBytes + relocTable
        val dataLen = dataPlain.size

        val subsections = (dataLen + 0x1FFF) / 0x2000
        val encrypted = BooleanArray(subsections) { it % 3 != 2 }
        val generations = IntArray(subsections) { 1 + it % 4 }
        val subEntries = (0 until subsections).map { j ->
            le(0x10) {
                putLong(j.toLong() * 0x2000)
                put((if (encrypted[j]) 0 else 1).toByte()); put(0); put(0); put(0)
                putInt(generations[j])
            }
        }
        val subTable = bucketTable(subEntries, 0x10, dataLen.toLong())

        val fh = template.fsHeader.copyOf()
        val bb = ByteBuffer.wrap(fh).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(0x100, chunkBytes.size.toLong()).putLong(0x108, relocTable.size.toLong())
        relocTable.copyInto(fh, 0x110, 0, 16)
        bb.putLong(0x120, dataLen.toLong()).putLong(0x128, subTable.size.toLong())
        subTable.copyInto(fh, 0x130, 0, 16)
        // The secure value lives in the section IV (0x140..0x148, written by assemble); the subsections use it too.
        fh[4] = 4 // AES-CTR-EX: the section is an update
        fh[5] = 0 // no 14.0+ meta hash layer

        val padded = align(dataLen + subTable.size, 0x200)
        val body: (Long, ByteArray) -> ByteArray = { start, upper ->
            val out = ByteArray(padded)
            // The upper IV is secure value (high 4 bytes) and generation (low 4), big-endian: the same bytes the parser uses.
            val secureValue = ByteBuffer.wrap(upper, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            for (j in 0 until subsections) {
                val from = j * 0x2000
                val to = minOf(dataLen, from + 0x2000)
                val piece = dataPlain.copyOfRange(from, to)
                val stored = if (encrypted[j]) {
                    // Same IV layout as a normal section: secure value and subsection generation, big-endian.
                    val iv = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt(secureValue).putInt(generations[j]).array()
                    ctr(piece, start + from, iv)
                } else piece
                stored.copyInto(out, from)
            }
            // The subsection table is encrypted with the section IV, like the rest of the table area.
            ctr(subTable, start + dataLen, upper).copyInto(out, dataLen)
            out
        }
        return Sec(fh, newSection, 4, body)
    }

    /**
     * BKTR table: 0x10-byte header, one L1 node and entry sets of 0x4000 bytes each. [entries] are the fixed-size
     * entries in virtual-offset order (the first 8 bytes of each are the virtual offset).
     */
    fun bucketTable(entries: List<ByteArray>, entrySize: Int, endOffset: Long): ByteArray {
        val perSet = (0x4000 - 0x10) / entrySize
        val sets = entries.chunked(perSet)
        val out = ByteBuffer.allocate(0x10 + 0x4000 * (1 + sets.size)).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0, 0x52544B42).putInt(4, 1).putInt(8, entries.size)
        out.putInt(0x10, 0).putInt(0x14, sets.size).putLong(0x18, endOffset)
        sets.forEachIndexed { i, set ->
            out.putLong(0x20 + i * 8, ByteBuffer.wrap(set.first()).order(ByteOrder.LITTLE_ENDIAN).getLong(0))
        }
        sets.forEachIndexed { i, set ->
            val base = 0x10 + 0x4000 * (1 + i)
            val setEnd = if (i + 1 < sets.size) ByteBuffer.wrap(sets[i + 1].first()).order(ByteOrder.LITTLE_ENDIAN).getLong(0) else endOffset
            out.putInt(base, i).putInt(base + 4, set.size).putLong(base + 8, setEnd)
            set.forEachIndexed { j, e -> out.position(base + 0x10 + j * entrySize); out.put(e) }
        }
        return out.array()
    }

    /**
     * NCZ from an NCA built with firstSectionAt = 0x4000: raw 0x4000-byte header, NCZSECTN table, then the
     * decrypted body as one zstd stream ([blockExponent] null) or independent blocks (NCZBLOCK).
     */
    fun ncz(b: Built, blockExponent: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(b.nca, 0, 0x4000)
        out.write(le(0x10) { put("NCZSECTN".toByteArray()); putLong(1) })
        out.write(le(0x40) { putLong(0x4000); putLong(b.plainBody.size.toLong()); putLong(3); putLong(0) })
        if (blockExponent == null) {
            out.write(com.github.luben.zstd.Zstd.compress(b.plainBody, 3))
        } else {
            val bs = 1 shl blockExponent
            val blocks = (b.plainBody.size + bs - 1) / bs
            val comps = (0 until blocks).map { i ->
                val raw = b.plainBody.copyOfRange(i * bs, minOf(b.plainBody.size, (i + 1) * bs))
                val c = com.github.luben.zstd.Zstd.compress(raw, 3)
                if (c.size >= raw.size) raw else c
            }
            out.write(le(0x18) { put("NCZBLOCK".toByteArray()); put(2); put(1); put(0); put(blockExponent.toByte()); putInt(blocks); putLong(b.plainBody.size.toLong()) })
            comps.forEach { out.write(le(4) { putInt(it.size) }) }
            comps.forEach { out.write(it) }
        }
        return out.toByteArray()
    }

    /** Programs for NSP/NSZ tests: sections at 0x4000 like real NCAs. */
    fun programBuilt(useTitleKey: Boolean = false, exefsIn: Map<String, ByteArray> = exefsFiles, romfsIn: Map<String, ByteArray> = romfsFiles): Built =
        build(
            0,
            listOf(Triple(1, pfs0(exefsIn), 3), Triple(0, romfs(romfsIn), 3), Triple(1, pfs0(mapOf("NintendoLogo.png" to ByteArray(10))), 3)),
            firstSectionAt = 0x4000,
            useTitleKey = useTitleKey,
        )

    fun nsp(entries: Map<String, ByteArray>): ByteArray = pfs0(entries, hfs = false)

    /** XCI with the NCAs in the secure partition, and optionally update NCAs in the update partition. */
    fun xci(ncas: Map<String, ByteArray>, prefix: Int = 0, updates: Map<String, ByteArray> = emptyMap()): ByteArray {
        val secure = pfs0(ncas, hfs = true)
        val normal = pfs0(emptyMap(), hfs = true)
        val root = if (updates.isEmpty()) {
            pfs0(linkedMapOf("normal" to normal, "secure" to secure), hfs = true)
        } else {
            pfs0(linkedMapOf("normal" to normal, "secure" to secure, "update" to pfs0(updates, hfs = true)), hfs = true)
        }
        val head = ByteArray(0xF000)
        "HEAD".toByteArray().copyInto(head, 0x100)
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).putLong(0x130, 0xF000).putLong(0x138, root.size.toLong())
        return ByteArray(prefix) + head + root
    }

    fun standardXci(prefix: Int = 0): ByteArray {
        val program = nca(0, listOf(
            Triple(1, pfs0(exefsFiles), 3),
            Triple(0, romfs(romfsFiles), 3),
            Triple(1, pfs0(mapOf("NintendoLogo.png" to ByteArray(10))), 3),
        ))
        val meta = nca(1, emptyList(), 0x0100AABBCCDD0800L)
        return xci(linkedMapOf("${"%032x".format(1)}.nca" to program, "${"%032x".format(2)}.cnmt.nca" to meta), prefix)
    }
}

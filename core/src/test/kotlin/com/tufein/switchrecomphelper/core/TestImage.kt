package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.crypto.AesUtil
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Builds a synthetic (and obviously fake) XCI with known keys, following the on-disk layout the parser reads. */
class TestImage(seed: Long = 1) {
    private val rnd = Random(seed)
    val headerKey = ByteArray(32).also(rnd::nextBytes)
    val masterKey = ByteArray(16).also(rnd::nextBytes)
    val kaekApp = ByteArray(16).also(rnd::nextBytes)
    private val ctrKey = ByteArray(16).also(rnd::nextBytes)

    val keyText: String
        get() = "header_key = ${headerKey.toHex()}\nmaster_key_00 = ${masterKey.toHex()}\n" +
            "; comment\nkey_area_key_application_00 = ${kaekApp.toHex()}\n"

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
        val dataOff = align(cur, 16); 
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

    fun nca(type: Int, sections: List<Triple<Int, ByteArray, Int>>, titleId: Long = programId): ByteArray {
        // sections: (fsType, payload, encryptionType)
        val hdr = ByteArray(0xC00)
        "NCA3".toByteArray().copyInto(hdr, 0x200)
        hdr[0x205] = type.toByte(); hdr[0x206] = 1; hdr[0x207] = 0; hdr[0x220] = 0 // key generation 1 -> master_key_00
        ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).putLong(0x210, titleId)
        val keyArea = ByteArray(0x40).also { ctrKey.copyInto(it, 0x20) }
        AesUtil.ecbEncrypt(kaekApp, keyArea).copyInto(hdr, 0x300)
        var pos = 0xC00L
        val body = ByteArrayOutputStream()
        sections.forEachIndexed { i, (fsType, payload, enc) ->
            val upper = ByteArray(8) { (i * 8 + it + 1).toByte() }
            // Layout inside section: hash region (0x200 zeros), then payload at 0x200 (PFS0) / 0x4000 (IVFC).
            val dataOff = if (fsType == 1) 0x200 else 0x4000
            val plain = ByteArray(dataOff + align(payload.size, 0x200)).also { payload.copyInto(it, dataOff) }
            val fh = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN)
            fh.putShort(2).put(fsType.toByte()).put(if (fsType == 1) 2 else 3).put(enc.toByte())
            fh.position(0x8)
            if (fsType == 1) {
                fh.position(0x28); fh.putInt(0x200); fh.putInt(2)
                fh.putLong(0).putLong(0x20); fh.putLong(dataOff.toLong()).putLong(payload.size.toLong())
            } else {
                fh.put("IVFC".toByteArray()); fh.putInt(0x20000); fh.putInt(0x20); fh.putInt(7)
                for (l in 0 until 6) {
                    if (l == 5) { fh.putLong(dataOff.toLong()); fh.putLong(payload.size.toLong()) } else { fh.putLong(0); fh.putLong(0) }
                    fh.putInt(14); fh.putInt(0)
                }
            }
            fh.position(0x140); fh.put(upper.reversedArray())
            fh.array().copyInto(hdr, 0x400 + i * 0x200)
            val enc2 = if (enc == 3) ctr(plain, pos, upper) else plain
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(0x240 + i * 0x10, (pos / 0x200).toInt()); bb.putInt(0x244 + i * 0x10, ((pos + plain.size) / 0x200).toInt())
            body.write(enc2)
            pos += plain.size
        }
        return AesUtil.xtsEncrypt(headerKey, hdr, 0) + body.toByteArray()
    }

    fun xci(ncas: Map<String, ByteArray>, prefix: Int = 0): ByteArray {
        val secure = pfs0(ncas, hfs = true)
        val normal = pfs0(emptyMap(), hfs = true)
        val root = pfs0(linkedMapOf("normal" to normal, "secure" to secure), hfs = true)
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

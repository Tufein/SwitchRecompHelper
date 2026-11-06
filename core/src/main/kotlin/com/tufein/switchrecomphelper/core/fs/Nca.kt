package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.KeySet
import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.crypto.AesCtrSource
import com.tufein.switchrecomphelper.core.crypto.AesUtil
import com.tufein.switchrecomphelper.core.io.RandomAccessSource
import com.tufein.switchrecomphelper.core.io.readBytes
import com.tufein.switchrecomphelper.core.io.slice
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NcaSection(
    val index: Int,
    val start: Long,
    val end: Long,
    /** 0 = RomFS, 1 = PartitionFS (PFS0) */
    val fsType: Int,
    val hashType: Int,
    /** 0/3 = AES-CTR, 1 = none, 4 = BKTR (patch, unsupported) */
    val encryptionType: Int,
    internal val fsHeader: ByteArray,
) {
    val size: Long get() = end - start
    val isRomFs: Boolean get() = fsType == 0
    val isPfs0: Boolean get() = fsType == 1
}

/** Nintendo Content Archive. Header is decrypted on construction; section keys are derived lazily. */
class Nca(private val source: RandomAccessSource, private val keys: KeySet) {
    val contentType: Int
    val programId: Long
    val sections: List<NcaSection>

    private val keyGenOld: Int
    private val keyGen: Int
    private val kaekIndex: Int
    private val rightsId: ByteArray
    private val encKeyArea: ByteArray

    init {
        val raw = source.readBytes(0, HEADER_SIZE)
        val hdr = AesUtil.xtsDecrypt(keys.headerKey, raw, 0)
        when (val magic = String(hdr, 0x200, 4, Charsets.US_ASCII)) {
            "NCA3" -> {}
            // NCA2 encrypts every section header as its own sector 0.
            "NCA2" -> for (i in 0 until 4) {
                val dec = AesUtil.xtsDecrypt(keys.headerKey, raw.copyOfRange(0x400 + i * 0x200, 0x600 + i * 0x200), 0)
                dec.copyInto(hdr, 0x400 + i * 0x200)
            }
            else -> throw SwitchFormatException(
                "NCA header could not be decrypted (found '${magic.filter { it.isLetterOrDigit() }}'). The header_key in prod.keys is probably wrong.",
            )
        }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        contentType = hdr[0x205].toInt() and 0xFF
        keyGenOld = hdr[0x206].toInt() and 0xFF
        kaekIndex = hdr[0x207].toInt() and 0xFF
        programId = bb.getLong(0x210)
        keyGen = hdr[0x220].toInt() and 0xFF
        rightsId = hdr.copyOfRange(0x230, 0x240)
        encKeyArea = hdr.copyOfRange(0x300, 0x340)

        val list = ArrayList<NcaSection>()
        for (i in 0 until 4) {
            val startUnits = bb.getInt(0x240 + i * 0x10).toLong() and 0xFFFFFFFFL
            val endUnits = bb.getInt(0x244 + i * 0x10).toLong() and 0xFFFFFFFFL
            if (endUnits <= startUnits) continue
            val fh = hdr.copyOfRange(0x400 + i * 0x200, 0x600 + i * 0x200)
            list.add(
                NcaSection(
                    index = i,
                    start = startUnits * 0x200,
                    end = endUnits * 0x200,
                    fsType = fh[2].toInt() and 0xFF,
                    hashType = fh[3].toInt() and 0xFF,
                    encryptionType = fh[4].toInt() and 0xFF,
                    fsHeader = fh,
                ),
            )
        }
        sections = list
    }

    private val usesTitleKey: Boolean get() = rightsId.any { it != 0.toByte() }

    private val masterKeyRevision: Int
        get() {
            val gen = maxOf(keyGenOld, keyGen)
            return if (gen > 0) gen - 1 else 0
        }

    private val ctrKey: ByteArray by lazy {
        val rev = masterKeyRevision
        if (usesTitleKey) {
            val encTitleKey = keys.titleKey(rightsId)
                ?: throw SwitchFormatException("This NCA uses a title key (rights id ${rightsId.joinToString("") { "%02x".format(it) }}) that is not in your key file. Add it via title.keys.")
            AesUtil.ecbDecrypt(keys.titleKek(rev), encTitleKey)
        } else {
            val keyArea = AesUtil.ecbDecrypt(keys.keyAreaKey(kaekIndex, rev), encKeyArea)
            keyArea.copyOfRange(0x20, 0x30)
        }
    }

    /** Whole section, decrypted. Offsets are relative to the section start. */
    fun openSection(s: NcaSection): RandomAccessSource {
        val raw = source.slice(s.start, s.size)
        return when (s.encryptionType) {
            1 -> raw
            0, 3 -> {
                val upper = s.fsHeader.copyOfRange(0x140, 0x148).reversedArray()
                AesCtrSource(raw, ctrKey, upper, s.start)
            }
            else -> throw SwitchFormatException("Unsupported section encryption type ${s.encryptionType} (patch/BKTR sections are not supported)")
        }
    }

    /** The filesystem payload of a section, i.e. the PFS0 or RomFS image without the integrity hash layers. */
    fun openFilesystem(s: NcaSection): RandomAccessSource {
        val section = openSection(s)
        val bb = ByteBuffer.wrap(s.fsHeader).order(ByteOrder.LITTLE_ENDIAN)
        val (offset, size) = when {
            s.isPfs0 -> {
                // HierarchicalSha256: layer regions start at 0x30, the last layer is the PFS0 itself.
                val layers = bb.getInt(0x2C)
                val idx = (layers - 1).coerceIn(1, 5)
                bb.getLong(0x30 + idx * 0x10) to bb.getLong(0x38 + idx * 0x10)
            }
            s.isRomFs -> {
                // IVFC: levels start at 0x18 (0x18 bytes each), the last level is the RomFS image.
                val levels = bb.getInt(0x14)
                val idx = (levels - 2).coerceIn(0, 5)
                bb.getLong(0x18 + idx * 0x18) to bb.getLong(0x20 + idx * 0x18)
            }
            else -> throw SwitchFormatException("Unknown section filesystem type ${s.fsType}")
        }
        if (offset < 0 || size <= 0 || offset > section.size || size > section.size - offset) {
            throw SwitchFormatException("Section ${s.index} layout is invalid (wrong keys or corrupt dump?)")
        }
        return section.slice(offset, size)
    }

    companion object {
        const val HEADER_SIZE = 0xC00
        const val TYPE_PROGRAM = 0
    }
}

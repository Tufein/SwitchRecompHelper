package com.tufein.switchrecomphelper.core.fs

import com.tufein.switchrecomphelper.core.KeySet
import com.tufein.switchrecomphelper.core.SwitchFormatException
import com.tufein.switchrecomphelper.core.UnsupportedSectionException
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
    /** 0/3 = AES-CTR, 1 = none, 4 = AES-CTR-EX with a bucket-tree patch (update RomFS, see [Nca.openPatchedSection]) */
    val encryptionType: Int,
    internal val fsHeader: ByteArray,
) {
    val size: Long get() = end - start
    val isRomFs: Boolean get() = fsType == 0
    val isPfs0: Boolean get() = fsType == 1
    /** An update section: its data is the difference to the base game's section. */
    val isPatch: Boolean get() = encryptionType == 4
}

/** Nintendo Content Archive. Header is decrypted on construction; section keys are derived lazily. */
class Nca(
    private val source: RandomAccessSource,
    private val keys: KeySet,
    /** True for an NCZ (compressed NCA, as found in NSZ/XCZ): header + decrypted, zstd-compressed body. */
    private val compressed: Boolean = false,
) {
    val contentType: Int
    val contentSize: Long
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
        contentSize = bb.getLong(0x208)
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

    /** NCZ only: the decrypted NCA body, starting at NCA offset 0x4000. Created on first use. */
    private val plainBody: RandomAccessSource by lazy { Ncz.openBody(source, contentSize) }

    /** Whole section, decrypted. Offsets are relative to the section start. */
    fun openSection(s: NcaSection): RandomAccessSource {
        if (s.encryptionType !in SUPPORTED_ENCRYPTION) {
            if (s.isPatch) throw UnsupportedSectionException("Section ${s.index} is an update (patch) section; it needs its base game")
            throw UnsupportedSectionException("Unsupported section encryption type ${s.encryptionType}")
        }
        if (compressed) {
            // NCZ stores sections already decrypted; no section key is needed.
            if (s.start < Ncz.HEADER_SIZE) throw SwitchFormatException("NCZ section ${s.index} starts inside the header")
            return plainBody.slice(s.start - Ncz.HEADER_SIZE, s.size)
        }
        val raw = source.slice(s.start, s.size)
        return when (s.encryptionType) {
            1 -> raw
            0, 3 -> {
                val upper = s.fsHeader.copyOfRange(0x140, 0x148).reversedArray()
                AesCtrSource(raw, ctrKey, upper, s.start)
            }
            else -> throw UnsupportedSectionException("Unsupported section encryption type ${s.encryptionType}")
        }
    }

    /**
     * The decrypted patch section of an update NCA (encryption type 4, "BKTR"): the base game's section [base] with
     * the update's changes applied. The result has the same layout as a base section, so its FS header applies to it.
     *
     * Layout (offsets relative to the section): patch data [0, subsectionTable), the subsection table (AES-CTR,
     * entries with their own generation) and the relocation table (indirect, normally read through the subsection layer).
     */
    fun openPatchedSection(s: NcaSection, base: RandomAccessSource): RandomAccessSource {
        if (!s.isPatch) throw SwitchFormatException("Section ${s.index} is not an update section")
        if (compressed) throw UnsupportedSectionException("Update sections inside a compressed (NCZ/XCZ) file are not supported yet")
        val fh = ByteBuffer.wrap(s.fsHeader).order(ByteOrder.LITTLE_ENDIAN)
        val indirectOffset = fh.getLong(0x100)
        val indirectSize = fh.getLong(0x108)
        val subsectionOffset = fh.getLong(0x120)
        val subsectionSize = fh.getLong(0x128)
        if (indirectOffset < 0 || indirectSize <= 0 || subsectionOffset < 0 || subsectionSize <= 0 ||
            indirectOffset + indirectSize > subsectionOffset || subsectionOffset + subsectionSize > s.size
        ) {
            throw SwitchFormatException("Section ${s.index} has an invalid update layout (wrong keys or corrupt dump?)")
        }
        // Tables are AES-CTR with the section's own IV. With the 14.0+ meta hash layer the relocation table is read
        // around the subsection layer; without it, it is read through it (as the console does).
        val tablesAroundSubsections = s.fsHeader[5].toInt() == 1
        val upper = s.fsHeader.copyOfRange(0x140, 0x148).reversedArray()
        val raw = source.slice(s.start, s.size)
        fun plainTable(offset: Long, size: Long): RandomAccessSource =
            AesCtrSource(raw.slice(offset, size), ctrKey, upper, s.start + offset)
        val secureValue = fh.getInt(0x144)
        val subsectionTable = BucketTree.open(plainTable(subsectionOffset, subsectionSize), 0x10, "Section ${s.index} subsections")
        val patchData = AesCtrExSource(raw.slice(0, subsectionOffset), subsectionTable, ctrKey, secureValue, s.start)
        val relocationRaw = if (tablesAroundSubsections) plainTable(indirectOffset, indirectSize) else patchData.slice(indirectOffset, indirectSize)
        val relocations = BucketTree.open(relocationRaw, 0x14, "Section ${s.index} relocations")
        return IndirectSource(base, patchData, relocations)
    }

    /** The filesystem payload of a section, i.e. the PFS0 or RomFS image without the integrity hash layers. */
    fun openFilesystem(s: NcaSection, verifyHashes: Boolean = true): RandomAccessSource =
        layoutFilesystem(s, openSection(s), verifyHashes)

    /** Like [openFilesystem], for an update section; [base] is the decrypted section of the base game. */
    fun openPatchedFilesystem(s: NcaSection, base: RandomAccessSource, verifyHashes: Boolean = true): RandomAccessSource =
        layoutFilesystem(s, openPatchedSection(s, base), verifyHashes)

    private fun layoutFilesystem(s: NcaSection, section: RandomAccessSource, verifyHashes: Boolean): RandomAccessSource {
        val bb = ByteBuffer.wrap(s.fsHeader).order(ByteOrder.LITTLE_ENDIAN)
        return when {
            s.isPfs0 -> {
                // HierarchicalSha256: layer regions start at 0x30, the last layer is the PFS0 itself.
                val layers = bb.getInt(0x2C)
                val idx = (layers - 1).coerceIn(1, 5)
                val (offset, size) = bb.getLong(0x30 + idx * 0x10) to bb.getLong(0x38 + idx * 0x10)
                checkRegion(s, section, offset, size)
                val payload = section.slice(offset, size)
                if (!verifyHashes || s.hashType != 2) {
                    payload
                } else {
                    val tableOffset = bb.getLong(0x30)
                    val tableSize = bb.getLong(0x38)
                    checkRegion(s, section, tableOffset, tableSize)
                    // HierarchicalSha256: master hash at 0x08, block size at 0x28.
                    Sha256Verifier(section.slice(tableOffset, tableSize), payload, bb.getInt(0x28), s.fsHeader.copyOfRange(0x08, 0x28)).data
                }
            }
            s.isRomFs -> {
                // IVFC: levels start at 0x18 (0x18 bytes each), the last level is the RomFS image.
                val levelCount = bb.getInt(0x14)
                val idx = (levelCount - 2).coerceIn(0, 5)
                val (offset, size) = bb.getLong(0x18 + idx * 0x18) to bb.getLong(0x20 + idx * 0x18)
                checkRegion(s, section, offset, size)
                if (!verifyHashes || s.hashType == 1 || s.hashType == 0) {
                    section.slice(offset, size)
                } else if (s.hashType == 3) {
                    val levels = (0 until levelCount - 1).map { i ->
                        val at = 0x18 + i * 0x18
                        IvfcLevel(bb.getLong(at), bb.getLong(at + 8), bb.getInt(at + 0x10))
                    }
                    // IVFC: master hash at 0xC8 (IntegrityMetaInfo.MasterHash).
                    IvfcVerifier(section, levels, s.fsHeader.copyOfRange(0xC8, 0xE8)).data
                } else {
                    throw UnsupportedSectionException(
                        "Section ${s.index} uses hash type ${s.hashType} (SHA-3), which the hash check does not support yet. Turn off the hash check to extract it.",
                    )
                }
            }
            else -> throw SwitchFormatException("Unknown section filesystem type ${s.fsType}")
        }
    }

    private fun checkRegion(s: NcaSection, section: RandomAccessSource, offset: Long, size: Long) {
        if (offset < 0 || size <= 0 || offset > section.size || size > section.size - offset) {
            throw SwitchFormatException("Section ${s.index} layout is invalid (wrong keys or corrupt dump?)")
        }
    }

    companion object {
        const val HEADER_SIZE = 0xC00
        const val TYPE_PROGRAM = 0
        private val SUPPORTED_ENCRYPTION = setOf(0, 1, 3)
    }
}

package com.tufein.switchrecomphelper.core

import com.tufein.switchrecomphelper.core.crypto.AesUtil

/**
 * Keys supplied by the user (prod.keys, optionally title.keys appended or merged).
 * The app ships no keys; dump them from your own console (e.g. with Lockpick_RCM).
 */
class KeySet private constructor(private val keys: Map<String, ByteArray>) {
    val size: Int get() = keys.size
    val hasHeaderKey: Boolean get() = keys["header_key"]?.size == 32

    private fun get(name: String): ByteArray? = keys[name]

    private fun need(name: String, size: Int): ByteArray {
        val k = keys[name] ?: throw SwitchFormatException("Key '$name' is missing from prod.keys")
        if (k.size != size) throw SwitchFormatException("Key '$name' has the wrong length (${k.size} bytes, expected $size)")
        return k
    }

    val headerKey: ByteArray get() = need("header_key", 32)

    private fun hex(rev: Int) = "%02x".format(rev)

    fun masterKey(rev: Int): ByteArray = need("master_key_${hex(rev)}", 16)

    /** [kaekIndex]: 0 = application, 1 = ocean, 2 = system (NCA header byte 0x207). */
    fun keyAreaKey(kaekIndex: Int, rev: Int): ByteArray {
        val kind = KAEK_NAMES.getOrNull(kaekIndex) ?: throw SwitchFormatException("Unknown key area key index $kaekIndex")
        get("key_area_key_${kind}_${hex(rev)}")?.takeIf { it.size == 16 }?.let { return it }
        // Older key files only carry the sources; derive the key the way the console does.
        val master = masterKey(rev)
        val kek = AesUtil.ecbDecrypt(master, need("aes_kek_generation_source", 16))
        val srcKek = AesUtil.ecbDecrypt(kek, need("key_area_key_${kind}_source", 16))
        return AesUtil.ecbDecrypt(srcKek, need("aes_key_generation_source", 16))
    }

    fun titleKek(rev: Int): ByteArray {
        get("titlekek_${hex(rev)}")?.takeIf { it.size == 16 }?.let { return it }
        return AesUtil.ecbDecrypt(masterKey(rev), need("titlekek_source", 16))
    }

    /** Encrypted title key for a 16-byte rights id, from title.keys. */
    fun titleKey(rightsId: ByteArray): ByteArray? = keys[rightsId.toHex()]?.takeIf { it.size == 16 }

    /** A copy of this key set with extra (encrypted) title keys, keyed by lowercase rights-id hex, e.g. from tickets in an NSP. */
    fun withTitleKeys(extra: Map<String, ByteArray>): KeySet = KeySet(keys + extra)

    companion object {
        private val KAEK_NAMES = listOf("application", "ocean", "system")

        /** Parses `name = hexvalue` lines. Unknown/invalid lines are ignored. */
        fun parse(text: String): KeySet {
            val map = HashMap<String, ByteArray>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val name = line.substring(0, eq).trim().lowercase()
                val value = line.substring(eq + 1).trim()
                val bytes = hexToBytes(value) ?: continue
                map[name] = bytes
            }
            return KeySet(map)
        }

        private fun hexToBytes(s: String): ByteArray? {
            if (s.isEmpty() || s.length % 2 != 0) return null
            val out = ByteArray(s.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(s[2 * i], 16)
                val lo = Character.digit(s[2 * i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

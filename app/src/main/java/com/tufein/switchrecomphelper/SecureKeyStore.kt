package com.tufein.switchrecomphelper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * `prod.keys` in Keystore-encrypted app storage. A plain-text `prod.keys` in filesDir (written by
 * versions before 0.7.0) is migrated on first load and then deleted, so the keys only exist
 * encrypted at rest.
 */
object SecureKeyStore {
    private const val PREFS_NAME = "secure_keys"
    private const val KEY_PROD_KEYS = "prod_keys"
    private const val LEGACY_FILE = "prod.keys"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(context: Context): String? =
        runCatching { prefs(context).getString(KEY_PROD_KEYS, null) }.getOrNull()

    fun save(context: Context, text: String) {
        prefs(context).edit().putString(KEY_PROD_KEYS, text).apply()
        deleteLegacyFile(context)
    }

    /** The stored keys if present, otherwise a legacy plain-text file, migrated and deleted. */
    fun loadOrMigrate(context: Context): String? {
        load(context)?.let { stored ->
            deleteLegacyFile(context)
            return stored
        }
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (!legacy.isFile) return null
        val text = runCatching { legacy.readText() }.getOrNull() ?: return null
        runCatching {
            save(context, text)
            legacy.delete()
        }
        return text
    }

    private fun deleteLegacyFile(context: Context) {
        runCatching { File(context.filesDir, LEGACY_FILE).delete() }
    }
}

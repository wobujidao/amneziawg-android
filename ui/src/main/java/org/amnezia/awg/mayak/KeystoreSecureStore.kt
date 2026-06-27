// Android-реализация SecureStore (:core): секреты (токен сессии, приватный ключ) в
// EncryptedSharedPreferences с мастер-ключом из Android Keystore (AES-256-GCM). Так токен и ключ
// не лежат в открытом виде (ADR-0004).
package org.amnezia.awg.mayak

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.amnezia.awg.mayak.core.SecureStore

// TODO(tech-debt): androidx.security.crypto (EncryptedSharedPreferences/MasterKey) депрекейтнут
//   Google (апр-2025). Мигрировать на Android Keystore напрямую (AES-256-GCM) или datastore-tink.
//   Отдельная задача — НЕ в рамках D1 (кэш конфига). См. docs/research/2026-06-27-android-encrypted-config-cache.md.

class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "mayak_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

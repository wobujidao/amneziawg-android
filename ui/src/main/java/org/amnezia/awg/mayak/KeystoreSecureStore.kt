// Android-реализация SecureStore (:core): секреты (токен сессии, приватный ключ) в
// EncryptedSharedPreferences с мастер-ключом из Android Keystore (AES-256-GCM). Так токен и ключ
// не лежат в открытом виде (ADR-0004).
package org.amnezia.awg.mayak

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.amnezia.awg.mayak.core.SecureStore
import java.security.KeyStore

// TODO(tech-debt): androidx.security.crypto (EncryptedSharedPreferences/MasterKey) депрекейтнут
//   Google (апр-2025). Мигрировать на Android Keystore напрямую (AES-256-GCM) или datastore-tink.
//   Отдельная задача — НЕ в рамках D1 (кэш конфига). См. docs/research/2026-06-27-android-encrypted-config-cache.md.

private const val PREFS_NAME = "mayak_secure"
// Дефолтный alias мастер-ключа androidx.security (MasterKey.DEFAULT_MASTER_KEY_ALIAS).
private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs: SharedPreferences = openPrefs(context.applicationContext)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        fun create(ctx: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        // Открываем зашифрованные prefs, а при повреждённом keystore/prefs (частый кейс — миграция/restore
        // устройства: alias мастер-ключа не совпадает → create() бросает) НЕ роняем приложение на старте,
        // а чистим prefs + мастер-ключ и пересоздаём. Секреты при этом теряются → потребуется повторный вход.
        fun openPrefs(ctx: Context): SharedPreferences =
            try {
                create(ctx)
            } catch (e: Exception) {
                runCatching { ctx.deleteSharedPreferences(PREFS_NAME) }
                runCatching {
                    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    if (ks.containsAlias(MASTER_KEY_ALIAS)) ks.deleteEntry(MASTER_KEY_ALIAS)
                }
                create(ctx) // чистый мастер-ключ + пустые prefs
            }
    }
}

// Android-реализация HwidProvider (:core): стабильный идентификатор «железа», который ПЕРЕЖИВАЕТ
// переустановку приложения, чтобы ядро узнавало то же устройство и не считало переустановку новым
// устройством (иначе ложно бьёт лимит устройств).
//
// Источник — Settings.Secure.ANDROID_ID: стабилен на (устройство + ключ подписи приложения) и
// переживает переустановку с Android 8. НЕ отправляем сырой ANDROID_ID наружу (приватность) — шлём
// SHA-256(ANDROID_ID + фиксированная соль). Редкий случай пустого/невалидного ANDROID_ID
// (некоторые эмуляторы/прошивки) — фолбэк на однажды сгенерированное и сохранённое в SecureStore
// значение (оно НЕ переживёт переустановку, но это крайний случай; основной путь — ANDROID_ID).
package org.amnezia.awg.mayak

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import org.amnezia.awg.mayak.core.HwidProvider
import org.amnezia.awg.mayak.core.SecureStore
import java.security.MessageDigest

class AndroidHwidProvider(
    context: Context,
    private val store: SecureStore,
) : HwidProvider {
    private val appContext = context.applicationContext

    @SuppressLint("HardwareIds")
    override fun hwid(): String {
        // 1) Кэш в SecureStore (быстрый путь + стабильность в рамках установки).
        store.get(KEY_HWID)?.takeIf { it.isNotBlank() }?.let { return it }

        // 2) Основной путь: ANDROID_ID (переживает переустановку). Хешируем с солью.
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } // известный «битый» id

        val hwid = if (androidId != null) {
            sha256Hex(SALT + androidId)
        } else {
            // 3) Фолбэк: случайный id (НЕ переживёт переустановку — крайний случай).
            sha256Hex(SALT + "fallback:" + java.util.UUID.randomUUID().toString())
        }
        store.put(KEY_HWID, hwid)
        return hwid
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_HWID = "hwid"
        // Фиксированная соль приложения «Маяк» (не секрет; разводит наш хеш от чужих хешей ANDROID_ID).
        private const val SALT = "mayak-vpn-hwid-v1:"
    }
}

// Android-реализация SecureStore (:core): секреты (токен сессии, приватный ключ) шифруются ключом из
// Android Keystore (AES-256-GCM) и лежат в обычных SharedPreferences уже в зашифрованном виде (ADR-0004).
//
// ПОЧЕМУ НЕ EncryptedSharedPreferences. Раньше здесь была androidx.security.crypto — Google
// депрекейтнул её в апреле 2025 и развития у неё нет (библиотека так и осталась в alpha). Замена
// делает ровно то же самое, но своими руками и без внешней зависимости: ключ живёт в аппаратном
// хранилище телефона, наружу не извлекается, значение хранится как base64(iv‖ciphertext).
//
// 🔴 ГЛАВНОЕ ЗДЕСЬ — ПЕРЕЕЗД, А НЕ ШИФР. В старом хранилище лежит токен сессии живых людей. Молча
// сменить формат = разлогинить всех, кто обновится, причём выглядело бы это как «приложение забыло
// меня». Поэтому при первом запуске новой сборки мы читаем старое хранилище и переносим значения в
// новое; старое чистим только после успешного переноса. Проверять это надо ОБНОВЛЕНИЕМ поверх
// предыдущей сборки, а не установкой с нуля — с нуля переносить нечего и дефект не виден.
package org.amnezia.awg.mayak

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.amnezia.awg.mayak.core.SecureStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val PREFS_NAME = "mayak_secure_v2"
private const val LEGACY_PREFS_NAME = "mayak_secure"
private const val KEY_ALIAS = "mayak_secure_key"
// Дефолтный alias мастер-ключа androidx.security (MasterKey.DEFAULT_MASTER_KEY_ALIAS) — старое хранилище.
private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12

// Тег «AmneziaWG/*» — только такие строки попадают в присланный диаг-лог (SPEC-0012). Секреты сюда
// не пишем НИКОГДА: только факт «переехало N значений» и причины отказов.
private const val TAG = "AmneziaWG/mayak-store"

class KeystoreSecureStore(context: Context) : SecureStore {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Перенос НИКОГДА не должен ронять запуск: хранилище создаётся в onCreate главного экрана,
        // и любое исключение отсюда — это «приложение не открывается вообще». Проверено на живом
        // обновлении: первая версия переноса определяла старый файл через getDatabasePath("../…") и
        // падала на IllegalArgumentException «contains a path separator» ещё до единой строки логики.
        runCatching { migrateFromLegacy(app, prefs) }
            .onFailure { Log.w(TAG, "перенос старого хранилища не состоялся: ${it.javaClass.simpleName}") }
    }

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            // Расшифровать нечем (ключ потерян при восстановлении телефона на другой аппарат) —
            // значение бесполезно. Отдаём null: вызывающий поймёт это как «не вошёл» и спросит вход.
            Log.w(TAG, "значение не расшифровано (${e.javaClass.simpleName}) — считаем, что его нет")
            null
        }
    }

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {

        /** Ключ шифрования из аппаратного хранилища; при первом обращении создаётся. Не извлекаем. */
        fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Экран блокировки НЕ требуем: токен нужен фоновым задачам (продление аренды,
                    // телеметрия) и автоподключению при загрузке телефона — с требованием
                    // аутентификации они бы просто не работали. Блокировка самого приложения —
                    // отдельная настройка (KEY_APP_LOCK), она про UI, а не про хранилище.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return gen.generateKey()
        }

        fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // iv‖ciphertext одной строкой: свой IV у каждого значения (переиспользовать нельзя).
            return Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }

        fun decrypt(stored: String): String {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            require(raw.size > GCM_IV_BYTES) { "слишком короткое значение" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_BYTES),
            )
            return String(cipher.doFinal(raw, GCM_IV_BYTES, raw.size - GCM_IV_BYTES), Charsets.UTF_8)
        }

        /**
         * Перенос значений из старого EncryptedSharedPreferences. Делается один раз: как только в
         * новом хранилище что-то есть, старое больше не открываем.
         *
         * Всё в runCatching намеренно: старое хранилище умеет бросать на восстановленном из бэкапа
         * телефоне (мастер-ключ не совпадает). Провал переноса = человек войдёт заново — неприятно,
         * но это ровно то поведение, которое было и раньше в том же случае. Ронять запуск нельзя.
         */
        fun migrateFromLegacy(ctx: Context, target: SharedPreferences) {
            if (target.all.isNotEmpty()) return
            // Есть ли что переносить, спрашиваем у САМИХ prefs, а не у файловой системы: путь к
            // shared_prefs — деталь реализации Android, а getDatabasePath("../…") вообще запрещён.
            // Читаем сырой файл (ключи и значения там зашифрованы — расшифровка ниже, через EncryptedSharedPreferences).
            if (ctx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).all.isEmpty()) return
            runCatching {
                val masterKey = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val legacy = EncryptedSharedPreferences.create(
                    ctx,
                    LEGACY_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                var moved = 0
                val edit = target.edit()
                for ((k, v) in legacy.all) {
                    if (v is String) {
                        edit.putString(k, encrypt(v))
                        moved++
                    }
                }
                edit.apply()
                Log.i(TAG, "перенесено значений из старого хранилища: $moved")
                // Чистим старое ТОЛЬКО после успешного переноса: иначе потеряли бы и то, и другое.
                legacy.edit().clear().apply()
                runCatching { ctx.deleteSharedPreferences(LEGACY_PREFS_NAME) }
                runCatching {
                    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    if (ks.containsAlias(LEGACY_MASTER_KEY_ALIAS)) ks.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
                }
            }.onFailure {
                Log.w(TAG, "старое хранилище не открылось (${it.javaClass.simpleName}) — потребуется повторный вход")
                runCatching { ctx.deleteSharedPreferences(LEGACY_PREFS_NAME) }
            }
        }
    }
}

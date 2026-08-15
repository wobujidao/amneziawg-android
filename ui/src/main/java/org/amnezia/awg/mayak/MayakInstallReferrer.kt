// Код приглашения из установочной метки Play (SPEC-0049).
//
// 🔴 ЧЕСТНАЯ ГРАНИЦА, которую надо помнить всем, кто сюда придёт. Код доезжает от ссылки-приглашения
// до приложения САМ только при установке ИЗ МАГАЗИНА: ссылка ведёт на лендинг, лендинг подставляет
// код в адрес Play (`&referrer=ref%3DКОД`), Play сохраняет его вместе с установкой, а мы читаем при
// первом запуске. При установке APK с нашего сайта такого канала нет ВООБЩЕ — файл одинаковый для
// всех, положить в него код нечего. Поэтому поле кода на регистрации остаётся ручным, а рядом с ним
// живёт кнопка «Вставить»: лендинг кладёт код в буфер обмена, когда человек жмёт «Скачать».
//
// Спрашиваем метку РОВНО ОДИН РАЗ за жизнь установки: Play отдаёт её и через месяц, но человек к
// тому времени давно зарегистрировался, а лишнее соединение с магазином на каждом запуске нам ни к
// чему. Признак «уже спрашивали» лежит в тех же настройках, что и остальные флаги приложения.
package org.amnezia.awg.mayak

import android.content.Context
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.net.URLDecoder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.awg.mayak.core.looksLikeReferralCode
import org.amnezia.awg.mayak.core.normalizeReferralCode

object MayakInstallReferrer {

    private const val TAG = "MayakInstallReferrer"

    /** Свой файл настроек, как у MayakLatency: чужой (`mayak_ui_prefs`) не трогаем. */
    private const val PREFS = "mayak_install_referrer"

    /** Ключ настройки: метку уже спрашивали (независимо от того, был там код или нет). */
    private const val KEY_ASKED = "mayak_install_referrer_asked"

    /** Ключ настройки: код, вынутый из метки, до того как он применён. */
    private const val KEY_CODE = "mayak_install_referrer_code"

    /**
     * Сколько ждём ответа магазина. Соединение с Play — межпроцессное, и на холодном старте оно
     * иногда думает; но экран регистрации ждать человека не заставит: не ответили за это время —
     * поле останется пустым, и код вводится руками.
     */
    private const val TIMEOUT_MS = 1500L

    /**
     * Достать код приглашения: сперва из уже сохранённого, иначе — один раз спросить Play.
     *
     * Возвращает пустую строку, если кода нет (обычный случай: ставили не из магазина или пришли
     * не по ссылке). Никогда не бросает: отсутствие магазина — норма, а не сбой.
     */
    suspend fun code(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_CODE, null)?.takeIf { it.isNotBlank() }?.let { return it }
        if (prefs.getBoolean(KEY_ASKED, false)) return ""

        val raw = withTimeoutOrNull(TIMEOUT_MS) { fetchReferrer(context) }.orEmpty()
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        val code = parseCode(raw)
        if (code.isNotEmpty()) prefs.edit().putString(KEY_CODE, code).apply()
        return code
    }

    /** Забыть сохранённый код: зовётся, когда он применён или отвергнут сервером насовсем. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CODE).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Вынуть код из строки метки. Play отдаёт её как есть — то, что мы сами положили в адрес:
     * `ref=NP5EM3D2`, иногда вперемешку с чужими полями (`utm_source=...&ref=...`).
     *
     * Всё, что не похоже на наш код, отбрасываем молча: метка приходит из внешнего мира, и верить
     * ей нельзя — сервер всё равно проверит код по-настоящему.
     */
    fun parseCode(referrer: String): String {
        if (referrer.isBlank()) return ""
        // Разбираем руками, а не через Uri: android.net.Uri в JVM-тестах — заглушка, и сторож этой
        // функции (ReferrerParseTest) молча тестировал бы пустоту.
        val value = referrer.split('&')
            .firstOrNull { it.substringBefore('=').trim() == "ref" }
            ?.substringAfter('=', "")
            ?.let { raw -> runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) }
            ?: return ""
        val code = normalizeReferralCode(value)
        return if (looksLikeReferralCode(code)) code else ""
    }

    /** Один разговор с магазином. Ошибки глотаем: магазина может не быть вовсе. */
    private suspend fun fetchReferrer(context: Context): String = suspendCancellableCoroutine { cont ->
        val client = runCatching { InstallReferrerClient.newBuilder(context).build() }.getOrElse {
            cont.resume("")
            return@suspendCancellableCoroutine
        }
        // Клиент нельзя закрывать дважды и нельзя отвечать в корутину дважды — Play зовёт колбэк
        // повторно при обрыве соединения.
        var done = false
        fun finish(value: String) {
            if (done) return
            done = true
            runCatching { client.endConnection() }
            if (cont.isActive) cont.resume(value)
        }
        cont.invokeOnCancellation { finish("") }
        runCatching {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
                        finish("")
                        return
                    }
                    val value = runCatching { client.installReferrer.installReferrer }.getOrElse {
                        Log.d(TAG, "метка не прочиталась: ${it.javaClass.simpleName}")
                        ""
                    }
                    finish(value.orEmpty())
                }

                override fun onInstallReferrerServiceDisconnected() = finish("")
            })
        }.onFailure { finish("") }
    }
}

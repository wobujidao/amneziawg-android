// Разбор ответов «Пригласи друга» (SPEC-0049) — отдельно от экрана, как SupportOutcome.
//
// Зачем отдельный файл: причин отказа у одного HTTP-статуса несколько (под 409 их четыре), и
// человеку в каждом случае надо сказать РАЗНОЕ — «это ваш код», «код уже применён», «окно
// закрылось», «программа выключена». Разложить это в Activity значит переписать разбор ещё раз в
// следующем экране, где код тоже вводится (регистрация), и разойтись с этим.
package org.amnezia.awg.mayak.core

import java.io.IOException

/** Алфавит кода приглашения — тот же, что на ядре: без похожих символов (нет 0/O, 1/I/L). */
private const val REFERRAL_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

/** Длина кода. Ядро принимает только ровно столько символов после чистки. */
const val REFERRAL_CODE_LEN = 8

/**
 * Привести код к канону: верхний регистр, без пробелов и дефисов.
 *
 * Тот же канон, что у ядра (`NormalizeReferralCode`): код диктуют голосом и перепечатывают с чужого
 * экрана, поэтому «np5em-3d2» и «NP5EM3D2» обязаны быть одним кодом. Чистим И на клиенте, чтобы не
 * гонять заведомо мусорный запрос, но правда — на сервере: клиент не решает, существует ли код.
 */
fun normalizeReferralCode(raw: String): String =
    raw.trim().uppercase().filter { it != ' ' && it != '-' }

/** Похоже ли введённое на наш код — только чтобы включить кнопку, а не чтобы судить о коде. */
fun looksLikeReferralCode(raw: String): Boolean {
    val c = normalizeReferralCode(raw)
    return c.length == REFERRAL_CODE_LEN && c.all { REFERRAL_ALPHABET.contains(it) }
}

/** Почему код не применился. Каждая ветка = свой текст человеку и своё следующее действие. */
enum class ReferralFailure {
    /** Программа выключена в панели — раздел вообще не про этого человека сейчас. */
    DISABLED,

    /** Такого кода нет. Самая частая беда — опечатка, поэтому просим проверить написание. */
    NOT_FOUND,

    /** Свой собственный код. */
    OWN_CODE,

    /** К этой учётке приглашение уже применяли — пригласить можно один раз в жизни. */
    ALREADY_INVITED,

    /** Окно после регистрации закрылось. Повторять бессмысленно, и это надо сказать прямо. */
    WINDOW_CLOSED,

    /** Пустой код или заведомо не наш — до сети не дошли. */
    EMPTY_OR_MALFORMED,

    /** Нужен вход заново (401). */
    NEED_LOGIN,

    /** Слишком часто (429) — у ручки свой лимит на подбор кодов. */
    RATE_LIMITED,

    /** Сети нет / ни один домен не ответил. */
    NO_CONNECTION,

    /** Сервер сейчас не смог — повтор осмыслен. */
    RETRY_LATER,

    /** Причина неизвестна: показываем текст ядра как есть и НЕ выдумываем действие. */
    UNKNOWN,
}

/**
 * Разбор отказа по паре (HTTP-статус, машинный `code`).
 *
 * status = 0 — HTTP-ответа не было (сетевая ошибка). Незнакомая пара даёт [ReferralFailure.UNKNOWN]:
 * соврать про причину хуже, чем признать, что она нам неизвестна.
 */
fun referralFailure(status: Int, code: String): ReferralFailure = when {
    status == 0 -> ReferralFailure.NO_CONNECTION
    status == 401 -> ReferralFailure.NEED_LOGIN
    status == 429 -> ReferralFailure.RATE_LIMITED
    code == "referral_disabled" -> ReferralFailure.DISABLED
    code == "referral_code_not_found" -> ReferralFailure.NOT_FOUND
    code == "referral_own_code" -> ReferralFailure.OWN_CODE
    code == "referral_already_invited" -> ReferralFailure.ALREADY_INVITED
    code == "referral_window_closed" -> ReferralFailure.WINDOW_CLOSED
    status == 400 -> ReferralFailure.EMPTY_OR_MALFORMED
    status >= 500 -> ReferralFailure.RETRY_LATER
    else -> ReferralFailure.UNKNOWN
}

/** То же по исключению клиента: ответ ядра разбираем по `code`, сетевой отказ — как «нет связи». */
fun referralFailure(e: Throwable): ReferralFailure = when (e) {
    is MayakApiException -> referralFailure(e.status, e.code)
    is IOException -> ReferralFailure.NO_CONNECTION
    else -> ReferralFailure.UNKNOWN
}

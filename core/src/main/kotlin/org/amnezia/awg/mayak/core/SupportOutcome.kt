// Что приложение показывает человеку по ОТВЕТУ ядра на обращение в поддержку.
//
// Зачем отдельный файл, а не `when` внутри экрана. У формы обращения девять разных исходов, и семь из
// них приходят под статусами 400/429/503, которые сами по себе не говорят, что человеку делать:
// «тема разъехалась» и «текст короткий» — оба 400, «канал не настроен» и «письмо не ушло» — оба 503.
// Правило проекта на этот счёт прямое: ветвиться по машинному полю `code`, а не по HTTP-коду
// (internal/clientapi: writeErrCode). Внутри Activity это решение проверяемо только эмулятором —
// именно поэтому оно живёт здесь, чистой функцией на JVM, как [accessDenial].
//
// 🔴 Fail-safe у ТЕМЫ. Белый список тем живёт в ядре (`supportTopics` в internal/clientapi/support.go),
// а копии — в кабинете (`<option>`) и теперь в приложении. Разъедутся — человек выберет тему из
// списка и получит «выберите тему обращения из списка»: отказ, который он НЕ МОЖЕТ исправить никаким
// своим действием, потому что других тем у него на экране нет. У кабинета от этого есть сторож (он
// читает разметку рядом), у приложения в чужом репозитории такого сторожа быть не может. Поэтому
// клиент не спорит, а переотправляет тот же текст темой «Другое» ([supportResendAsOther]): человек
// проходит, текст не теряется, а классификация в панели портится ровно на одно обращение.
package org.amnezia.awg.mayak.core

import java.io.IOException

/**
 * Темы обращения — КОДЫ, которые понимает ядро (`supportTopics` в internal/clientapi/support.go).
 *
 * Здесь только коды: названия тем человеку показывает :ui из ресурсов (их надо переводить, а :core —
 * чистый Kotlin без Android). Порядок = порядок на экране, «Другое» последним.
 */
object SupportTopics {
    /** Тема-приют: ею уходит обращение, если ядро не знает выбранной (см. [supportResendAsOther]). */
    const val OTHER = "other"

    val CODES: List<String> = listOf("connect", "speed", "payment", "account", "app", OTHER)
}

/**
 * Потолки текста обращения — ТЕ ЖЕ, что у ядра (`minSupportMessage`/`maxSupportMessage`).
 *
 * Зачем копия на клиенте, если решает всё равно сервер. Чтобы «слишком коротко» человек узнал
 * мгновенно, а не через круг по сети и отказ (в РФ-сотовой это несколько секунд), и чтобы счётчик
 * под полем считал то же самое, что потом проверит ядро. Сервер остаётся ПОСЛЕДНЕЙ инстанцией:
 * разъедутся числа — победит его отказ, и человек увидит его текст, а не наш.
 */
object SupportLimits {
    const val MIN_CHARS = 10
    const val MAX_CHARS = 4000

    /**
     * Длина в РУНАХ, как считает ядро (`len([]rune(msg))`), а не в UTF-16-единицах Kotlin: эмодзи и
     * редкие иероглифы — это ДВА units и ОДНА руна, и на них наш счётчик разошёлся бы с серверным.
     */
    fun runes(s: String): Int = if (s.isEmpty()) 0 else s.codePointCount(0, s.length)

    /**
     * Что не так с ПЕРВЫМ сообщением обращения ещё до отправки; null — можно отправлять.
     * Текст обязан быть уже обрезан по краям (как это делает ядро через strings.TrimSpace).
     */
    fun firstMessageProblem(trimmed: String): SupportFailure? = when {
        runes(trimmed) < MIN_CHARS -> SupportFailure.TOO_SHORT
        runes(trimmed) > MAX_CHARS -> SupportFailure.TOO_LONG
        else -> null
    }

    /**
     * То же для ДОПОЛНЕНИЯ к нитке. Нижнего порога здесь нет намеренно — «да», «нет», «помогло»
     * внутри уже идущего разговора полноценные ответы на наш же вопрос (так решает и ядро).
     */
    fun replyProblem(trimmed: String): SupportFailure? = when {
        trimmed.isEmpty() -> SupportFailure.TOO_SHORT
        runes(trimmed) > MAX_CHARS -> SupportFailure.TOO_LONG
        else -> null
    }
}

/** Почему обращение (или его чтение) не удалось — в терминах ДЕЙСТВИЯ человека, а не HTTP. */
enum class SupportFailure {
    /** Ядро не знает выбранной темы: списки разъехались. Лечится переотправкой темой «Другое». */
    TOPIC_REJECTED,

    /** Текст короче 10 знаков — ядру нечего передать поддержке, кроме «уточните». */
    TOO_SHORT,

    /** Текст длиннее 4000 знаков. */
    TOO_LONG,

    /** Исчерпан лимит обращений (5/ч на аккаунт либо 20/ч на адрес). Сколько ждать — Retry-After. */
    RATE_LIMITED,

    /** Канала отправки у ядра нет вовсе (503 `support_unavailable`) — показываем ЗАПАСНОЙ путь письмом. */
    CHANNEL_OFF,

    /** Наша сторона не смогла сейчас (письмо не ушло, перегрузка, 5xx). Тот же текст, кнопка «Повторить». */
    RETRY_LATER,

    /** Сессия недействительна (401): форма требует входа. */
    NEED_LOGIN,

    /** Обращения нет или оно чужое (404 `not_found`) — ядро НАРОЧНО не различает эти два случая. */
    NOT_FOUND,

    /** Ответа не было вообще: нет сети или ни один домен ядра не отвечает. */
    NO_CONNECTION,

    /** Причина нам неизвестна — показываем текст ядра как есть, действие не подсказываем. */
    UNKNOWN,
}

/**
 * Разбор отказа: HTTP-код + машинный признак `code` из тела → что показать.
 *
 * status = 0 означает «HTTP-ответа не было» (сетевая ошибка) — см. перегрузку по [Throwable].
 *
 * Fail-safe: незнакомая пара (status, code) даёт [SupportFailure.UNKNOWN], а не выдуманную причину.
 * Экран в этом случае печатает текст ядра и НЕ подсказывает действие: соврать человеку про причину
 * хуже, чем признаться, что причина нам неизвестна.
 */
fun supportFailure(status: Int, code: String): SupportFailure = when {
    status == 0 -> SupportFailure.NO_CONNECTION
    status == 401 -> SupportFailure.NEED_LOGIN
    status == 404 -> SupportFailure.NOT_FOUND
    status == 429 -> SupportFailure.RATE_LIMITED // и `support_rate_limited` (аккаунт), и `rate_limited` (IP)
    status == 400 -> when (code) {
        "unknown_topic" -> SupportFailure.TOPIC_REJECTED
        "message_too_short" -> SupportFailure.TOO_SHORT
        // Пустое дополнение к нитке — та же беда, что короткий текст, и лечится тем же действием.
        "message_empty" -> SupportFailure.TOO_SHORT
        "message_too_long" -> SupportFailure.TOO_LONG
        else -> SupportFailure.UNKNOWN
    }
    // `support_unavailable` — канала нет НАСОВСЕМ (не настроен отправитель или адрес), повтор не
    // поможет; `send_failed`/`overloaded`/5xx — «сейчас не вышло», повтор осмыслен. Один статус, два
    // разных совета человеку — ровно тот случай, ради которого ядро и отдаёт `code`.
    code == "support_unavailable" -> SupportFailure.CHANNEL_OFF
    status >= 500 -> SupportFailure.RETRY_LATER
    else -> SupportFailure.UNKNOWN
}

/** То же по исключению клиента: ответ ядра разбираем по `code`, сетевой отказ — как «нет связи». */
fun supportFailure(e: Throwable): SupportFailure = when (e) {
    is MayakApiException -> supportFailure(e.status, e.code)
    // NoReachableHostException — тоже IOException: ни один домен не ответил.
    is IOException -> SupportFailure.NO_CONNECTION
    else -> SupportFailure.UNKNOWN
}

/**
 * Можно ли предлагать «Повторить» ТЕМ ЖЕ текстом.
 *
 * Нельзя там, где повтор гарантированно даст тот же отказ: правку должен сделать человек (короткий/
 * длинный текст), либо ждать (лимит), либо канала нет вовсе, либо надо войти. Кнопка «Повторить»,
 * которая всегда возвращает одну и ту же ошибку, — это тупик с иллюзией действия.
 */
val SupportFailure.canRetrySameText: Boolean
    get() = when (this) {
        SupportFailure.RETRY_LATER, SupportFailure.NO_CONNECTION, SupportFailure.UNKNOWN,
        SupportFailure.TOPIC_REJECTED -> true

        SupportFailure.TOO_SHORT, SupportFailure.TOO_LONG, SupportFailure.RATE_LIMITED,
        SupportFailure.CHANNEL_OFF, SupportFailure.NEED_LOGIN, SupportFailure.NOT_FOUND -> false
    }

/**
 * Переотправить ли обращение темой «Другое», не спрашивая человека (см. fail-safe в шапке файла).
 * Только для [SupportFailure.TOPIC_REJECTED] и только ОДИН раз — иначе пара «клиент против ядра»
 * закрутилась бы в цикл, если ядро не знает и «Другое».
 */
fun supportResendAsOther(f: SupportFailure): Boolean = f == SupportFailure.TOPIC_REJECTED

/**
 * Через сколько МИНУТ можно снова, из заголовка Retry-After (секунды).
 *
 * Округляем ВВЕРХ и не ниже одной минуты: сказать «через 0 минут» и снова получить отказ — это
 * обещание, которое мы не держим. 0 на выходе значит только одно: ядро не сказало, сколько ждать
 * (тогда экран говорит «попробуйте позже», без выдуманного числа).
 */
fun retryAfterMinutes(seconds: Int): Int = when {
    seconds <= 0 -> 0
    else -> (seconds + 59) / 60
}

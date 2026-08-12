// Регистрация ПРЯМО В ПРИЛОЖЕНИИ (SPEC-0048): правила, которые можно проверить без Android.
//
// Здесь живёт ровно то, что определяет судьбу человека на самом хрупком шаге знакомства: годится ли
// придуманный им пароль и можно ли вообще нажимать кнопку. Экран (MayakRegisterActivity) только
// показывает результат этих решений — потому что решения надо накрывать тестами, а экран нечем.
package org.amnezia.awg.mayak.core

import java.security.SecureRandom

/**
 * Политика пароля — КОПИЯ серверной (`internal/accounts.ValidatePassword` в ядре).
 *
 * 🔴 Зачем копия, если сервер всё равно проверит. Затем, что отказ `weak_password` приезжает ПОСЛЕ
 * запроса: человек нажал «Создать аккаунт», подождал, прошёл проверку «не робот» — и получил
 * «пароль слишком простой», потеряв одноразовый токен капчи. Проверка на месте отвечает мгновенно и
 * НИКОГДА не должна расходиться с серверной: за этим следит таблица-тест `PasswordPolicyTest`,
 * дословно повторяющая примеры из `internal/accounts/validate_test.go`.
 *
 * Правило сервера словами: длина 8..200 БАЙТ (Go считает `len(pw)`, то есть UTF-8) и хотя бы ДВЕ
 * категории символов из трёх — буква / цифра / всё остальное. Отдельная серверная проверка «строка
 * из одного повторяющегося символа» здесь не нужна: одинаковые символы дают ровно одну категорию,
 * то есть такая строка отсекается предыдущим правилом (и на сервере она тоже недостижима).
 */
object PasswordPolicy {

    /** Минимум и максимум — в БАЙТАХ UTF-8, как считает сервер. */
    const val MIN_BYTES = 8
    const val MAX_BYTES = 200

    /** Длина пароля, который приложение придумывает само. 24 — с большим запасом от подбора. */
    const val GENERATED_LENGTH = 24

    /**
     * Алфавит генератора: БЕЗ похожих друг на друга знаков (0/O/o, 1/l/I) — этот пароль человек
     * будет переписывать на бумагу и вводить руками на втором телефоне, и «l или 1?» там дороже,
     * чем два лишних бита энтропии. Символов-разделителей тоже нет: часть мобильных клавиатур
     * прячет их на третий экран.
     */
    private const val LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"

    /** Пройдёт ли пароль серверную проверку. false → показываем словами У ПОЛЯ, запрос не шлём. */
    fun isStrong(password: String): Boolean {
        val bytes = password.toByteArray(Charsets.UTF_8).size
        if (bytes < MIN_BYTES || bytes > MAX_BYTES) return false
        var letter = false
        var digit = false
        var other = false
        for (c in password) {
            when {
                c.isLetter() -> letter = true
                c.isDigit() -> digit = true
                else -> other = true
            }
        }
        var categories = 0
        if (letter) categories++
        if (digit) categories++
        if (other) categories++
        return categories >= 2
    }

    /**
     * Придумать пароль. Буква и цифра кладутся в него ЯВНО, а не «как получится»: случайные 24
     * символа из этого алфавита в паре процентов случаев оказываются без единой цифры, и такой
     * пароль наш же экран отверг бы как слабый — то есть кнопка «Придумать пароль» иногда выдавала
     * бы негодный результат. Порядок перемешиваем тем же криптографическим источником.
     */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val all = LETTERS + DIGITS
        val out = ArrayList<Char>(GENERATED_LENGTH)
        out.add(LETTERS[random.nextInt(LETTERS.length)])
        out.add(DIGITS[random.nextInt(DIGITS.length)])
        while (out.size < GENERATED_LENGTH) out.add(all[random.nextInt(all.length)])
        java.util.Collections.shuffle(out, random)
        return out.joinToString("")
    }
}

/** Правила самой формы регистрации: когда кнопка живая. */
object RegisterForm {

    /**
     * Можно ли отправлять форму.
     *
     * Согласие обязательно: `consent_required` — серверное правило, и соблюдать его надо НА ЭКРАНЕ,
     * а не ловить отказом после запроса. Пароль в это условие НЕ входит намеренно: мёртвая кнопка
     * без объяснения читается как «приложение сломалось», поэтому слабый пароль объясняется словами
     * у поля по нажатию (см. [PasswordPolicy.isStrong]).
     *
     * `requestInFlight` закрывает вторую беду — двойное нажатие: два запроса заведут ДВА аккаунта
     * (второй ещё и упрётся в одноразовый токен капчи), а человеку нужен один.
     */
    fun canSubmit(consentChecked: Boolean, requestInFlight: Boolean): Boolean =
        consentChecked && !requestInFlight
}

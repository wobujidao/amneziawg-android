// Строка про доступ («Доступ до 7 авг. 2026 г. · осталось 7 дней») — ОДНА на всё приложение.
//
// Аудит 2026-07-31, п. 11: новичок нигде на своём пути не узнавал, что у него пробные 7 дней.
// Единственным местом был самый низ «Настроек» — пять экранов прокрутки. Человек, у которого через
// неделю всё перестанет работать, об этом не предупреждён; уходит он молча.
//
// Текст собирается здесь, чтобы главный экран и настройки не разошлись в формулировках (ровно так
// админка и разошлась в цифрах на пяти экранах — аудит той же даты).
package org.amnezia.awg.mayak

import android.content.Context
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.AccountStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object MayakAccessLine {

    /** Обратный отсчёт имеет смысл только вблизи конца: «осталось 3652 дня» — шум. */
    private const val COUNTDOWN_FROM_DAYS = 30

    /** С какого остатка строка становится тревожной (красной) у ОПЛАЧЕННОГО доступа. */
    private const val WARN_FROM_DAYS = 3

    /**
     * С какого остатка тревожен ПРОБНЫЙ доступ — только последний день.
     *
     * Пробный без почты длится ровно три дня (решение владельца 16-08, `registration.anon_trial_days`),
     * а общий порог тревоги — тоже три. Значит человек, только что заведший учётку, видел на первом
     * же экране красную строку «осталось 3 дня» ещё до первого подключения (снято на эмуляторе
     * 16-08). Цвет, который горит всегда, ничего не сообщает; и «пробный кончается» — это не
     * поломка, а замысел. Тревога остаётся там, где она значит дело: последний день и всё, что
     * после.
     */
    private const val TRIAL_WARN_FROM_DAYS = 1

    /** [text] — что показать человеку, [alarming] — красить ли тревожно (кончается/кончился). */
    data class Line(val text: String, val alarming: Boolean)

    /**
     * @param withDevices добавить второй строкой «Устройства: N из M» (в настройках место есть,
     *        на главном экране это лишний шум — там показываем только срок).
     */
    fun of(ctx: Context, st: AccountStatus, withDevices: Boolean = false): Line {
        val until = st.validUntilMs()
        val days = st.daysLeft()
        val text = when {
            st.access == "none" -> ctx.getString(R.string.mayak_settings_subscription_none)
            st.access == "expired" -> ctx.getString(
                R.string.mayak_settings_subscription_expired,
                until?.let { formatDate(it) } ?: "",
            )
            until != null && days != null && days <= COUNTDOWN_FROM_DAYS -> ctx.getString(
                if (st.trial) R.string.mayak_settings_subscription_trial_until
                else R.string.mayak_settings_subscription_until,
                formatDate(until),
                ctx.resources.getQuantityString(R.plurals.mayak_days, days, days),
            )
            until != null -> ctx.getString(
                if (st.trial) R.string.mayak_settings_subscription_trial_until_plain
                else R.string.mayak_settings_subscription_until_plain,
                formatDate(until),
            )
            // Доступ без срока (выдан админом бессрочно) — «до какого числа» тут не существует.
            else -> ctx.getString(R.string.mayak_settings_subscription_active)
        }
        val devices = if (withDevices && st.deviceLimit > 0) {
            "\n" + ctx.getString(R.string.mayak_settings_devices_used, st.devicesUsed, st.deviceLimit)
        } else ""
        return Line(text + devices, alarming(st.access, days, st.trial))
    }

    /**
     * Красить ли строку тревожно. Вынесено из [of] отдельной ЧИСТОЙ функцией нарочно: решение про
     * цвет — это правило продукта, и проверять его надо тестом, а не глазами на эмуляторе (для
     * [of] нужен Context, а значит Robolectric, которого в проекте нет).
     */
    fun alarming(access: String, days: Int?, trial: Boolean): Boolean {
        val warnFrom = if (trial) TRIAL_WARN_FROM_DAYS else WARN_FROM_DAYS
        return access != "active" || (days != null && days <= warnFrom)
    }

    /** Дата в языке телефона («2 авг. 2026 г.»). Год оставляем: без него «до 2 авг.» двусмысленно. */
    private fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
}

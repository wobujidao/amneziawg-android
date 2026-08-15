// Приглашения на стороне экранов (SPEC-0049): причина отказа словами, деньги в рублях и буфер обмена.
//
// Разбор ответа живёт в :core (ReferralOutcome), тексты — здесь: код приглашения вводится в ДВУХ
// местах (регистрация и настройки), и одна и та же причина обязана звучать там одинаково.
package org.amnezia.awg.mayak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.ReferralFailure
import org.amnezia.awg.mayak.core.looksLikeReferralCode
import org.amnezia.awg.mayak.core.normalizeReferralCode

object MayakReferral {

    /** Причина отказа одной фразой — со строчной буквы: подставляется в «не применился: …». */
    fun reason(context: Context, failure: ReferralFailure): String = context.getString(
        when (failure) {
            ReferralFailure.DISABLED -> R.string.mayak_referral_err_disabled
            ReferralFailure.NOT_FOUND -> R.string.mayak_referral_err_not_found
            ReferralFailure.OWN_CODE -> R.string.mayak_referral_err_own
            ReferralFailure.ALREADY_INVITED -> R.string.mayak_referral_err_already
            ReferralFailure.WINDOW_CLOSED -> R.string.mayak_referral_err_window
            ReferralFailure.EMPTY_OR_MALFORMED -> R.string.mayak_referral_err_bad
            ReferralFailure.NEED_LOGIN -> R.string.mayak_referral_err_login
            ReferralFailure.RATE_LIMITED -> R.string.mayak_referral_err_rate
            ReferralFailure.NO_CONNECTION -> R.string.mayak_referral_err_network
            ReferralFailure.RETRY_LATER -> R.string.mayak_referral_err_retry
            ReferralFailure.UNKNOWN -> R.string.mayak_referral_err_unknown
        }
    )

    /**
     * Деньги человеку: копейки → «100 ₽».
     *
     * Копейки показываем ТОЛЬКО когда они есть (35,50 ₽) — «100,00 ₽» на экране выглядит как
     * бухгалтерия, а суммы у нас круглые. Знак рубля один и тот же в обоих языках: это валюта,
     * а не слово, и переводить её нечем.
     */
    fun money(kopecks: Long): String {
        val rub = kopecks / 100
        val kop = (kopecks % 100).toInt()
        return if (kop == 0) "$rub ₽" else String.format("%d,%02d ₽", rub, kop)
    }

    /** Достать код из буфера обмена (по нажатию человека). Пусто — если там не код. */
    fun codeFromClipboard(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
            ?.toString().orEmpty()
        // Лендинг кладёт в буфер и голый код, и целую ссылку-приглашение — принимаем оба.
        val fromLink = Regex("[?&]ref=([^&\\s]+)").find(text)?.groupValues?.getOrNull(1).orEmpty()
        val candidate = normalizeReferralCode(if (fromLink.isNotEmpty()) fromLink else text)
        return if (looksLikeReferralCode(candidate)) candidate else ""
    }

    /** Положить в буфер (свой код или ссылку) — с подписью, которую видно в системном превью. */
    fun copy(context: Context, label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
    }
}

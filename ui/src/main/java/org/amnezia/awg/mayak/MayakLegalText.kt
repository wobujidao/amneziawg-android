/*
 * Copyright © 2026 Mayak Networks. SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.mayak

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.amnezia.awg.R

/**
 * Строка «входя, вы принимаете Политику и Условия» — с ЖИВЫМИ ссылками на оба документа.
 *
 * Зачем отдельный файл: до 16-08 экран входа называл оба документа обычным текстом. Человек читал,
 * что он что-то принимает, и не мог это «что-то» открыть — обещание без исполнения, ровно тот класс
 * дефектов, ради которого заведена вычитка текстов. Кнопки, как на экране «О приложении», сюда не
 * годятся (две крупные кнопки под формой входа спорят с «Войти»), поэтому ссылки живут внутри строки.
 *
 * Строка собирается из формата и двух подписей, а не ищется подстрокой в готовом предложении:
 * названия документов склоняются («принимаете Политику конфиденциальности»), и поиск по
 * именительному падежу из `mayak_privacy_policy` не нашёл бы ничего именно в русской версии.
 */
object MayakLegalText {

    /**
     * Кладёт в [tv] строку согласия с двумя кликабельными названиями документов.
     *
     * Адреса берутся там же, где их берёт экран «О приложении» ([MayakHostList]), — то есть с
     * учётом выученного адреса кабинета, а не по зашитой константе.
     */
    fun bindConsentNote(context: Context, tv: TextView) {
        val privacy = context.getString(R.string.mayak_consent_link_privacy)
        val terms = context.getString(R.string.mayak_consent_link_terms)
        val text = context.getString(R.string.mayak_consent_note_fmt, privacy, terms)

        val sb = SpannableStringBuilder(text)
        linkify(context, sb, privacy) { MayakHostList.privacyUrl(context) }
        linkify(context, sb, terms) { MayakHostList.termsUrl(context) }

        tv.text = sb
        tv.movementMethod = LinkMovementMethod.getInstance()
        // Подсветка нажатия по умолчанию — жёлтая, на тёмном фоне выглядит как дефект.
        tv.highlightColor = ContextCompat.getColor(context, android.R.color.transparent)
    }

    private fun linkify(
        context: Context,
        sb: SpannableStringBuilder,
        label: String,
        url: () -> String,
    ) {
        val start = sb.indexOf(label)
        if (start < 0) return // формат и подписи разъехались — оставляем обычный текст, не падаем
        val span = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // runCatching: браузера может не быть вовсе (голая прошивка) — молчим, а не падаем.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url()))) }
            }
        }
        sb.setSpan(span, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(UnderlineSpan(), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

// Флаги стран для списка направлений. Приоритет — ВЕКТОРНЫЕ drawable (flag_ru/flag_nl/…):
// эмодзи-флаги на части прошивок РФ рендерятся серым «XX», вектор всегда рисуется корректно.
// Эмодзи оставлены лишь как запасной текст (там, где вектора пока нет).
package org.amnezia.awg.mayak

import androidx.annotation.DrawableRes
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.Direction

/** Подпись направления — человеческое имя, без нашего внутреннего кода: «Нидерланды», не
 *  «Нидерланды (nl)» (аудит 2026-07-31, п. 22: код направления лез и в список, и в подробности, и в
 *  шторку). Код остаётся ключом внутри — в интерфейсе он ничего не объясняет. Единый источник, чтобы
 *  уведомление показывало то же, что список (директива владельца 2026-07-02). */
fun Direction.displayLabel(): String = name.ifBlank { code }

/** Код для ФЛАГА направления: явный country_code (SPEC-0033), иначе — code направления. Так направление
 *  с произвольным кодом («almaty», «kz-adblock») показывает верный флаг, если задан country_code. */
fun Direction.flagCode(): String = countryCode.ifBlank { code }

object MayakFlags {
    // Векторные флаги по двухбуквенному коду (есть для имеющихся направлений).
    private val VECTORS: Map<String, Int> = mapOf(
        "ru" to R.drawable.flag_ru,
        "nl" to R.drawable.flag_nl,
        "pl" to R.drawable.flag_pl,
        "kz" to R.drawable.flag_kz,
        "de" to R.drawable.flag_de,
        "us" to R.drawable.flag_us,
        "gb" to R.drawable.flag_gb,
        "uk" to R.drawable.flag_gb, // альтернативный код Великобритании → тот же флаг
        "fr" to R.drawable.flag_fr,
        "fi" to R.drawable.flag_fi,
        "se" to R.drawable.flag_se,
        "by" to R.drawable.flag_by,
        "uz" to R.drawable.flag_uz,
    )

    // Эмодзи-фолбэк (только если нужен текст и нет вектора).
    private val EMOJI = mapOf(
        "ru" to "🇷🇺", "nl" to "🇳🇱", "pl" to "🇵🇱", "de" to "🇩🇪", "us" to "🇺🇸",
        "kz" to "🇰🇿", "by" to "🇧🇾", "uz" to "🇺🇿", "gb" to "🇬🇧",
        "uk" to "🇬🇧", "fr" to "🇫🇷", "fi" to "🇫🇮", "se" to "🇸🇪",
    )

    private fun two(code: String): String = code.trim().lowercase().take(2)

    /**
     * Показать флаг направления: эмодзи, если прошивка умеет его нарисовать, иначе векторная
     * картинка. Одно решение на все экраны — и это не украшательство.
     *
     * 10-08 владелец увидел в «Подробностях подключения» глобус вместо флага Польши, хотя в списке
     * стран флаг был правильный. Причина: список спрашивал эмодзи (а он собирается из ЛЮБЫХ двух
     * букв кода и потому работает для новой страны сам), подробности же брали только вектор — а
     * `flag_pl` завести забыли, когда 08-08 добавляли Польшу. Пока решение живёт в двух местах,
     * следующая страна повторит это ровно так же.
     */
    fun apply(image: android.widget.ImageView, emojiView: android.widget.TextView, code: String) {
        image.setImageResource(drawableForCode(code))
        val emoji = emojiForCode(code)
        if (emojiView.paint.hasGlyph(emoji)) {
            emojiView.text = emoji
            emojiView.visibility = android.view.View.VISIBLE
            image.visibility = android.view.View.GONE
        } else {
            emojiView.visibility = android.view.View.GONE
            image.visibility = android.view.View.VISIBLE
        }
    }

    /** Векторный флаг по коду направления; нейтральный «глобус», если вектора для кода нет. */
    @DrawableRes
    fun drawableForCode(code: String): Int = VECTORS[two(code)] ?: R.drawable.flag_globe

    /** Эмодзи-флаг (запасной вариант): из ISO-кода regional-indicator или 🌐. */
    fun emojiForCode(code: String): String {
        val c = two(code)
        EMOJI[c]?.let { return it }
        if (c.length == 2 && c[0] in 'a'..'z' && c[1] in 'a'..'z') {
            val sb = StringBuilder()
            for (ch in c) sb.appendCodePoint(0x1F1E6 + (ch - 'a'))
            return sb.toString()
        }
        return "🌐"
    }
}

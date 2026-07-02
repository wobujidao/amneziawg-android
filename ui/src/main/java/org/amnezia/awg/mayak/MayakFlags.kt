// Флаги стран для списка направлений. Приоритет — ВЕКТОРНЫЕ drawable (flag_ru/flag_nl/…):
// эмодзи-флаги на части прошивок РФ рендерятся серым «XX», вектор всегда рисуется корректно.
// Эмодзи оставлены лишь как запасной текст (там, где вектора пока нет).
package org.amnezia.awg.mayak

import androidx.annotation.DrawableRes
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.Direction

/** Подпись направления РОВНО как в списке приложения: "Нидерланды (nl)" (или name, если кода нет).
 *  Единый источник — чтобы уведомление показывало то же, что видно в списке (директива владельца 2026-07-02). */
fun Direction.displayLabel(): String = if (code.isNotBlank()) "$name ($code)" else name

object MayakFlags {
    // Векторные флаги по двухбуквенному коду (есть для имеющихся направлений).
    private val VECTORS: Map<String, Int> = mapOf(
        "ru" to R.drawable.flag_ru,
        "nl" to R.drawable.flag_nl,
    )

    // Эмодзи-фолбэк (только если нужен текст и нет вектора).
    private val EMOJI = mapOf(
        "ru" to "🇷🇺", "nl" to "🇳🇱", "de" to "🇩🇪", "us" to "🇺🇸",
        "kz" to "🇰🇿", "by" to "🇧🇾", "uz" to "🇺🇿", "gb" to "🇬🇧",
        "uk" to "🇬🇧", "fr" to "🇫🇷", "fi" to "🇫🇮", "se" to "🇸🇪",
    )

    private fun two(code: String): String = code.trim().lowercase().take(2)

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

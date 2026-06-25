// Флаг-эмодзи для строки-страны. Без ассетов: из ISO-кода страны собираем regional-indicator
// пары (🇷🇺 и т.п.). Код приходит от ядра (Direction.code), но он может быть не ISO
// (например «ru-ruvds»), поэтому берём первые две буквы и подстрахованы явной картой + фолбэком 🌐.
package org.amnezia.awg.mayak

object MayakFlags {
    // Явные соответствия для известных направлений (на случай не-ISO кодов ядра).
    private val EXPLICIT = mapOf(
        "ru" to "🇷🇺", "nl" to "🇳🇱", "de" to "🇩🇪", "us" to "🇺🇸",
        "kz" to "🇰🇿", "by" to "🇧🇾", "uz" to "🇺🇿", "gb" to "🇬🇧",
        "uk" to "🇬🇧", "fr" to "🇫🇷", "fi" to "🇫🇮", "se" to "🇸🇪",
    )

    /** Эмодзи-флаг по коду направления; 🌐 если код пустой/нераспознан. */
    fun forCode(code: String): String {
        val c = code.trim().lowercase()
        if (c.length >= 2) {
            val two = c.substring(0, 2)
            EXPLICIT[two]?.let { return it }
            // Собираем из regional indicator symbols, если это две латинские буквы.
            if (two[0] in 'a'..'z' && two[1] in 'a'..'z') {
                val sb = StringBuilder()
                for (ch in two) sb.appendCodePoint(0x1F1E6 + (ch - 'a'))
                return sb.toString()
            }
        }
        return "🌐"
    }
}

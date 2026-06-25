// Несекретные настройки интерфейса «Маяк»: тема (свет/тёмная/системная) и выбранный язык.
// Тема применяется через AppCompatDelegate; язык — через AppCompatDelegate.setApplicationLocales
// (там appcompat сам персистит). Тему персистим здесь и применяем при старте.
package org.amnezia.awg.mayak

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object MayakPrefs {
    private const val PREFS = "mayak_ui_prefs"
    private const val KEY_THEME = "theme_mode"

    // Значения совпадают по смыслу с AppCompatDelegate.MODE_NIGHT_*
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Сохранённый режим темы (по умолчанию — следовать системе). */
    fun themeMode(context: Context): Int =
        prefs(context).getInt(KEY_THEME, THEME_SYSTEM)

    /** Сохранить выбор и сразу применить к приложению. */
    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode))
    }

    /** Применить сохранённую тему (зовём при старте, до setContentView). */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(themeMode(context)))
    }

    private fun toNightMode(mode: Int): Int = when (mode) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}

// Несекретные настройки интерфейса «Маяк»: тема (свет/тёмная/системная) и выбранный язык.
// Тема применяется через AppCompatDelegate; язык — через AppCompatDelegate.setApplicationLocales
// (там appcompat сам персистит). Тему персистим здесь и применяем при старте.
package org.amnezia.awg.mayak

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import org.amnezia.awg.R

object MayakPrefs {
    private const val PREFS = "mayak_ui_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LAST_DIR = "last_direction_id"
    private const val KEY_UPDATE_DISMISSED = "update_dismissed_code" // versionCode, для которого нажали «Позже»
    private const val KEY_USE_IPV6 = "use_ipv6" // тумблер «использовать IPv6 в туннеле» (по умолч. ВКЛ)
    private const val KEY_SHOW_SPEED = "show_speed" // тумблер «показывать скорость передачи» (по умолч. ВЫКЛ)
    private const val KEY_SPLIT_APPS = "split_apps" // split-туннель: package-имена приложений (StringSet)
    private const val KEY_SPLIT_EXCLUDED = "split_excluded" // split-туннель: true=исключить эти, false=только эти

    /** Использовать ли IPv6 в туннеле (SPEC-0014). По умолчанию ВКЛ — польза; выключается в настройках
     *  («Не использовать IPv6»). При выкл клиент срезает v6 из конфига (ConfRenderer.stripIpv6). */
    fun useIpv6(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_IPV6, true)

    fun setUseIpv6(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_IPV6, enabled).apply()
    }

    /** Показывать ли скорость передачи в туннеле (↓/↑, обновление раз в секунду). По умолчанию ВЫКЛ. */
    fun showSpeed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_SPEED, false)

    fun setShowSpeed(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_SPEED, enabled).apply()
    }

    /** Split-туннель (SPEC-0018 F1): package-имена приложений, которые идут МИМО туннеля (excluded=true,
     *  по умолч.) — напр. банки/госуслуги, режущие загран-IP. Пусто = весь трафик в туннеле (безопасно
     *  by default). При excluded=false — наоборот, в туннель идут ТОЛЬКО эти. Применяется при коннекте
     *  (ConfRenderer.withSplitTunnel). Возвращаем КОПИЮ (getStringSet отдаёт живой набор — нельзя мутировать). */
    fun splitApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SPLIT_APPS, emptySet())?.toSet() ?: emptySet()

    /** true (по умолч.) — выбранные приложения ИСКЛЮЧЕНЫ из туннеля; false — только они В туннеле. */
    fun splitExcluded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPLIT_EXCLUDED, true)

    fun setSplitApps(context: Context, apps: Set<String>, excluded: Boolean) {
        prefs(context).edit()
            .putStringSet(KEY_SPLIT_APPS, apps)
            .putBoolean(KEY_SPLIT_EXCLUDED, excluded)
            .apply()
    }

    /** versionCode, обновление до которого пользователь отклонил («Позже») — чтобы не долбить каждый запуск. */
    fun updateDismissedCode(context: Context): Int =
        prefs(context).getInt(KEY_UPDATE_DISMISSED, 0)

    fun setUpdateDismissedCode(context: Context, code: Int) {
        prefs(context).edit().putInt(KEY_UPDATE_DISMISSED, code).apply()
    }

    /** ID последней выбранной страны (или -1, если не выбирали). */
    fun lastDirectionId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_DIR, -1L)

    fun setLastDirectionId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_LAST_DIR, id).apply()
    }

    // Значения совпадают по смыслу с AppCompatDelegate.MODE_NIGHT_*
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    /** Следующий режим в цикле Системная → Светлая → Тёмная → … */
    fun nextMode(mode: Int): Int = when (mode) {
        THEME_SYSTEM -> THEME_LIGHT
        THEME_LIGHT -> THEME_DARK
        else -> THEME_SYSTEM
    }

    /** Иконка, отражающая режим: солнце/луна/авто. */
    @DrawableRes
    fun iconFor(mode: Int): Int = when (mode) {
        THEME_LIGHT -> R.drawable.ic_theme_light
        THEME_DARK -> R.drawable.ic_theme_dark
        else -> R.drawable.ic_theme_system
    }

    /** Подпись режима для тоста. */
    @StringRes
    fun labelFor(mode: Int): Int = when (mode) {
        THEME_LIGHT -> R.string.mayak_theme_light
        THEME_DARK -> R.string.mayak_theme_dark
        else -> R.string.mayak_theme_system
    }

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

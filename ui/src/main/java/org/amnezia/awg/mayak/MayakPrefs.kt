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
    private const val KEY_SPLIT_RU_PRESET = "split_ru_preset" // RU-пресет: РФ-приложения мимо VPN одной кнопкой (по умолч. ВЫКЛ)
    private const val KEY_SPLIT_RU_VERSION = "split_ru_version" // version (хэш) последнего OTA-списка РФ-приложений
    // Пресеты split-туннеля (SPEC-0028):
    private const val KEY_PRESET_ACTIVE = "preset_active_id"   // id активного пресета (0 = нет)
    private const val KEY_PRESET_ENABLED = "preset_enabled"    // тумблер «применять активный пресет» (по умолч. ВЫКЛ)
    private const val KEY_PRESET_SHOW = "preset_show_on_home"   // показывать селектор пресетов на главном (по умолч. ВКЛ)
    private const val KEY_AUTOCONNECT = "autoconnect" // F3: автоподнятие последнего рабочего туннеля (по умолч. ВЫКЛ)
    private const val KEY_APP_LOCK = "app_lock" // блокировка приложения по биометрии/PIN устройства (по умолч. ВЫКЛ)
    private const val KEY_SORT_MODE = "dir_sort_mode" // SPEC-0031: 0=авто(сервер), 1=пинг, 2=свои (по умолч. 0)
    private const val KEY_CUSTOM_ORDER = "dir_custom_order" // SPEC-0031: пользовательский порядок направлений (CSV id)
    // Агрегированные (не-ПДн) счётчики для тихого еженедельного телеметри-бикона (MayakTelemetryWorker).
    // Кумулятивные за всё время установки (сервер при желании считает недельную дельту сам). НЕ сбрасываем.
    private const val KEY_CONNECT_COUNT = "telemetry_connect_count" // всего успешных подключений
    private const val KEY_ACTIVE_DAYS = "telemetry_active_days"     // число РАЗНЫХ дней с подключением
    private const val KEY_LAST_ACTIVE_DAY = "telemetry_last_active_day" // последняя учтённая дата (yyyy-MM-dd)

    // Режим сортировки списка стран (SPEC-0031): 0 — как отдал сервер (авто), 1 — по клиентскому пингу,
    // 2 — пользовательский (свой порядок перетаскиванием). По умолчанию 0.
    fun sortMode(context: Context): Int = prefs(context).getInt(KEY_SORT_MODE, 0)
    fun setSortMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_SORT_MODE, mode).apply()
    }

    // Пользовательский порядок направлений (список id). Пусто → нет своего порядка.
    fun customOrder(context: Context): List<Long> =
        prefs(context).getString(KEY_CUSTOM_ORDER, "")?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
    fun setCustomOrder(context: Context, ids: List<Long>) {
        prefs(context).edit().putString(KEY_CUSTOM_ORDER, ids.joinToString(",")).apply()
    }

    /** Автоподключение (SPEC-0018 F3): поднимать последний РАБОЧИЙ туннель при системном Always-On VPN и
     *  после загрузки устройства (из сохранённого на диске конфига, без сети). По умолчанию ВЫКЛ —
     *  включается пользователем в Настройках вместе с системным «блокировать интернет без VPN». */
    fun autoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOCONNECT, false)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOCONNECT, enabled).apply()
    }

    /** Блокировка приложения (запрос владельца 2026-07-06): при открытии/возврате спрашивать биометрию или
     *  системный PIN/паттерн (BiometricPrompt с DEVICE_CREDENTIAL). По умолчанию ВЫКЛ. Только UI-гейт — VPN
     *  не трогает. Свой PIN НЕ храним — используем системный (fallback DEVICE_CREDENTIAL). */
    fun appLock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APP_LOCK, false)

    fun setAppLock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_APP_LOCK, enabled).apply()
    }

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

    /** RU-пресет split-туннеля (BlancVPN-parity): «Открывать российские сервисы напрямую» одной кнопкой.
     *  При ВКЛ установленные РФ-приложения (банки/госуслуги/маркетплейсы — по правилам
     *  ui/assets/mayak_ru_direct.json) идут МИМО туннеля. Совмещается с ручным split (MayakRuDirect.effectiveSplit).
     *  По умолчанию ВЫКЛ. Применяется при следующем коннекте. */
    fun ruDirect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPLIT_RU_PRESET, false)

    fun setRuDirect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPLIT_RU_PRESET, enabled).apply()
    }

    /** version (хэш) последнего OTA-списка РФ-приложений в кэше — чтобы не перезаписывать без изменений. */
    fun ruDirectVersion(context: Context): String =
        prefs(context).getString(KEY_SPLIT_RU_VERSION, "") ?: ""

    fun setRuDirectVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SPLIT_RU_VERSION, version).apply()
    }

    /** Пресеты split-туннеля (SPEC-0028). Активный пресет — один за раз; тумблер решает, применять ли его. */
    fun activePresetId(context: Context): Long = prefs(context).getLong(KEY_PRESET_ACTIVE, 0L)

    fun setActivePresetId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_PRESET_ACTIVE, id).apply()
    }

    /** Применять активный пресет при подключении (тумблер у кнопки VPN). По умолчанию ВЫКЛ. */
    fun presetEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PRESET_ENABLED, false)

    fun setPresetEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRESET_ENABLED, enabled).apply()
    }

    /** Показывать селектор пресетов на главном экране (настройка). По умолчанию ВКЛ. */
    fun showPresetsOnHome(context: Context): Boolean = prefs(context).getBoolean(KEY_PRESET_SHOW, true)

    fun setShowPresetsOnHome(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRESET_SHOW, show).apply()
    }

    /** Сброс ВСЕХ настроек «Маяка» к дефолтам (кнопка в Настройках). Тему/язык appcompat перечитает при
     *  следующем старте. Не трогает токен/сессию (это не «настройки»). */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
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

    /** Всего успешных подключений (кумулятивно) — для телеметри-бикона. */
    fun connectCount(context: Context): Int = prefs(context).getInt(KEY_CONNECT_COUNT, 0)

    /** Число РАЗНЫХ дней, в которые было хоть одно подключение (кумулятивно) — для телеметри-бикона. */
    fun activeDays(context: Context): Int = prefs(context).getInt(KEY_ACTIVE_DAYS, 0)

    /** Отметить успешное подключение (best-effort счётчики телеметрии): +1 к числу подключений и, если
     *  сегодняшний день ещё не учтён, +1 к числу активных дней. Без ПДн — только агрегаты. Зовётся из
     *  onConnected(). java.time доступен через core-library desugaring (minSdk 24). */
    fun noteConnect(context: Context) {
        val p = prefs(context)
        val today = java.time.LocalDate.now().toString() // yyyy-MM-dd, без времени/ПДн
        val e = p.edit()
        e.putInt(KEY_CONNECT_COUNT, p.getInt(KEY_CONNECT_COUNT, 0) + 1)
        if (p.getString(KEY_LAST_ACTIVE_DAY, "") != today) {
            e.putInt(KEY_ACTIVE_DAYS, p.getInt(KEY_ACTIVE_DAYS, 0) + 1)
            e.putString(KEY_LAST_ACTIVE_DAY, today)
        }
        e.apply()
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

// Несекретные настройки интерфейса «Маяк»: тема (свет/тёмная/системная) и выбранный язык.
// Тема применяется через AppCompatDelegate; язык — через AppCompatDelegate.setApplicationLocales
// (там appcompat сам персистит). Тему персистим здесь и применяем при старте.
package org.amnezia.awg.mayak

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.AutoDiagGate
import org.amnezia.awg.mayak.core.LadderCounters
import org.amnezia.awg.mayak.core.UpdateNudge

object MayakPrefs {
    private const val PREFS = "mayak_ui_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LAST_DIR = "last_direction_id"
    private const val KEY_LAST_CONN_LABEL = "last_conn_label" // метка направления для шторки (переживает всё)
    private const val KEY_LAST_CONN_LABEL_DIR = "last_conn_label_dir" // чьё это имя (id направления)
    private const val KEY_UPDATE_DISMISSED = "update_dismissed_code" // versionCode, для которого нажали «Позже» (историческое, см. ниже)
    // Ступенчатые напоминания об обновлении (core.UpdateNudge): за какой версией следим, когда
    // впервые её увидели и какую ступень уже показали. Пришло на смену вечному молчанию после
    // одного «Позже» — тот ключ выше оставлен только чтобы не показывать ступень 0 повторно тем,
    // кто уже отказался в старой сборке.
    private const val KEY_UPDATE_TRACKED_CODE = "update_tracked_code"
    private const val KEY_UPDATE_FIRST_SEEN = "update_first_seen_ms"
    private const val KEY_UPDATE_STEP = "update_nudge_step"
    private const val KEY_USE_IPV6 = "use_ipv6" // тумблер «использовать IPv6 в туннеле» (по умолч. ВКЛ)
    private const val KEY_FORCE_FALLBACK = "force_fallback" // «всегда запасной канал» (SPEC-0039, по умолч. ВЫКЛ)
    private const val KEY_SHOW_SPEED = "show_speed" // тумблер «показывать скорость передачи» (по умолч. ВЫКЛ)
    private const val KEY_ONBOARDING_SHOWN = "onboarding_shown" // лист знакомства показан (один раз навсегда)
    private const val KEY_HAPTICS = "haptics_enabled" // тактильный отклик (по умолч. ВКЛ — как было всегда)
    private const val KEY_SPLIT_APPS = "split_apps" // split-туннель: package-имена приложений (StringSet)
    private const val KEY_SPLIT_EXCLUDED = "split_excluded" // split-туннель: true=исключить эти, false=только эти
    private const val KEY_SPLIT_RU_PRESET = "split_ru_preset" // RU-пресет: РФ-приложения мимо VPN одной кнопкой (по умолч. ВЫКЛ)
    private const val KEY_SPLIT_RU_VERSION = "split_ru_version" // version (хэш) последнего OTA-списка РФ-приложений
    // Пресеты split-туннеля (SPEC-0028):
    private const val KEY_PRESET_ACTIVE = "preset_active_id"   // id активного пресета (0 = нет)
    private const val KEY_PRESET_ENABLED = "preset_enabled"    // тумблер «применять активный пресет» (по умолч. ВЫКЛ)
    private const val KEY_PRESET_SHOW = "preset_show_on_home"   // показывать селектор пресетов на главном (по умолч. ВКЛ)
    // Авто-включение РФ-пресета при первом запуске (2026-08-03, прямой запрос владельца): человеку из
    // РФ иначе банки и Госуслуги идут через туннель и не пускают его, а тумблер он сам не найдёт.
    private const val KEY_RU_AUTO_TRIED = "ru_auto_tried" // проверка страны РЕАЛЬНО состоялась — не повторяем
    private const val KEY_PRESET_USER_DECIDED = "preset_user_decided" // человек сам тронул тумблер пресета руками
    // Ключ "always_on_decision" здесь БЫЛ и снят 10-08 вместе с самим разговором про постоянное
    // подключение (решение владельца). У тех, кто успел ответить, значение так и лежит в
    // SharedPreferences — не читаем его и не чистим: чистка ради чистки трогает чужой файл настроек
    // на всех телефонах разом, а мёртвый ключ не стоит ничего.
    private const val KEY_LEARNED_HOSTS = "learned_hosts" // адреса ядра, полученные от сервера (реестр доменов), CSV
    // Подписанный delivery-документ (F-T8): конверт целиком (verbatim JSON) + максимальная ПРИНЯТАЯ
    // version. Version — анти-откат МЕЖДУ запусками: без неё цензор мог бы скормить старый (ещё не
    // протухший) документ с уже отозванными адресами. ⚠️ resetAll() стирает и её — как и learned_hosts;
    // это сброс к вшитым, а не дыра: следующий принятый документ снова поднимет планку.
    private const val KEY_DELIVERY_ENVELOPE = "delivery_envelope"
    private const val KEY_DELIVERY_VERSION = "delivery_version"
    private const val KEY_LEARNED_CABINET = "learned_cabinet" // адрес кабинета из того же реестра (роль cabinet)
    private const val KEY_LEARNED_SITE = "learned_site" // адрес сайта из того же реестра (роль site) — справка
    private const val KEY_AUTOCONNECT = "autoconnect" // F3: автоподнятие последнего рабочего туннеля (по умолч. ВЫКЛ)
    private const val KEY_APP_LOCK = "app_lock" // блокировка приложения по биометрии/PIN устройства (по умолч. ВЫКЛ)
    private const val KEY_SORT_MODE = "dir_sort_mode" // SPEC-0031: 0=авто(сервер), 2=свои; 1=снятый режим «пинг» → авто
    private const val KEY_CUSTOM_ORDER = "dir_custom_order" // SPEC-0031: пользовательский порядок направлений (CSV id)
    // Агрегированные (не-ПДн) счётчики для тихого еженедельного телеметри-бикона (MayakTelemetryWorker).
    // Кумулятивные за всё время установки (сервер при желании считает недельную дельту сам). НЕ сбрасываем.
    private const val KEY_CONNECT_COUNT = "telemetry_connect_count" // всего успешных подключений
    private const val KEY_ACTIVE_DAYS = "telemetry_active_days"     // число РАЗНЫХ дней с подключением
    private const val KEY_LAST_ACTIVE_DAY = "telemetry_last_active_day" // последняя учтённая дата (yyyy-MM-dd)
    private const val KEY_FALLBACK_COUNT = "telemetry_fallback_count" // подключений через запасной канал (SPEC-0039)
    // Исход лестницы подключения (2026-08-09): какая ступень дала выход, какие до неё провалились,
    // сколько занял успех. Та же кумулятивная модель, что у счётчиков выше. Логика — core.LadderTelemetry.
    private const val KEY_LADDER_DIRECT_OK = "telemetry_ladder_direct_ok"
    private const val KEY_LADDER_RELAY_OK = "telemetry_ladder_relay_ok"
    private const val KEY_LADDER_FALLBACK_OK = "telemetry_ladder_fallback_ok"
    private const val KEY_LADDER_DIRECT_FAIL = "telemetry_ladder_direct_fail"
    private const val KEY_LADDER_RELAY_FAIL = "telemetry_ladder_relay_fail"
    private const val KEY_LADDER_FALLBACK_FAIL = "telemetry_ladder_fallback_fail"
    private const val KEY_LADDER_NONE = "telemetry_ladder_none"
    private const val KEY_LADDER_MS_SUM = "telemetry_ladder_ms_sum" // Long: сумма мс успешных попыток
    // Rate-limit авто-заливки диаг-лога (0.3.48, переработан 0.3.99): два зазора, арифметика — в
    // :core.AutoDiagGate (юнит-тест на JVM). lastAttempt — короткий анти-шквал (ставим ДО сети,
    // независимо от исхода); lastSuccess — основной 6-часовой лимит (ставим ТОЛЬКО после того, как
    // лог реально дошёл до сервера — иначе постоянно ломающаяся сеть выжигала бы лимит без пользы).
    private const val KEY_LAST_AUTO_DIAG_ATTEMPT = "auto_diag_last_attempt_ms"
    private const val KEY_LAST_AUTO_DIAG_OK = "auto_diag_last_ok_ms"

    // Режим сортировки списка стран (SPEC-0031): 0 — как отдал сервер (авто), 2 — пользовательский
    // (свой порядок перетаскиванием). По умолчанию 0. Значение 1 — снятый 15-08 режим «по клиентскому
    // пингу»: у людей оно ещё лежит в prefs, читается как «авто», НЕ переиспользовать.
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
    /** Адреса ядра, которые прислал сервер (реестр доменов, миграция 0089). Пусто = ещё не спрашивали. */
    fun learnedHosts(context: Context): List<String> =
        prefs(context).getString(KEY_LEARNED_HOSTS, "")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun setLearnedHosts(context: Context, hosts: List<String>) {
        prefs(context).edit().putString(KEY_LEARNED_HOSTS, hosts.joinToString(",")).apply()
    }

    /** Последний ПРИНЯТЫЙ (подпись сошлась) delivery-конверт как есть; null — ещё не принимали.
     *  Подпись/срок перепроверяются при чтении (MayakDelivery.accepted) — здесь только хранение. */
    fun deliveryEnvelope(context: Context): String? =
        prefs(context).getString(KEY_DELIVERY_ENVELOPE, null)?.takeIf { it.isNotBlank() }

    /** Максимальная принятая version конверта (анти-откат между запусками). 0 = не принимали. */
    fun deliveryVersion(context: Context): Int = prefs(context).getInt(KEY_DELIVERY_VERSION, 0)

    /** Принять конверт: хранить его и поднять планку версии. Одним edit — чтобы конверт и планка
     *  не разъехались между собой при смерти процесса посреди записи. */
    fun setDelivery(context: Context, envelopeJson: String, version: Int) {
        prefs(context).edit()
            .putString(KEY_DELIVERY_ENVELOPE, envelopeJson)
            .putInt(KEY_DELIVERY_VERSION, maxOf(version, deliveryVersion(context)))
            .apply()
    }

    /** Адрес кабинета из того же реестра доменов. Пусто = сервер ещё не отвечал → берём зашитый. */
    fun learnedCabinet(context: Context): String =
        prefs(context).getString(KEY_LEARNED_CABINET, "").orEmpty()

    fun setLearnedCabinet(context: Context, host: String) {
        prefs(context).edit().putString(KEY_LEARNED_CABINET, host).apply()
    }

    /** Адрес САЙТА из того же реестра (роль site): там живёт справочный центр. Пусто = не спрашивали. */
    fun learnedSite(context: Context): String =
        prefs(context).getString(KEY_LEARNED_SITE, "").orEmpty()

    fun setLearnedSite(context: Context, host: String) {
        prefs(context).edit().putString(KEY_LEARNED_SITE, host).apply()
    }

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

    /** Всегда идти через ЗАПАСНОЙ канал (SPEC-0039), не пробуя UDP. По умолчанию ВЫКЛ: прямой путь
     *  быстрее, и в норме автоматика сама уходит на запасной, когда UDP не проходит. Тумблер нужен
     *  там, где UDP не работает ВСЕГДА (оператор режет наглухо): человек экономит ~6с ожидания на
     *  каждом подключении. Заодно это единственный способ проверить запасной канал на сети, где UDP
     *  ходит нормально. Если у линии запасного канала нет — тумблер ни на что не влияет. */
    fun forceFallback(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_FALLBACK, false)

    fun setForceFallback(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_FALLBACK, enabled).apply()
    }

    /**
     * Состояние ступенчатых напоминаний для версии latestCode: сколько дней прошло с первой встречи
     * и какую ступень уже показывали. Первая встреча запоминается ЗДЕСЬ же — иначе отсчёт начинать
     * не от чего; для новой версии история обнуляется (core.UpdateNudge.isNewVersion).
     */
    fun updateNudgeState(context: Context, latestCode: Int, nowMs: Long): Pair<Long, Int> {
        val p = prefs(context)
        val tracked = p.getInt(KEY_UPDATE_TRACKED_CODE, 0)
        if (UpdateNudge.isNewVersion(latestCode, tracked)) {
            p.edit()
                .putInt(KEY_UPDATE_TRACKED_CODE, latestCode)
                .putLong(KEY_UPDATE_FIRST_SEEN, nowMs)
                .putInt(KEY_UPDATE_STEP, UpdateNudge.NO_STEP)
                .apply()
            // Тот, кто уже отказался от ЭТОЙ версии в старой сборке (до ступеней), нулевую ступень
            // второй раз не увидит: уважаем прошлое «Позже», дальше он попадёт на 14-й день.
            val legacyDismissed = p.getInt(KEY_UPDATE_DISMISSED, 0) >= latestCode
            return 0L to (if (legacyDismissed) 0 else UpdateNudge.NO_STEP)
        }
        val firstSeen = p.getLong(KEY_UPDATE_FIRST_SEEN, nowMs)
        return UpdateNudge.daysBetween(firstSeen, nowMs) to p.getInt(KEY_UPDATE_STEP, UpdateNudge.NO_STEP)
    }

    /** Запомнить показанную ступень (последняя ступень повторяется, её не записываем как «выше»). */
    fun setUpdateNudgeStep(context: Context, step: Int) {
        prefs(context).edit().putInt(KEY_UPDATE_STEP, step).apply()
    }

    /** Показывали ли лист знакомства. Ответ навсегда: второй раз это раздражение, а не помощь. */
    fun onboardingShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_SHOWN, false)

    fun setOnboardingShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
    }

    /**
     * Тактильный отклик (вибрация) на действия и на смену состояния туннеля. По умолчанию ВКЛючен:
     * так приложение вело себя всегда, и выключатель добавлен именно как выключатель, а не как
     * «включите, чтобы стало как раньше». Единственный читатель — MayakHaptics.
     */
    fun hapticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, enabled).apply()
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

    /** Авто-включение РФ-пресета (2026-08-03): проверка страны по внешнему IP уже РЕАЛЬНО состоялась
     *  (успешно — сеть ответила) хотя бы раз. Пока false — при подходящих условиях пробуем снова на
     *  каждом следующем запуске (см. MayakActivity.maybeAutoEnableRuPreset). Отложенная проверка
     *  (например, из-за поднятого VPN) флаг НЕ ставит — это не «попытка», а перенос на потом. */
    fun ruAutoTried(context: Context): Boolean = prefs(context).getBoolean(KEY_RU_AUTO_TRIED, false)

    fun setRuAutoTried(context: Context, tried: Boolean) {
        prefs(context).edit().putBoolean(KEY_RU_AUTO_TRIED, tried).apply()
    }

    /** Человек САМ переключил тумблер «применять пресет» руками (а не мы программно синхронизировали
     *  UI с сохранённым состоянием). Как только это случилось — авто-включение РФ-пресета больше не
     *  имеет права трогать тумблер: осознанный выбор человека важнее нашей догадки по IP. */
    fun presetUserDecided(context: Context): Boolean = prefs(context).getBoolean(KEY_PRESET_USER_DECIDED, false)

    fun setPresetUserDecided(context: Context, decided: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRESET_USER_DECIDED, decided).apply()
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

    /**
     * Метка направления ПОСЛЕДНЕГО подключения («🇳🇱 Нидерланды») — запасной источник для шторки.
     *
     * Зачем на диске: в памяти она процесс-скоупная (GoTunnel.connectedLabel) и обнуляется на каждом
     * переподъёме туннеля, а возвращает её только успешный коннект при открытом экране. Один такой
     * промах — и в шторке до конца сессии висело голое «Защищено» без страны, пинга и IPv6 (жалоба
     * владельца 2026-08-03). Пишем при КАЖДОЙ попытке подключения, ещё до подъёма туннеля.
     */
    fun lastConnLabel(context: Context): String? =
        prefs(context).getString(KEY_LAST_CONN_LABEL, null)?.takeIf { it.isNotBlank() }

    /**
     * Та же метка, но ТОЛЬКО если она про ЭТО направление.
     *
     * Нужна безголовому подъёму (плитка в шторке, Always-On, BOOT_COMPLETED): там имени страны взять
     * неоткуда — /connect отдаёт КОД («nl»), а список стран лежит за сетью, которой в этот момент
     * может не быть. Метка на диске подходит, но не всегда: человек мог ВЫБРАТЬ другую страну и не
     * подключиться (id выбора пишется на тап, метка — на подключение). Подписать чужую страну именем
     * прежней хуже, чем показать код: код непонятен, а неверное имя — враньё.
     */
    fun connLabelFor(context: Context, directionId: Long): String? =
        if (prefs(context).getLong(KEY_LAST_CONN_LABEL_DIR, -1L) == directionId) lastConnLabel(context) else null

    /** directionId — чьё это имя. Без него метку нельзя переиспользовать (см. connLabelFor), поэтому
     *  параметр обязателен: у каждого места записи направление под рукой есть. */
    fun setLastConnLabel(context: Context, label: String?, directionId: Long) {
        prefs(context).edit()
            .putString(KEY_LAST_CONN_LABEL, label)
            .putLong(KEY_LAST_CONN_LABEL_DIR, directionId)
            .apply()
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

    /** Сколько подключений ушло через ЗАПАСНОЙ канал (кумулятивно) — для телеметри-бикона. Отдельно от
     *  общего счётчика: доля таких подключений показывает, у скольких людей UDP уже не проходит.
     *  Это сигнал о цензуре, ради которого спека и делалась — без него мы узнаём о блокировках от
     *  пользователей в поддержке, а не из данных. */
    fun fallbackConnects(context: Context): Int = prefs(context).getInt(KEY_FALLBACK_COUNT, 0)

    /** Отметить подключение, поднятое через запасной канал. Зовётся из onConnected() ДОПОЛНИТЕЛЬНО к
     *  noteConnect() — то есть такое подключение попадает и в общий счётчик, и в этот. */
    fun noteFallbackConnect(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_FALLBACK_COUNT, p.getInt(KEY_FALLBACK_COUNT, 0) + 1).apply()
    }

    /** Накопленный исход лестницы подключения — для телеметри-бикона (кумулятивно, не сбрасываем). */
    fun ladderCounters(context: Context): LadderCounters {
        val p = prefs(context)
        return LadderCounters(
            directOk = p.getInt(KEY_LADDER_DIRECT_OK, 0),
            relayOk = p.getInt(KEY_LADDER_RELAY_OK, 0),
            fallbackOk = p.getInt(KEY_LADDER_FALLBACK_OK, 0),
            directFail = p.getInt(KEY_LADDER_DIRECT_FAIL, 0),
            relayFail = p.getInt(KEY_LADDER_RELAY_FAIL, 0),
            fallbackFail = p.getInt(KEY_LADDER_FALLBACK_FAIL, 0),
            none = p.getInt(KEY_LADDER_NONE, 0),
            successMsSum = p.getLong(KEY_LADDER_MS_SUM, 0L),
        )
    }

    /** Прибавить исход ОДНОЙ попытки подключения (дельту даёт LadderTelemetry.attemptOutcome).
     *  Зовётся из doConnect() ТОЛЬКО при явном исходе: успех какой-то ступени или «все ступени мимо».
     *  Отмена человеком и ошибки ядра исходом лестницы не являются и сюда не попадают. */
    fun noteLadder(context: Context, delta: LadderCounters) {
        val sum = ladderCounters(context) + delta
        prefs(context).edit()
            .putInt(KEY_LADDER_DIRECT_OK, sum.directOk)
            .putInt(KEY_LADDER_RELAY_OK, sum.relayOk)
            .putInt(KEY_LADDER_FALLBACK_OK, sum.fallbackOk)
            .putInt(KEY_LADDER_DIRECT_FAIL, sum.directFail)
            .putInt(KEY_LADDER_RELAY_FAIL, sum.relayFail)
            .putInt(KEY_LADDER_FALLBACK_FAIL, sum.fallbackFail)
            .putInt(KEY_LADDER_NONE, sum.none)
            .putLong(KEY_LADDER_MS_SUM, sum.successMsSum)
            .apply()
    }

    /** Можно ли сейчас пробовать авто-заливку (правило — AutoDiagGate в :core). ТОЛЬКО читает,
     *  ничего не помечает — пометки раздельные (см. noteAutoDiagAttempt/noteAutoDiagSuccess ниже),
     *  чтобы неудачная попытка не сжигала лимит наравне с успешной. Ручную кнопку это НЕ трогает. */
    fun autoDiagDue(context: Context): Boolean {
        val p = prefs(context)
        return AutoDiagGate.dueForAttempt(
            lastAttemptMs = p.getLong(KEY_LAST_AUTO_DIAG_ATTEMPT, 0L),
            lastSuccessMs = p.getLong(KEY_LAST_AUTO_DIAG_OK, 0L),
            nowMs = System.currentTimeMillis(),
        )
    }

    /** Отметить, что попытка авто-заливки НАЧАЛАСЬ — звать ДО сетевого вызова, до того как известен
     *  исход. Короткий анти-шквальный зазор (AutoDiagGate.MIN_ATTEMPT_INTERVAL_MS): не даёт серии
     *  отказов подряд долбить сеть, но НЕ трогает основной 6-часовой лимит. */
    fun noteAutoDiagAttempt(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_AUTO_DIAG_ATTEMPT, System.currentTimeMillis()).apply()
    }

    /** Отметить, что авто-заливка ДОШЛА до сервера — основной 6-часовой лимит. Звать ТОЛЬКО после
     *  успешного session.sendDiagLog(), не на каждой попытке (находка 2026-08-07: раньше единая
     *  метка ставилась ДО попытки, и провал запирал авто-заливку на все 6ч, даже когда сеть
     *  оживала уже через пару минут — человек с постоянно ломающимся каналом не получал диагностики
     *  вовсе). */
    fun noteAutoDiagSuccess(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_AUTO_DIAG_OK, System.currentTimeMillis()).apply()
    }

    // Значения совпадают по смыслу с AppCompatDelegate.MODE_NIGHT_*.
    // 🔴 THEME_SYSTEM (0) БОЛЬШЕ НЕ ВЫБИРАЕТСЯ (решение владельца 2026-08-09: «по умолчанию тёмная,
    // переключатель системной убрать — человек пусть выбирает сам»). Константа оставлена НЕ для
    // выбора, а для чтения СТАРЫХ настроек: у тех, кто уже пользуется приложением, в prefs лежит
    // ровно этот 0, и молча считать его «светлой» значило бы включить людям не то, что они видели.
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    /** Следующий режим по кругу. Режимов теперь два, поэтому это просто переброс светлая ↔ тёмная. */
    fun nextMode(mode: Int): Int = if (mode == THEME_LIGHT) THEME_DARK else THEME_LIGHT

    /** Иконка, отражающая режим: солнце или луна. */
    @DrawableRes
    fun iconFor(mode: Int): Int =
        if (mode == THEME_LIGHT) R.drawable.ic_theme_light else R.drawable.ic_theme_dark

    /** Подпись режима для тоста. */
    @StringRes
    fun labelFor(mode: Int): Int =
        if (mode == THEME_LIGHT) R.string.mayak_theme_light else R.string.mayak_theme_dark

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Сохранённый режим темы. По умолчанию ТЁМНАЯ — и для новых установок, и для тех, у кого в
     *  настройках лежит снятый режим «Системная»: выбора между тремя больше нет, а переносить
     *  человека в светлую тему без его ведома — хуже, чем в тёмную, которую он и так видел бы
     *  ночью. Ответ сужен до двух значений здесь, чтобы никакая ветка выше не гадала. */
    fun themeMode(context: Context): Int =
        if (prefs(context).getInt(KEY_THEME, THEME_DARK) == THEME_LIGHT) THEME_LIGHT else THEME_DARK

    /** Сохранить выбор и сразу применить к приложению. */
    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode))
    }

    /** Применить сохранённую тему (зовём при старте, до setContentView). */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(themeMode(context)))
    }

    // MODE_NIGHT_FOLLOW_SYSTEM тут больше нет НАМЕРЕННО: пока он оставался запасной веткой, любое
    // неизвестное значение в prefs снова отдавало тему системе — ровно то, что решили убрать.
    private fun toNightMode(mode: Int): Int =
        if (mode == THEME_LIGHT) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
}

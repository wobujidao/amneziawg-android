// Настройки «Маяк»: выбор темы (свет/тёмная/системная) и языка. Тема — через MayakPrefs
// (AppCompatDelegate + персист), язык — через общий MayakLanguages-диалог.
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.fragment.AppListDialogFragment
import org.amnezia.awg.mayak.core.AccountSettings
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend

class MayakSettingsActivity : AppCompatActivity() {

    // Сессия/хранилище нужны нескольким блокам экрана (фильтрация, подписка, диаг-лог, выход) —
    // держим одну пару на активити вместо трёх одинаковых конструкторов по месту.
    private val store by lazy { KeystoreSecureStore(this) }
    private val session by lazy { MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store)) }

    /** Текущие настройки аккаунта с ядра; null — ещё не загрузились или загрузка не удалась. */
    private var accountSettings: AccountSettings? = null

    private fun backend(): MayakBackend =
        MayakBackend(
            HostProvider(MayakHostList.effective(this, store.get(MayakActivity.KEY_SERVER))),
            bypassTunnel = OutsideTunnel.opener(this),
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_settings)
        MayakSystemBars.apply(this) // контраст иконок статус-бара/навбара под тему

        // Edge-to-edge: контент рисуется под системными барами (градиент во всю высоту). Отступаем контент на
        // высоту статус-бара сверху и НАВИГАЦИОННОЙ панели снизу — иначе кнопка «Выход» уезжала под навбар
        // (правка владельца 2026-07-06, скриншот). Адаптивно: жест-навигация тоньше, 3-кнопочная толще.
        //
        // Статус-бар отдан ЗАКРЕПЛЁННОЙ шапке (аудит 2026-07-31, п. 8): она непрозрачная, поэтому
        // прокручиваемый текст прячется под ней, а не сталкивается с часами. Контент отступает на
        // высоту шапки — её меряем после разметки, высота зависит от инсета конкретного телефона.
        val content = findViewById<View>(R.id.mayak_settings_content)
        val header = findViewById<View>(R.id.mayak_settings_header)
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        val headerBaseTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = headerBaseTop + bars.top)
            header.post { v.updatePadding(top = baseTop + header.height, bottom = baseBottom + bars.bottom) }
            insets
        }

        findViewById<MaterialButton>(R.id.mayak_settings_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }
        findViewById<MaterialButton>(R.id.mayak_settings_language).setOnClickListener {
            MayakLanguages.showDialog(this)
        }
        findViewById<MaterialButton>(R.id.mayak_settings_about).setOnClickListener {
            startActivity(Intent(this, MayakAboutActivity::class.java))
            MayakTransitions.applyAxis(this)
        }
        // Справочный центр: адрес — из реестра доменов. Его может не быть (кабинет задан как IP) —
        // тогда кнопку прячем, а не показываем неработающую.
        val helpCenter = findViewById<MaterialButton>(R.id.mayak_settings_help_center)
        if (MayakHostList.helpUrl(this) != null) {
            helpCenter.setOnClickListener { MayakSupport.openHelp(this) }
        } else {
            helpCenter.visibility = View.GONE
        }
        // «Написать в поддержку» ведёт на ФОРМУ, а не в почтовое приложение (08-08): у человека без
        // настроенного почтового клиента mailto: не делал ничего — то есть кнопка была пустой ровно
        // в той ситуации, ради которой её нажимают. Письмо осталось запасным путём НА ТОМ экране.
        findViewById<MaterialButton>(R.id.mayak_settings_write_support).setOnClickListener {
            startActivity(Intent(this, MayakSupportActivity::class.java))
            MayakTransitions.applyAxis(this)
        }
        findViewById<MaterialButton>(R.id.mayak_settings_send_log).setOnClickListener { sendLog(it as MaterialButton) }
        // «Поделиться логом» видна, только пока на диске лежит недоставленный лог с прошлой неудачной
        // попытки (0.3.99) — состояние могло смениться, пока экран был закрыт (пришли на новую сессию
        // после провала на прошлой), поэтому проверяем прямо тут, а не полагаемся на видимость из XML.
        findViewById<MaterialButton>(R.id.mayak_settings_share_log).setOnClickListener { shareSavedLog() }
        refreshShareLogButton()
        findViewById<MaterialButton>(R.id.mayak_settings_logout).setOnClickListener { confirmLogout() }
        // Удаление аккаунта показываем только вошедшим: удалять нечего, а кнопка пугает.
        val deleteAccount = findViewById<MaterialButton>(R.id.mayak_settings_delete_account)
        if (session.hasToken()) deleteAccount.setOnClickListener { confirmDeleteAccount() }
        else deleteAccount.visibility = View.GONE

        // Показываем, под каким email выполнен вход (запрос владельца: в приложении не было видно аккаунта).
        findViewById<TextView>(R.id.mayak_settings_account).text = getString(
            R.string.mayak_settings_account,
            session.email() ?: getString(R.string.mayak_settings_account_none),
        )
        // Номер аккаунта: сначала из хранилища (мгновенно, работает и без сети), потом — освежить.
        showAccountNumber(org.amnezia.awg.mayak.core.AccountNumber.display(store))
        findViewById<MaterialButton>(R.id.mayak_settings_cabinet).setOnClickListener {
            openUrl(MayakHostList.cabinetUrl(this))
        }
        // Список устройств и отключение лишнего — прямо здесь. После отключения перечитываем строку
        // «Устройства: N из M»: иначе она показывала бы прежнее число, и человек решил бы, что не сработало.
        findViewById<MaterialButton>(R.id.mayak_settings_devices).setOnClickListener {
            MayakDevices.show(this) { loadSubscription() }
        }

        // Фильтрация DNS и срок доступа — оба живут на АККАУНТЕ (ядро), поэтому только после входа.
        findViewById<MaterialButton>(R.id.mayak_settings_dns).setOnClickListener {
            if (accountSettings == null) loadFiltering() else showDnsDialog()
        }
        if (session.hasToken()) {
            loadFiltering()
            loadSubscription()
            loadAccountNumber()
        } else {
            // Не вошли — карточка фильтрации бесполезна (менять нечего) и только путала бы.
            findViewById<View>(R.id.mayak_settings_filtering_card).visibility = View.GONE
        }

        // Тактильный отклик (директива владельца 01-07): единственная точка правды — MayakPrefs,
        // единственный исполнитель — MayakHaptics. Применяется сразу, переподключение не нужно.
        val hapticsSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_haptics)
        hapticsSwitch.isChecked = MayakPrefs.hapticsEnabled(this)
        hapticsSwitch.setOnCheckedChangeListener { view, checked ->
            MayakPrefs.setHapticsEnabled(this, checked)
            // Включили — сразу дать почувствовать, что это такое (выключили — молчим, логично).
            if (checked) MayakHaptics.tap(view)
        }

        // Тумблер «Использовать IPv6» (SPEC-0014): по умолч. ВКЛ. При выкл клиент срезает v6 из конфига
        // при следующем подключении (кэш конфига v6-полный, стрип на apply) → IPv6 идёт мимо туннеля.
        val speedSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_speed)
        speedSwitch.isChecked = MayakPrefs.showSpeed(this)
        speedSwitch.setOnCheckedChangeListener { _, checked ->
            MayakPrefs.setShowSpeed(this, checked)
            // Применяем СРАЗУ, без переподключения (правка владельца 2026-07-06): на главном скорость
            // подхватит живой цикл (проверяет тумблер каждую секунду), а уведомлению переключаем
            // спид-нотифаер здесь же. При выключении возвращаем обычное уведомление (без ↓/↑).
            if (GoTunnel(this).isUp()) {
                if (checked) {
                    SpeedNotifier.start(this)
                } else {
                    SpeedNotifier.stop()
                    MayakNotification.show(this, GoTunnel.connectedLabel, GoTunnel.connectedPingMs)
                }
            }
        }
        val ipv6Switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_ipv6)
        ipv6Switch.isChecked = MayakPrefs.useIpv6(this)
        ipv6Switch.setOnCheckedChangeListener { _, checked ->
            MayakPrefs.setUseIpv6(this, checked)
            // Применится при следующем коннекте (текущий туннель не трогаем, чтобы не рвать сессию молча).
            Toast.makeText(this, R.string.mayak_settings_ipv6_applied, Toast.LENGTH_SHORT).show()
        }

        // «Всегда запасной канал» (SPEC-0039). В норме не нужен: автоматика сама уходит на запасной,
        // когда UDP не проходит. Полезен там, где UDP не работает ВСЕГДА — не ждать распознавания
        // каждый раз. Применяется со следующего подключения, текущий туннель не рвём.
        val forceFallback = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_force_fallback)
        forceFallback.isChecked = MayakPrefs.forceFallback(this)
        forceFallback.setOnCheckedChangeListener { _, checked ->
            MayakPrefs.setForceFallback(this, checked)
            Toast.makeText(this, R.string.mayak_settings_ipv6_applied, Toast.LENGTH_SHORT).show()
        }

        // Пресеты split-туннеля (SPEC-0028): само управление (выбор/создание/правка) — на ГЛАВНОМ экране
        // (селектор пресета + тумблер у кнопки VPN). Здесь — только показывать ли этот селектор. По умолч. ВКЛ.
        val showPresets = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_show_presets)
        showPresets.isChecked = MayakPrefs.showPresetsOnHome(this)
        showPresets.setOnCheckedChangeListener { _, checked ->
            MayakPrefs.setShowPresetsOnHome(this, checked)
        }

        // Split-туннель (SPEC-0028): сам выбор/редактор пресетов живёт в MayakActivity (состояние,
        // кэш, редактор — общие с главным экраном) — открываем тот же диалог там же, extra-флагом
        // (находка 2026-08-03: раньше это было достижимо ТОЛЬКО через непомеченную кнопку на главном).
        findViewById<MaterialButton>(R.id.mayak_settings_split).setOnClickListener {
            startActivity(Intent(this, MayakActivity::class.java).putExtra(MayakActivity.EXTRA_OPEN_SPLIT_TUNNEL, true))
            MayakTransitions.applyAxis(this)
        }

        // Сбросить все настройки к дефолтам (SPEC-0028): настроек много — быстрый сброс с подтверждением.
        findViewById<MaterialButton>(R.id.mayak_settings_reset).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(R.string.mayak_settings_reset_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MayakPrefs.resetAll(this)
                    Toast.makeText(this, R.string.mayak_settings_reset_done, Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Значок приложения / маскировка (SPEC-0018 F2): диалог выбора пресета иконки+имени.
        findViewById<MaterialButton>(R.id.mayak_settings_disguise).setOnClickListener { showDisguiseDialog() }

        // Автоподключение (SPEC-0018 F3): при вкл поднимаем последний РАБОЧИЙ туннель, когда система стартует
        // наш VpnService по Always-On VPN и после загрузки устройства (из сохранённого конфига, без сети).
        // По умолчанию ВЫКЛ. Текущий туннель не трогаем — только будущие старты.
        val autoConnectSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_autoconnect)
        autoConnectSwitch.isChecked = MayakPrefs.autoConnect(this)
        autoConnectSwitch.setOnCheckedChangeListener { _, checked ->
            MayakPrefs.setAutoConnect(this, checked)
        }

        // Kill-switch (SPEC-0018 F3): «блокировать интернет без VPN» задаётся в СИСТЕМНЫХ настройках VPN
        // (Always-On VPN + «Блокировать соединения без VPN») — приложение НЕ может включить это само, поэтому
        // ведём пользователя в системный экран. Вместе с автоподключением выше даёт полный kill-switch: нет
        // туннеля → нет интернета, а туннель поднимается сам. ACTION_VPN_SETTINGS есть не на всех прошивках → фолбэк.
        findViewById<MaterialButton>(R.id.mayak_settings_killswitch).setOnClickListener {
            val opened = runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)); true
            }.getOrDefault(false)
            if (!opened) Toast.makeText(this, R.string.mayak_settings_killswitch_unavailable, Toast.LENGTH_LONG).show()
        }

        // Блокировка приложения по биометрии/PIN (запрос владельца 2026-07-06). Применяется при следующем
        // возврате из фона/открытии — текущий сеанс не запираем сразу. Если на устройстве нет ни биометрии,
        // ни экран-блокировки — предупреждаем (fail-open: без них блокировка просто не сработает).
        val appLockSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_applock)
        appLockSwitch.isChecked = MayakPrefs.appLock(this)
        appLockSwitch.setOnCheckedChangeListener { btn, checked ->
            if (checked) {
                // Включение = сразу подтвердить личность (запрос владельца 2026-07-20): нельзя включить
                // защиту, не доказав, что это ты. Нет биометрии/экран-блока → защищать нечем, откатываем.
                if (!MayakLock.canAuthenticate(this)) {
                    Toast.makeText(this, R.string.mayak_settings_applock_no_credential, Toast.LENGTH_LONG).show()
                    btn.isChecked = false
                    return@setOnCheckedChangeListener
                }
                MayakLock.authenticate(this) { ok ->
                    if (ok) MayakPrefs.setAppLock(this, true)
                    else btn.isChecked = false // отмена/ошибка отпечатка → не включаем
                }
            } else {
                MayakPrefs.setAppLock(this, false)
            }
        }

        // Тема — сегментированный переключатель (Светлая/Тёмная). Кнопки «Системная» больше нет
        // (решение владельца 09-08): выбор за человеком, а умолчание — тёмная. check() при
        // инициализации дёрнет листенер, но guard `mode != текущий` не даст лишнего
        // setThemeMode/пересоздания.
        val group = findViewById<MaterialButtonToggleGroup>(R.id.mayak_theme_group)
        group.check(
            if (MayakPrefs.themeMode(this) == MayakPrefs.THEME_LIGHT) R.id.mayak_theme_light
            else R.id.mayak_theme_dark
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.mayak_theme_light) MayakPrefs.THEME_LIGHT
                       else MayakPrefs.THEME_DARK
            if (mode != MayakPrefs.themeMode(this)) {
                MayakPrefs.setThemeMode(this, mode) // setDefaultNightMode пересоздаст активити с новой темой
            }
        }

        // Пришли сюда с провала подключения (тап по надписи об ошибке на главном, находка 2026-08-03:
        // «Диагностика и помощь» лежит на самом дне списка, и с места отказа до неё не дойти). Секция
        // и так на экране — просто сразу докручиваем к ней, отдельного пункта меню заводить не нужно.
        if (intent?.getBooleanExtra(EXTRA_OPEN_DIAGNOSTICS, false) == true) {
            val scroll = findViewById<ScrollView>(R.id.mayak_settings_scroll)
            val card = findViewById<View>(R.id.mayak_settings_diagnostics_card)
            scroll.post { scroll.smoothScrollTo(0, card.top) }
        }
    }

    /** Диалог маскировки (SPEC-0018 F2): выбор пресета иконки+имени. Применение — MayakDisguise.apply
     *  (переключает activity-alias, не убивая процесс → VPN не рвётся; иконка обновится через миг). */
    private fun showDisguiseDialog() {
        val aliases = MayakDisguise.ALL
        val labels = arrayOf(
            getString(R.string.mayak_icon_dark),
            getString(R.string.mayak_icon_light),
            getString(R.string.mayak_disguise_weather),
            getString(R.string.mayak_disguise_notes),
            getString(R.string.mayak_disguise_calc),
        )
        val current = MayakDisguise.current(this)
        val checked = aliases.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.mayak_settings_disguise)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                if (aliases[which] != current) {
                    MayakDisguise.apply(this, aliases[which])
                    Toast.makeText(this, R.string.mayak_disguise_applied, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.mayak_cancel, null)
            .show()
    }

    // ===== Фильтрация DNS (профиль аккаунта: обычный / реклама+трекеры / детский / свой резолвер) =====

    /** Подтянуть текущий профиль с ядра. Ошибка — не молчим: кнопка сама предлагает повторить. */
    private fun loadFiltering() {
        val button = findViewById<MaterialButton>(R.id.mayak_settings_dns)
        button.setText(R.string.mayak_settings_dns_loading)
        lifecycleScope.launch {
            val loaded = runCatching { session.settings(backend()) }.getOrNull()
            if (loaded == null) {
                accountSettings = null
                button.setText(R.string.mayak_settings_dns_unavailable)
                findViewById<TextView>(R.id.mayak_settings_dns_desc).visibility = View.GONE
                return@launch
            }
            accountSettings = loaded
            renderFiltering(loaded)
        }
    }

    /** Кнопка показывает ВЫБРАННЫЙ профиль (для своего резолвера — сразу его адреса), под ней — что он делает. */
    private fun renderFiltering(s: AccountSettings) {
        val button = findViewById<MaterialButton>(R.id.mayak_settings_dns)
        button.text = if (s.dnsMode == AccountSettings.DNS_CUSTOM && s.dnsCustom.isNotBlank()) {
            getString(R.string.mayak_dns_custom_value, s.dnsCustom)
        } else {
            getString(dnsLabel(s.dnsMode))
        }
        val desc = findViewById<TextView>(R.id.mayak_settings_dns_desc)
        desc.setText(dnsDescription(s.dnsMode))
        desc.visibility = View.VISIBLE
    }

    private fun dnsLabel(mode: String): Int = when (mode) {
        AccountSettings.DNS_ADBLOCK -> R.string.mayak_dns_adblock
        AccountSettings.DNS_FAMILY -> R.string.mayak_dns_family
        AccountSettings.DNS_CUSTOM -> R.string.mayak_dns_custom
        else -> R.string.mayak_dns_default
    }

    private fun dnsDescription(mode: String): Int = when (mode) {
        AccountSettings.DNS_ADBLOCK -> R.string.mayak_dns_adblock_desc
        AccountSettings.DNS_FAMILY -> R.string.mayak_dns_family_desc
        AccountSettings.DNS_CUSTOM -> R.string.mayak_dns_custom_desc
        else -> R.string.mayak_dns_default_desc
    }

    /** Выбор профиля. «Свой DNS-сервер» ведёт в диалог ввода адресов — сохранять пустой custom нельзя. */
    private fun showDnsDialog() {
        val current = accountSettings ?: return
        val modes = AccountSettings.MODES
        val labels = modes.map { getString(dnsLabel(it)) }.toTypedArray()
        val checked = modes.indexOf(current.dnsMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.mayak_settings_filtering)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val mode = modes[which]
                if (mode == AccountSettings.DNS_CUSTOM) showCustomDnsDialog(current.dnsCustom)
                else saveDns(mode, custom = null)
            }
            .setNegativeButton(R.string.mayak_cancel, null)
            .show()
    }

    /**
     * Ввод адресов своего резолвера. Адреса валидирует ядро (только публичные IP) — его текст ошибки
     * показываем ПОД полем и диалог НЕ закрываем: человек видит, что именно не так, прямо там, где
     * это исправлять. Закрывается диалог только после успешного сохранения.
     */
    private fun showCustomDnsDialog(prefill: String) {
        val view = layoutInflater.inflate(R.layout.dialog_mayak_dns, null)
        val layout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.mayak_dns_input_layout)
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.mayak_dns_input)
        input.setText(prefill)
        input.setSelection(input.text?.length ?: 0)
        layout.helperText = getString(R.string.mayak_dns_custom_hint)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.mayak_dns_custom)
            .setView(view)
            .setPositiveButton(R.string.mayak_ok, null) // слушатель ставим ниже: он не должен закрывать диалог
            .setNegativeButton(R.string.mayak_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                layout.error = null
                saveDns(
                    AccountSettings.DNS_CUSTOM,
                    input.text.toString().trim(),
                    onSaved = { dialog.dismiss() },
                    onInvalid = { msg -> layout.error = msg },
                )
            }
        }
        dialog.show()
    }

    /**
     * Сохранить профиль на ядре. custom = null — «адреса не трогать» (ядро сохранит прежние).
     * Пока идёт запрос, кнопка заблокирована: два быстрых тапа = две гонки за один и тот же профиль.
     *
     * onInvalid получает текст ядра о негодном вводе (400) — диалог ввода показывает его под полем.
     * Всё остальное (сеть, 5xx) — тостом: поля, к которому это относится, там нет.
     */
    private fun saveDns(
        mode: String,
        custom: String?,
        onSaved: () -> Unit = {},
        onInvalid: ((String) -> Unit)? = null,
    ) {
        val button = findViewById<MaterialButton>(R.id.mayak_settings_dns)
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                val saved = session.updateSettings(backend(), mode, custom)
                accountSettings = saved
                renderFiltering(saved)
                onSaved()
                Toast.makeText(this@MayakSettingsActivity, R.string.mayak_dns_saved, Toast.LENGTH_LONG).show()
            } catch (e: MayakApiException) {
                // 400 от ядра — это разбор ВВОДА («не IP-адрес», «адрес не публичный»): показываем как есть.
                val msg = e.message ?: "HTTP ${e.status}"
                if (e.status == 400 && onInvalid != null) onInvalid(msg)
                else Toast.makeText(
                    this@MayakSettingsActivity,
                    getString(R.string.mayak_dns_save_err, msg),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MayakSettingsActivity,
                    getString(R.string.mayak_dns_save_err, e.message ?: getString(R.string.mayak_settings_dns_unavailable)),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                button.isEnabled = true
            }
        }
    }

    // ===== Подписка: до какой даты действует доступ и сколько устройств занято =====

    /** Срок доступа с ядра (GET /v1/client/sync). Best-effort: нет сети — строку просто не показываем. */
    private fun loadSubscription() {
        lifecycleScope.launch {
            val st = runCatching { session.accountStatus(backend()) }.getOrNull() ?: return@launch
            val line = findViewById<TextView>(R.id.mayak_settings_subscription)
            // Текст один на всё приложение (MayakAccessLine) — тот же, что на главном экране.
            val access = MayakAccessLine.of(this@MayakSettingsActivity, st, withDevices = true)
            line.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this@MayakSettingsActivity,
                    if (access.alarming) R.color.mayak_red else R.color.mayak_on_surface,
                )
            )
            line.text = access.text
            line.visibility = View.VISIBLE
        }
    }

    /**
     * Подтягивает номер аккаунта с ядра, если его ещё нет в хранилище (у учётки номер не меняется,
     * поэтому это один запрос на установку). Не удалось — строка просто останется скрытой: ни
     * ошибки, ни пустого поля «Номер аккаунта: —», которое человек попробовал бы диктовать.
     */
    private fun loadAccountNumber() {
        lifecycleScope.launch {
            val shown = runCatching { session.accountNumber(backend()) }.getOrNull() ?: return@launch
            showAccountNumber(shown)
        }
    }

    /** Показ номера + копирование по нажатию. null/пусто — строку и подсказку прячем. */
    private fun showAccountNumber(shown: String?) {
        val row = findViewById<View>(R.id.mayak_settings_acctnum_row)
        val hint = findViewById<View>(R.id.mayak_settings_acctnum_hint)
        if (shown.isNullOrBlank()) {
            row.visibility = View.GONE
            hint.visibility = View.GONE
            return
        }
        findViewById<TextView>(R.id.mayak_settings_acctnum).text = shown
        // Копирование и по нажатию на всю строку, и по значку: значок 40dp — мелкая цель на телефоне,
        // а номер человек обычно жмёт по самим цифрам.
        val copy = { MayakAccountNumber.copy(this, shown) }
        row.setOnClickListener { copy() }
        findViewById<View>(R.id.mayak_settings_acctnum_copy).setOnClickListener { copy() }
        row.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    /**
     * Сбор и отправка диагностического лога на сервер (главное действие диагностики). Собираем
     * контекст устройства/сети + дамп logcat движка → POST /v1/client/diag-log. Требует входа.
     *
     * Если отправка ВСЁ РАВНО не проходит (и внутри туннеля, и мимо него — OutsideTunnel уже
     * подключён выше) — собранный запрос не пропадает: сохраняем его на диск (DiagLogPending) и
     * говорим об этом человеку. Ровно та ситуация, из-за которой владелец не смог пожаловаться
     * 2026-08-07: лог собрался, отправка упала, и раньше на этом всё заканчивалось молча.
     */
    private fun sendLog(button: MaterialButton) {
        val store = KeystoreSecureStore(this)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store))
        if (!session.hasToken()) {
            Toast.makeText(this, R.string.mayak_settings_send_log_need_login, Toast.LENGTH_LONG).show()
            return
        }
        val hosts = MayakHostList.effective(this, store.get(MayakActivity.KEY_SERVER))
        val backend = MayakBackend(HostProvider(hosts), bypassTunnel = OutsideTunnel.opener(this@MayakSettingsActivity))

        val original = button.text
        button.isEnabled = false
        button.setText(R.string.mayak_settings_send_log_sending)
        lifecycleScope.launch {
            // Сначала досылаем то, что осталось с прошлой неудачной попытки — иначе оно так и лежало
            // бы на диске, пока человек сам не нажмёт «Поделиться» (требование 2026-08-07).
            DiagLogPending.flush(this@MayakSettingsActivity, session, backend)
            var req: org.amnezia.awg.mayak.core.DiagLogRequest? = null
            val msg = try {
                req = DiagCollector.collect(
                    this@MayakSettingsActivity, direction = "", deviceId = session.deviceId(), source = "manual",
                    // Счётчики трафика снимаются с экземпляра туннеля; backend процесс-скоупный, так что
                    // новый GoTunnel читает статистику ТОГО ЖЕ живого туннеля (как в SpeedNotifier).
                    tunnel = GoTunnel(this@MayakSettingsActivity),
                )
                session.sendDiagLog(backend, req)
                getString(R.string.mayak_settings_send_log_ok)
            } catch (_: Exception) {
                // Не ушло — не теряем то, что уже собрали. Технический текст ошибки (HTTP-код и т.п.)
                // человеку тут не нужен: важно только, что лог цел и есть два способа его доставить.
                req?.let { DiagLogPending.save(this@MayakSettingsActivity, it) }
                getString(R.string.mayak_settings_send_log_saved)
            }
            button.isEnabled = true
            button.text = original
            refreshShareLogButton()
            Toast.makeText(this@MayakSettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Показать/спрятать «Поделиться логом» по факту наличия несданного файла на диске.
     *  Вместе с кнопкой показываем и ПОДПИСЬ: без неё на экране просто возникает новая кнопка,
     *  а объяснение («отправить не вышло, лог сохранён») живёт в тосте и через 3 секунды пропадает. */
    private fun refreshShareLogButton() {
        val visible = if (DiagLogPending.exists(this)) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.mayak_settings_share_log).visibility = visible
        findViewById<TextView>(R.id.mayak_settings_share_log_hint)?.apply {
            visibility = visible
            text = getString(R.string.mayak_settings_share_log_hint, BuildConfig.MAYAK_SUPPORT_EMAIL)
        }
    }

    /** Отдать сохранённый лог системному диалогу «Поделиться» — человек сам решает, в какой мессенджер. */
    private fun shareSavedLog() {
        val uri = DiagLogPending.shareUri(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.mayak_settings_share_log_title)))
    }

    /** Выход из аккаунта: гасим туннель, чистим сессию, возвращаемся на экран входа. */
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_logout))
            // Диалог был из одного заголовка и двух кнопок (аудит 2026-07-31, п. 18): человек не знал,
            // что порвётся туннель, сотрутся настройки и понадобится пароль. Теперь знает — и заодно
            // видит, что выход НЕ то же самое, что удаление аккаунта.
            .setMessage(getString(R.string.mayak_logout_msg))
            .setPositiveButton(getString(R.string.mayak_ok)) { _, _ ->
                val store = KeystoreSecureStore(this)
                val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store))
                val tunnel = GoTunnel(this)
                lifecycleScope.launch {
                    runCatching { tunnel.down() }
                    session.logout()
                    // Пресеты сплит-туннеля принадлежат аккаунту: следующий вошедший на этом телефоне
                    // не должен ни видеть, ни применять чужие правила (разбор 2026-07-27).
                    MayakPresets.clear(this@MayakSettingsActivity)
                    // Перезапускаем точку входа — без токена покажется экран логина.
                    val intent = Intent(this@MayakSettingsActivity, MayakActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /**
     * Удаление аккаунта из приложения (аудит 2026-07-31, п. 16).
     *
     * Требование Google Play: начать удаление человек должен уметь В приложении — ссылка «Открыть
     * кабинет» этому не отвечает. Ядро умело это давно (POST /v1/client/account/delete), кнопки не было.
     *
     * Пароль спрашивает ЯДРО, не мы: токен живёт 30 дней и мог уехать вместе с телефоном, а
     * уничтожение данных необратимо. Ошибся в пароле — аккаунт цел, и мы прямо это говорим (иначе
     * человек в необратимом сценарии не понимает, случилось что-нибудь или нет).
     */
    private fun confirmDeleteAccount() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.mayak_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wrapper = com.google.android.material.textfield.TextInputLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_delete_account))
            .setMessage(getString(R.string.mayak_delete_account_msg, session.email().orEmpty()))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.mayak_delete_account_confirm)) { _, _ ->
                deleteAccount(input.text?.toString().orEmpty())
            }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    private fun deleteAccount(password: String) {
        Toast.makeText(this, R.string.mayak_delete_account_deleting, Toast.LENGTH_SHORT).show()
        val tunnel = GoTunnel(this)
        lifecycleScope.launch {
            try {
                session.deleteAccount(backend(), password)
            } catch (e: Exception) {
                // Неверный пароль ядро помечает машинным признаком, чтобы клиент не спутал его с
                // протухшей сессией и не выкинул человека на экран входа (разбор 2026-07-27).
                val wrongPassword = e is MayakApiException && e.code == "wrong_password"
                Toast.makeText(
                    this@MayakSettingsActivity,
                    if (wrongPassword) getString(R.string.mayak_delete_account_wrong_password)
                    else getString(R.string.mayak_delete_account_failed, e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            // Аккаунта больше нет: гасим туннель, стираем пресеты и уходим на экран входа.
            runCatching { tunnel.down() }
            MayakPresets.clear(this@MayakSettingsActivity)
            Toast.makeText(this@MayakSettingsActivity, R.string.mayak_delete_account_done, Toast.LENGTH_LONG).show()
            val intent = Intent(this@MayakSettingsActivity, MayakActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }

    companion object {
        /** См. `MayakActivity.openErrorHelp()` — тап по надписи об отказе подключения ведёт сюда. */
        const val EXTRA_OPEN_DIAGNOSTICS = "mayak_open_diagnostics"
    }
}

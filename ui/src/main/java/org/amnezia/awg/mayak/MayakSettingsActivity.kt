// Настройки «Маяк»: выбор темы (свет/тёмная/системная) и языка. Тема — через MayakPrefs
// (AppCompatDelegate + персист), язык — через общий MayakLanguages-диалог.
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
// Переходы берём ПЛАТФОРМЕННЫЕ (android.transition), а не androidx: библиотеки androidx.transition
// в зависимостях модуля нет, и её появление ради одной анимации складывания — лишний вес.
import android.transition.AutoTransition
import android.transition.TransitionManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.fragment.AppListDialogFragment
import org.amnezia.awg.mayak.core.AccountSettings
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.looksLikeReferralCode
import org.amnezia.awg.mayak.core.referralFailure

class MayakSettingsActivity : AppCompatActivity() {

    // Сессия/хранилище нужны нескольким блокам экрана (фильтрация, подписка, диаг-лог, выход) —
    // держим одну пару на активити вместо трёх одинаковых конструкторов по месту.
    private val store by lazy { KeystoreSecureStore(this) }
    private val session by lazy { MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store)) }

    /** Текущие настройки аккаунта с ядра; null — ещё не загрузились или загрузка не удалась. */
    private var accountSettings: AccountSettings? = null

    /** Настоящая почта учётки со слов ядра; null — ещё не спрашивали ИЛИ почты у неё нет вовсе. */
    private var accountEmail: String? = null

    /** Что ядро ответило про почту дословно (null — ещё не отвечало, "" — почты нет). */
    private var accountEmailFromCore: String? = null

    /** Доступ ПРОБНЫЙ по последней сверке; null — ещё не спрашивали. От этого зависит текст про почту:
     *  привязка доводит пробный срок до полного, а платившему обещать этим нечего. */
    private var accountTrial: Boolean? = null

    /** Выключатели уведомлений с ядра (SPEC-0047); null — ещё не ответило или ручки там нет. */
    private var notifyPrefs: org.amnezia.awg.mayak.core.NotificationPrefs? = null

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

        // Складные разделы (правка владельца 17-08: «меню очень растянуто вниз»). С 21-08 (вечер)
        // НЕ РАСКРЫТ НИ ОДИН: то, за чем приходили в «Аккаунт» (номер и срок), теперь всегда видно
        // в карточке-удостоверении наверху, а раскрытый раздел стоил 375dp — больше половины
        // первого экрана. Свёрнутый список из семи разделов помещается на экран целиком.
        wireSection(R.id.mayak_sec_account_head, R.id.mayak_sec_account_body, R.id.mayak_sec_account_chevron)
        wireSection(R.id.mayak_sec_network_head, R.id.mayak_sec_network_body, R.id.mayak_sec_network_chevron)
        wireSection(R.id.mayak_sec_protection_head, R.id.mayak_sec_protection_body, R.id.mayak_sec_protection_chevron)
        wireSection(R.id.mayak_sec_notify_head, R.id.mayak_sec_notify_body, R.id.mayak_sec_notify_chevron)
        wireSection(R.id.mayak_sec_appearance_head, R.id.mayak_sec_appearance_body, R.id.mayak_sec_appearance_chevron)
        wireSection(R.id.mayak_sec_diag_head, R.id.mayak_sec_diag_body, R.id.mayak_sec_diag_chevron)
        wireSection(R.id.mayak_sec_referral_head, R.id.mayak_sec_referral_body, R.id.mayak_sec_referral_chevron)

        findViewById<MaterialButton>(R.id.mayak_settings_back).setOnClickListener {
            if (!collapseOpenSection()) {
                finish(); MayakTransitions.applyAxisReverse(this)
            }
        }
        // «Назад» с РАСКРЫТЫМ разделом сначала складывает его, и только потом уходит с экрана.
        //
        // Почему это не педантизм. Раскрытый раздел подводится под шапку (scrollSectionToTop), и на
        // экране остаётся заголовок + его содержимое — визуально это НОВЫЙ ЭКРАН. Человек жмёт
        // «Назад», ожидая вернуться к списку разделов, а вылетал из настроек целиком. NN/g описывает
        // ровно эту ловушку у складных разделов: чем убедительнее раскрытие выглядит переходом, тем
        // вернее по «Назад» ждут возврата на уровень выше.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (collapseOpenSection()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                MayakTransitions.applyAxisReverse(this@MayakSettingsActivity)
            }
        })
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
        // «Проверить связь» сама ничего не проверяет: подъём туннеля живёт на главном экране (там
        // разрешение VPN, состояние подключения и выбранная страна). Отсюда — только просьба:
        // возвращаемся на главный экран с флагом, и проверку заводит он. Заводить второй владелец
        // туннеля значило бы держать два места, которые могут поднять VPN, — а такое расходится.
        findViewById<MaterialButton>(R.id.mayak_settings_check_link).setOnClickListener {
            startActivity(
                Intent(this, MayakActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MayakActivity.EXTRA_RUN_LINK_CHECK, true)
            )
            finish()
        }
        findViewById<MaterialButton>(R.id.mayak_settings_send_log).setOnClickListener { sendLog(it as MaterialButton) }
        // «Поделиться логом» видна, только пока на диске лежит недоставленный лог с прошлой неудачной
        // попытки (0.3.99) — состояние могло смениться, пока экран был закрыт (пришли на новую сессию
        // после провала на прошлой), поэтому проверяем прямо тут, а не полагаемся на видимость из XML.
        findViewById<MaterialButton>(R.id.mayak_settings_share_log).setOnClickListener { shareSavedLog() }
        refreshShareLogButton()
        findViewById<MaterialButton>(R.id.mayak_settings_logout).setOnClickListener { confirmLogout() }
        // Версия сборки — тем же текстом, что на главном экране (одна строка на всё приложение).
        findViewById<android.widget.TextView?>(R.id.mayak_settings_version)?.text =
            getString(R.string.mayak_version_stamp, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        // Удаление аккаунта показываем только вошедшим: удалять нечего, а кнопка пугает.
        val deleteAccount = findViewById<MaterialButton>(R.id.mayak_settings_delete_account)
        if (session.hasToken()) deleteAccount.setOnClickListener { confirmDeleteAccount() }
        else deleteAccount.visibility = View.GONE

        // Под кем вошли (запрос владельца: в приложении не было видно аккаунта). Сначала — то, что
        // знаем без сети, потом строку уточнит ответ ядра (loadAccountCard).
        showAccountEmail(fromCore = null)
        // Номер аккаунта: сначала из хранилища (мгновенно, работает и без сети), потом — освежить.
        showAccountNumber(org.amnezia.awg.mayak.core.AccountNumber.display(store))
        findViewById<MaterialButton>(R.id.mayak_settings_cabinet).setOnClickListener {
            MayakCabinet.open(this)
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
        // Ящик сообщений (SPEC-0047): вход на экран из настроек — второй путь к нему помимо шапки
        // главного. Кнопка живёт в той же карточке, что и выключатели уведомлений: человек, который
        // ищет «где посмотреть, что мне писали», и человек, который ищет «как это выключить», —
        // приходят в одно и то же место.
        findViewById<MaterialButton>(R.id.mayak_settings_messages).setOnClickListener {
            MayakMessagesActivity.open(this)
        }

        if (session.hasToken()) {
            loadFiltering()
            loadSubscription()
            loadAccountCard()
            loadNotificationPrefs()
            loadReferral()
        } else {
            // Не вошли — выбирать DNS-профиль не из чего (менять нечего), прячем сам пункт.
            // Раньше пряталась целая карточка «Фильтрация»; 21-08 её слили с «Сетью», поэтому
            // прячем ровно кнопку и её подписи, а не раздел.
            findViewById<View>(R.id.mayak_settings_dns).visibility = View.GONE
            findViewById<View>(R.id.mayak_settings_dns_hint_row).visibility = View.GONE
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
            // Раздел теперь складной — мало доскроллить до него, надо ещё и раскрыть: иначе человек
            // с неудачным подключением приезжает к закрытой строке «Помощь и поддержка».
            openSection(R.id.mayak_sec_diag_head, R.id.mayak_sec_diag_body, R.id.mayak_sec_diag_chevron)
            scrollSectionToTop(findViewById(R.id.mayak_sec_diag_head))
        }
    }

    /**
     * Раздел-гармошка: по заголовку карточку складываем и раскладываем.
     *
     * Зачем: экран настроек был одним свитком на шесть экранов прокрутки — у каждого тумблера рядом
     * абзац пояснения, и человек, которому нужен номер аккаунта, пролистывал мимо всего (правка
     * владельца 17-08). Свёрнутый раздел — одна строка, весь список умещается на первом экране.
     *
     * Состояние НЕ запоминаем между заходами: экран должен открываться одинаково, а не «как я его
     * оставил в прошлый раз» — иначе человек, вернувшийся через неделю, видит другой экран и ищет
     * заново. Исключение — «Аккаунт»: он раскрыт всегда.
     */
    /** Все разделы экрана: заголовок → тело → шеврон. Нужен, чтобы открытый закрывал остальные. */
    private val sections = mutableListOf<Triple<View, View, ImageView?>>()

    private fun wireSection(headId: Int, bodyId: Int, chevronId: Int, open: Boolean = false) {
        val head = findViewById<View>(headId) ?: return
        val body = findViewById<View>(bodyId) ?: return
        val chevron = findViewById<ImageView>(chevronId)
        sections += Triple(head, body, chevron)
        setSectionOpen(head, body, chevron, open)
        head.setOnClickListener {
            val shown = body.visibility != View.VISIBLE
            // Складывание анимируем: скачок высоты на пол-экрана читается как «экран моргнул».
            (findViewById<View>(R.id.mayak_settings_content) as? ViewGroup)?.let {
                TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(160))
            }
            // Открытый раздел закрывает остальные (аккордеон). Иначе за сеанс человек раскрывает
            // три-четыре раздела, страница вырастает до 3–4 экранов, и дальше он ищет нужное
            // прокруткой — ровно то, на что владелец жаловался трижды. С одним открытым высота
            // страницы ограничена сверху: список разделов + самое большое тело.
            if (shown) {
                for ((h, b, c) in sections) {
                    if (h !== head && b.visibility == View.VISIBLE) setSectionOpen(h, b, c, false)
                }
            }
            setSectionOpen(head, body, chevron, shown)
            MayakHaptics.tap(head)
            if (shown) scrollSectionToTop(head)
        }
    }

    /**
     * Раскрыли раздел — подвести его заголовок под шапку.
     *
     * Иначе раскрытие «Помощи» после раскрытого «Аккаунта» уводит содержимое за нижний край, и
     * человек, только что нажавший на заголовок, видит ровно то же, что и до нажатия. Ждём конца
     * анимации складывания: до неё высоты ещё старые и прокрутка уедет не туда.
     */
    private fun scrollSectionToTop(head: View) {
        val scroll = findViewById<ScrollView>(R.id.mayak_settings_scroll) ?: return
        val content = findViewById<View>(R.id.mayak_settings_content) ?: return
        val header = findViewById<View>(R.id.mayak_settings_header)
        // Карточка раздела — прямой ребёнок колонки контента; заголовок лежит внутри неё.
        var card: View = head
        while (card.parent !== content) card = card.parent as? View ?: return
        // ⚠️ Задержка БОЛЬШЕ длительности анимации складывания (160 мс), и с запасом: пока
        // TransitionManager ведёт анимацию, ScrollView считает высоту содержимого по СТАРОЙ разметке
        // и обрезает прокрутку по старому максимуму. На 200 мс это ловилось через раз — раздел
        // «Внешний вид» оставался в середине экрана, и его тело уходило под нижний край (замер
        // 21-08). 320 мс попадает после конца анимации при любом порядке кадров.
        scroll.postDelayed({
            // Шапка закреплена ПОВЕРХ прокрутки, поэтому вычитаем её высоту: без этого заголовок
            // раздела уезжает ровно под неё.
            scroll.smoothScrollTo(0, (card.top - (header?.height ?: 0)).coerceAtLeast(0))
        }, 320)
    }

    /**
     * Складывает открытый раздел (он всегда один — аккордеон) и возвращает экран к началу списка.
     * true — раздел был открыт и закрыт, значит «Назад» уже отработал и уходить с экрана не надо.
     */
    private fun collapseOpenSection(): Boolean {
        val open = sections.firstOrNull { it.second.visibility == View.VISIBLE } ?: return false
        (findViewById<View>(R.id.mayak_settings_content) as? ViewGroup)?.let {
            TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(160))
        }
        setSectionOpen(open.first, open.second, open.third, false)
        // К началу списка: раздел мог быть подведён под шапку, и после складывания человек остался бы
        // смотреть на пустое место там, где только что было содержимое.
        findViewById<ScrollView>(R.id.mayak_settings_scroll)?.smoothScrollTo(0, 0)
        return true
    }

    /** Шеврон: 90° — свёрнуто (смотрит вниз), 270° — раскрыто (смотрит вверх). */
    private fun setSectionOpen(head: View, body: View, chevron: ImageView?, open: Boolean) {
        body.visibility = if (open) View.VISIBLE else View.GONE
        chevron?.rotation = if (open) 270f else 90f
        // Голосовому доступу нужно СЛОВО, а не поворот картинки: иначе строка «Сеть» ничем не
        // отличается от обычного заголовка и непонятно, что по ней можно нажать.
        head.contentDescription = getString(
            if (open) R.string.mayak_settings_section_collapse else R.string.mayak_settings_section_expand
        )
    }

    /** Раскрыть раздел и подвести к нему экран (приход по EXTRA_OPEN_DIAGNOSTICS). */
    private fun openSection(headId: Int, bodyId: Int, chevronId: Int) {
        val head = findViewById<View>(headId) ?: return
        val body = findViewById<View>(bodyId) ?: return
        setSectionOpen(head, body, findViewById(chevronId), true)
    }

    /** Первый onResume идёт сразу за onCreate — там всё уже запрошено, второй раз ходить незачем. */
    private var firstResume = true

    /**
     * Возврат на экран — перечитать учётку.
     *
     * Отсюда есть кнопка «Открыть кабинет», а в кабинете человек делает ровно то, что меняет эти
     * строки: привязывает почту, платит, продлевает. Возвращался он на ЗАСТЫВШИЙ экран — «Почта: не
     * привязана» и прежний срок, хотя на сервере уже другое (снято живьём 17-08: привязка почты
     * подняла пробный с 3 дней до 7, приложение об этом не узнало до перезапуска). Два лёгких
     * запроса на возврат — честная цена за экран, который не врёт.
     */
    override fun onResume() {
        super.onResume()
        if (firstResume) { firstResume = false; return }
        if (!session.hasToken()) return
        loadSubscription()
        loadAccountCard()
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

    // ===== Уведомления (SPEC-0047): категории и тихие часы =====

    /**
     * Подтянуть выключатели с ядра и показать карточку. Не получилось — карточку НЕ показываем
     * вовсе: ручки может ещё не быть (серверная половина едет отдельно), а тумблеры, которым некуда
     * сохраняться, — это интерфейс, который врёт про сохранение.
     */
    private fun loadNotificationPrefs() {
        lifecycleScope.launch {
            val prefs = runCatching { session.notificationPrefs(backend()) }.getOrNull() ?: return@launch
            MayakMessages.rememberPrefs(this@MayakSettingsActivity, prefs)
            notifyPrefs = prefs
            renderNotificationPrefs(prefs)
            findViewById<View>(R.id.mayak_settings_notify_card).visibility = View.VISIBLE
        }
    }

    // ===== Пригласить друга (SPEC-0049) =====

    /**
     * Подтянуть карточку приглашений. Не получилось или программа выключена — карточку НЕ показываем:
     * её включают из панели без выката, и раздел обязан исчезать у людей вслед за настройкой.
     */
    private fun loadReferral() {
        lifecycleScope.launch {
            val info = runCatching { session.referral(backend()) }.getOrNull() ?: return@launch
            if (!info.enabled) return@launch
            renderReferral(info)
            findViewById<View>(R.id.mayak_settings_referral_card).visibility = View.VISIBLE
        }
    }

    /** Разложить карточку по данным сервера. Суммы и сроки — ТОЛЬКО оттуда, своих цифр не рисуем. */
    private fun renderReferral(info: org.amnezia.awg.mayak.core.ReferralInfo) {
        findViewById<TextView>(R.id.mayak_settings_referral_terms).text = getString(
            R.string.mayak_referral_terms,
            // Срок — через plurals, а не «%d days»: владелец правит выдержку в панели, и на
            // значении 1 английский выдавал «1 days», а русский прятал склонение сокращением «дн.»
            // (найдено вычиткой английских строк 16-08).
            resources.getQuantityString(R.plurals.mayak_days, info.holdDays, info.holdDays),
            MayakReferral.money(info.inviteeKopecks),
            MayakReferral.money(info.inviterKopecks),
        )
        findViewById<TextView>(R.id.mayak_settings_referral_code).text = info.code
        findViewById<TextView>(R.id.mayak_settings_referral_stats).text = getString(
            R.string.mayak_referral_stats,
            info.invited,
            info.rewarded,
            MayakReferral.money(info.earnedKopecks),
        )
        // Копируем ССЫЛКУ, а не голый код: человек отправляет её в мессенджер, и по ссылке друг
        // попадёт куда надо, а код ему пришлось бы объяснять словами.
        val copyLink = View.OnClickListener {
            MayakReferral.copy(this, getString(R.string.mayak_referral_title), info.link)
            android.widget.Toast.makeText(this, R.string.mayak_referral_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.mayak_settings_referral_code_row).setOnClickListener(copyLink)
        findViewById<View>(R.id.mayak_settings_referral_copy).setOnClickListener(copyLink)
        findViewById<MaterialButton>(R.id.mayak_settings_referral_share).setOnClickListener {
            val text = getString(R.string.mayak_referral_share_text, info.link)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, text)
            runCatching { startActivity(android.content.Intent.createChooser(send, getString(R.string.mayak_referral_share))) }
        }

        // Поле чужого кода — только пока его есть смысл вводить: применяли уже / окно закрылось =
        // поле, которое заведомо ответит отказом. Окно считает СЕРВЕР, здесь только показываем срок.
        val box = findViewById<View>(R.id.mayak_settings_referral_apply_box)
        box.visibility = if (info.appliedCode) View.GONE else View.VISIBLE
        renderMyInvite(info)
        if (info.appliedCode) return
        val layout = findViewById<TextInputLayout>(R.id.mayak_settings_referral_input_layout)
        val field = findViewById<TextInputEditText>(R.id.mayak_settings_referral_input)
        // Срок — через mayak_days: подставленное числом «%d дн.» обходит склонение, и на единице
        // получалось «1 days»/«1 дн.» (тот же брак ловили на экране регистрации 13-08).
        layout.helperText = if (info.applyWindowDays > 0) {
            getString(
                R.string.mayak_referral_apply_window,
                resources.getQuantityString(R.plurals.mayak_days, info.applyWindowDays, info.applyWindowDays),
            )
        } else {
            null
        }
        field.doAfterTextChanged { layout.error = null }
        findViewById<MaterialButton>(R.id.mayak_settings_referral_apply).setOnClickListener {
            applyReferralCode(field.text?.toString().orEmpty(), layout, info)
        }
    }

    /**
     * Что с МОИМ приглашением — строка на месте спрятанного поля ввода.
     *
     * 🔴 Зачем. Раньше приложение просто прятало поле, и человек, применивший код, больше НИГДЕ не
     * видел ни обещанной суммы, ни условия, ни того, начислили ему уже или нет (найдено живой
     * проверкой 15-08; в кабинете была та же беда, там висела сухая фраза «код уже применён»).
     * Обещание, о котором нельзя вспомнить, работает как обман: человек ждёт денег и идёт в
     * поддержку.
     *
     * Все числа — С СЕРВЕРА. Своих цифр не рисуем и старое ядро переживаем молча: нет `myInvite` —
     * говорим то, что знаем точно, а не выдумываем сумму.
     */
    private fun renderMyInvite(info: org.amnezia.awg.mayak.core.ReferralInfo) {
        val view = findViewById<TextView>(R.id.mayak_settings_referral_mine)
        if (!info.appliedCode) {
            view.visibility = View.GONE
            return
        }
        val m = info.myInvite
        val text = when {
            m == null -> getString(R.string.mayak_referral_mine_plain)
            m.status == "reversed" -> getString(R.string.mayak_referral_mine_reversed)
            m.kopecks > 0 -> getString(R.string.mayak_referral_mine_rewarded, MayakReferral.money(m.kopecks))
            m.status == "capped" -> getString(R.string.mayak_referral_mine_capped)
            m.promisedKopecks <= 0 -> getString(R.string.mayak_referral_mine_plain)
            m.status == "qualified" && ripeDate(m.ripeAt) != null ->
                getString(R.string.mayak_referral_mine_ripening, MayakReferral.money(m.promisedKopecks), ripeDate(m.ripeAt))
            else -> getString(
                R.string.mayak_referral_mine_pending,
                MayakReferral.money(m.promisedKopecks),
                resources.getQuantityString(R.plurals.mayak_days, info.holdDays, info.holdDays),
            )
        }
        view.text = text
        view.visibility = View.VISIBLE
    }

    /** Дата созревания в языке телефона. Сервер шлёт время по RFC 3339; не разобрали — молчим. */
    private fun ripeDate(iso: String): String? {
        if (iso.isBlank()) return null
        return runCatching {
            java.time.OffsetDateTime.parse(iso)
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(
                    java.time.format.DateTimeFormatter
                        .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                        .withLocale(java.util.Locale.getDefault())
                )
        }.getOrNull()
    }

    /** Отправить чужой код. Отказ называем причиной, а не «не получилось»: см. ReferralOutcome. */
    private fun applyReferralCode(
        raw: String,
        layout: TextInputLayout,
        info: org.amnezia.awg.mayak.core.ReferralInfo,
    ) {
        if (!looksLikeReferralCode(raw)) {
            layout.error = getString(R.string.mayak_reg_invite_bad)
            return
        }
        lifecycleScope.launch {
            try {
                session.applyReferral(backend(), raw)
                android.widget.Toast.makeText(
                    this@MayakSettingsActivity,
                    getString(R.string.mayak_referral_apply_ok, MayakReferral.money(info.inviteeKopecks)),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                MayakInstallReferrer.clear(this@MayakSettingsActivity)
                // Перечитываем с сервера, а не дорисовываем сами: после применения меняются и
                // счётчики, и признак «код уже применён», и правда о них у ядра.
                loadReferral()
                findViewById<View>(R.id.mayak_settings_referral_apply_box).visibility = View.GONE
            } catch (e: Exception) {
                layout.error = getString(
                    R.string.mayak_referral_apply_failed,
                    MayakReferral.reason(this@MayakSettingsActivity, referralFailure(e)),
                )
            }
        }
    }

    private fun switchById(id: Int) =
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(id)

    /**
     * Расставить тумблеры. Слушатели вешаем ПОСЛЕ setChecked и снимаем перед ним: иначе первичная
     * отрисовка сама себя отправила бы на сервер, а включение «Новостей» ещё и спросило бы согласие
     * у человека, который экран только открыл.
     */
    private fun renderNotificationPrefs(prefs: org.amnezia.awg.mayak.core.NotificationPrefs) {
        val service = switchById(R.id.mayak_settings_notify_service)
        val news = switchById(R.id.mayak_settings_notify_news)
        val quiet = switchById(R.id.mayak_settings_notify_quiet)
        service.setOnCheckedChangeListener(null)
        news.setOnCheckedChangeListener(null)
        quiet.setOnCheckedChangeListener(null)
        service.isChecked = prefs.service
        news.isChecked = prefs.news
        quiet.isChecked = prefs.quietHours
        service.setOnCheckedChangeListener { _, checked -> saveNotificationPrefs(prefs().copy(service = checked)) }
        quiet.setOnCheckedChangeListener { _, checked -> saveNotificationPrefs(prefs().copy(quietHours = checked)) }
        news.setOnCheckedChangeListener { view, checked ->
            if (!checked) {
                // Выключение — сразу и без вопросов. Время согласия сервер НЕ стирает: доказательство
                // «согласие было» должно пережить отказ от него.
                saveNotificationPrefs(prefs().copy(news = false))
                return@setOnCheckedChangeListener
            }
            // Включение = согласие на рекламные сообщения (38-ФЗ ст. 18). Спрашиваем явно и
            // короткими словами; отказался — возвращаем тумблер на место, ничего не сохраняя.
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mayak_settings_notify_news)
                .setMessage(R.string.mayak_settings_notify_news_consent)
                .setPositiveButton(R.string.mayak_settings_notify_news_agree) { _, _ ->
                    saveNotificationPrefs(prefs().copy(news = true))
                }
                .setNegativeButton(R.string.mayak_cancel) { _, _ -> view.isChecked = false }
                .setOnCancelListener { view.isChecked = false }
                .show()
        }
    }

    /** Текущее состояние выключателей: то, что пришло с ядра, а до ответа — умолчания таблицы. */
    private fun prefs(): org.amnezia.awg.mayak.core.NotificationPrefs =
        notifyPrefs ?: org.amnezia.awg.mayak.core.NotificationPrefs()

    /**
     * Сохранить выключатели. Не ушло — говорим об этом и ВОЗВРАЩАЕМ тумблеры к тому, что реально
     * лежит на сервере: тумблер, оставшийся в новом положении после неудачи, — это ровно тот класс
     * вранья, из-за которого экран регистрации месяц говорил «код отправлен».
     */
    private fun saveNotificationPrefs(update: org.amnezia.awg.mayak.core.NotificationPrefs) {
        lifecycleScope.launch {
            val ok = runCatching { session.updateNotificationPrefs(backend(), update) }.isSuccess
            if (ok) {
                notifyPrefs = update
                MayakMessages.rememberPrefs(this@MayakSettingsActivity, update)
            } else {
                Toast.makeText(
                    this@MayakSettingsActivity,
                    R.string.mayak_settings_notify_save_err,
                    Toast.LENGTH_LONG,
                ).show()
                renderNotificationPrefs(prefs())
            }
        }
    }

    // ===== Подписка: до какой даты действует доступ и сколько устройств занято =====

    /** Срок доступа с ядра (GET /v1/client/sync). Best-effort: нет сети — строку просто не показываем. */
    private fun loadSubscription() {
        lifecycleScope.launch {
            val st = runCatching { session.accountStatus(backend()) }.getOrNull() ?: return@launch
            // Признак «пробный» приезжает ЗДЕСЬ, а строка про почту рисуется в другом запросе —
            // поэтому, узнав его, перерисовываем и её: иначе прибавка к пробному сроку показывалась
            // бы через раз, в зависимости от того, чей ответ пришёл вторым.
            accountTrial = st.trial
            if (accountEmailFromCore != null) showAccountEmail(fromCore = accountEmailFromCore)
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
            refreshIdCard()
        }
    }

    /**
     * Подтягивает номер аккаунта с ядра, если его ещё нет в хранилище (у учётки номер не меняется,
     * поэтому это один запрос на установку). Не удалось — строка просто останется скрытой: ни
     * ошибки, ни пустого поля «Номер аккаунта: —», которое человек попробовал бы диктовать.
     */
    /**
     * Карточка учётки с ядра: номер И настоящая почта. Одним запросом, потому что показываются они
     * рядом и порознь смысла не имеют. Любой отказ проглатываем: на экране остаётся то, что знали
     * без сети, — это не тот блок, из-за которого стоит ронять настройки.
     */
    private fun loadAccountCard() {
        lifecycleScope.launch {
            val info = runCatching { session.accountCard(backend()) }.getOrNull() ?: return@launch
            // Номер берём из ХРАНИЛИЩА, а не из ответа: пустое поле в ответе означает «ядро старее
            // правки», а не «номера больше нет», и сохранённый номер оно не стирает (AccountNumber.remember).
            showAccountNumber(org.amnezia.awg.mayak.core.AccountNumber.display(store))
            accountEmail = info.email?.takeIf { it.isNotBlank() }
            accountEmailFromCore = info.email.orEmpty()
            showAccountEmail(fromCore = accountEmailFromCore)
        }
    }

    /**
     * Чем называть учётку в вопросах-подтверждениях: почтой, если она есть, иначе НОМЕРОМ с дефисами
     * (то, что человек видит на этом же экране). Введённый логин — последний запасной вариант: у
     * безпочтового это тот же номер, но без разметки.
     */
    private fun accountLabel(): String =
        accountEmail
            ?: session.loginName()?.takeIf { it.contains('@') }
            ?: org.amnezia.awg.mayak.core.AccountNumber.display(store)
            ?: session.loginName().orEmpty()

    /**
     * Строка «Почта: …» в карточке аккаунта.
     *
     * 🔴 Раньше сюда печаталось ВВЕДЁННОЕ в поле входа, и у вошедшего номером получалась «Почта:
     * 848681728» — приложение называло почтой то, что ею не является (ревизия 12-08). Теперь:
     *   • [fromCore] непусто — показываем настоящую почту учётки;
     *   • [fromCore] пустая строка (ядро ответило «почты нет») — говорим прямо, что она не привязана,
     *     и что её можно добавить: кнопка кабинета стоит тут же, а привязка изнутри кабинета живая с 12-08;
     *   • [fromCore] == null (ядро ещё не ответило или сети нет) — показываем введённый логин, но
     *     ТОЛЬКО если он похож на почту; иначе строку прячем, чтобы не соврать. Человек в этот
     *     момент не остаётся без опознания: ниже стоит его номер, и он есть в хранилище с прошлого раза.
     */
    private fun showAccountEmail(fromCore: String?) {
        val row = findViewById<TextView>(R.id.mayak_settings_account)
        if (!session.hasToken()) {
            row.text = getString(R.string.mayak_settings_account, getString(R.string.mayak_settings_account_none))
            row.visibility = View.VISIBLE
            return
        }
        val shown = when {
            fromCore == null -> session.loginName()?.takeIf { it.contains('@') }
            fromCore.isNotBlank() -> fromCore
            else -> null
        }
        when {
            shown != null -> {
                row.text = getString(R.string.mayak_settings_account, shown)
                row.visibility = View.VISIBLE
            }
            // Ядро ответило «почты нет» — это не пустота экрана, а осмысленное состояние.
            fromCore != null -> {
                row.text = getString(
                    if (accountTrial == true) R.string.mayak_settings_account_no_email_trial
                    else R.string.mayak_settings_account_no_email
                )
                row.visibility = View.VISIBLE
            }
            else -> row.visibility = View.GONE
        }
    }

    /** Показ номера + копирование по нажатию. null/пусто — строку и подсказку прячем. */
    private fun showAccountNumber(shown: String?) {
        val row = findViewById<View>(R.id.mayak_settings_acctnum_row)
        val hint = findViewById<View>(R.id.mayak_settings_acctnum_hint)
        if (shown.isNullOrBlank()) {
            row.visibility = View.GONE
            hint.visibility = View.GONE
            refreshIdCard()
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
        refreshIdCard()
    }

    /**
     * Карточка-удостоверение наверху экрана прячется целиком, когда показывать нечего (не вошли —
     * нет ни номера, ни срока). Пустая карточка-рамка выглядела бы как сбой загрузки.
     */
    private fun refreshIdCard() {
        val card = findViewById<View>(R.id.mayak_settings_idcard) ?: return
        val num = findViewById<View>(R.id.mayak_settings_acctnum_row)
        val sub = findViewById<View>(R.id.mayak_settings_subscription)
        val any = num?.visibility == View.VISIBLE || sub?.visibility == View.VISIBLE
        card.visibility = if (any) View.VISIBLE else View.GONE
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
        val pending = DiagLogPending.exists(this)
        val visible = if (pending) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.mayak_settings_share_log).visibility = visible
        findViewById<TextView>(R.id.mayak_settings_share_log_hint)?.apply {
            visibility = visible
            text = getString(R.string.mayak_settings_share_log_hint, BuildConfig.MAYAK_SUPPORT_EMAIL)
        }
        // Несданный лог на диске — единственная кнопка, которая появляется САМА. Внутри свёрнутого
        // раздела её никто не увидит, поэтому раздел в этом случае раскрываем.
        if (pending) openSection(R.id.mayak_sec_diag_head, R.id.mayak_sec_diag_body, R.id.mayak_sec_diag_chevron)
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
                    // Снять адрес доставки пуша ДО выхода: ядро узнаёт устройство по токену сессии,
                    // после logout() снимать уже нечем — и телефон продолжил бы получать толчки по
                    // чужой учётке. Локальную отметку функция стирает в любом случае.
                    MayakPush.onLogout(this@MayakSettingsActivity)
                    session.logout()
                    // Пресеты сплит-туннеля принадлежат аккаунту: следующий вошедший на этом телефоне
                    // не должен ни видеть, ни применять чужие правила (разбор 2026-07-27).
                    MayakPresets.clear(this@MayakSettingsActivity)
                    // Ящик — тоже про КОНКРЕТНУЮ учётку: счётчик непрочитанного и «о чём уже
                    // уведомляли» не должны пережить выход и достаться следующему вошедшему.
                    MayakMessages.clear(this@MayakSettingsActivity)
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
            // Называем учётку тем, что человек про себя знает: почтой, если она есть, иначе НОМЕРОМ
            // (с дефисами — так он и записан у него на экране). Раньше сюда шёл введённый логин, и
            // безпочтовый читал «Аккаунт 848681728 будет удалён» — свой же номер в чужом формате.
            .setMessage(getString(R.string.mayak_delete_account_msg, accountLabel()))
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
            // Аккаунта больше нет: гасим туннель, стираем пресеты и ящик, уходим на экран входа.
            runCatching { tunnel.down() }
            MayakPresets.clear(this@MayakSettingsActivity)
            MayakMessages.clear(this@MayakSettingsActivity)
            // Аккаунта больше нет — снимать адрес доставки на ядре не у кого, но забыть отправленный
            // адрес обязаны: иначе следующий вошедший на этом телефоне сочтёт его уже отправленным.
            MayakPush.onLogout(this@MayakSettingsActivity)
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

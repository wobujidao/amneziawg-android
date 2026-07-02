// Экран «Маяк VPN» (бета MVP). Брендовый дизайн на XML-разметке (Material 3):
// вход — логотип-маяк + название + карточка с полями логин/пароль, кнопки «Войти», «Сканировать QR»,
// «Вставить рег-ссылку» (в ссылке зашиты адрес ядра + логин/пароль — юзеру ничего вводить не надо).
// Главный экран — список стран → подключение со сквозной пробой и авто-резервом (прямой → резерв).
// Тема следует системе (DayNight) или ручному выбору (MayakPrefs); язык — ru/be/kk/uz/en/de/fr.
package org.amnezia.awg.mayak

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.ImageViewCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.mayak.core.Direction
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend
import org.amnezia.awg.mayak.core.NoReachableHostException

class MayakActivity : AppCompatActivity() {

    private lateinit var store: KeystoreSecureStore
    private lateinit var session: MayakSession
    private lateinit var tunnel: GoTunnel
    private val probe = IpifyProbe()

    private var backend: MayakBackend? = null
    private var pendingConnect: Direction? = null

    private lateinit var status: TextView
    private var dirsContainer: LinearLayout? = null

    // --- состояние главного экрана (Happ-стиль) ---
    private enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }
    private var connState = ConnState.DISCONNECTED
    private var isHomeShown = false // главный экран показан (для пересинхронизации состояния в onResume)
    private var connectJob: Job? = null // корутина текущего подключения — чтобы можно было ОТМЕНИТЬ тапом
    // Кэш конфигов /connect по направлению живёт в MayakSession (процесс-скоупный, ПЕРЕЖИВАЕТ пересоздание
    // Activity) — предзагружается при выборе страны, берётся ОДНОРАЗОВО в момент коннекта (нет
    // переиспользования устаревшего lease; провал → след. коннект тянет свежий). Раньше это было поле
    // Activity → умирало при смене темы и /connect гонялся заново (баг: смена темы дёргала сеть).
    // preloadJob отменяет предыдущую предзагрузку при быстром переключении стран.
    private var preloadJob: Job? = null
    private var directions: List<Direction> = emptyList()
    private var selectedDir: Direction? = null
    private val rowViews = mutableListOf<View>()
    private val pingResults = mutableMapOf<Long, Int?>() // dir.id → мс (null = недоступен; нет ключа = ещё меряем)
    private var pingJob: Job? = null
    private var timerJob: Job? = null
    private var sessionStartElapsed = 0L

    // вьюхи круга/таймера (на главном экране)
    private var connectCircle: View? = null
    private var connectIcon: ImageView? = null
    private var connectGlow: View? = null
    private var timerView: TextView? = null
    private var ipView: TextView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var glowBreath: ObjectAnimator? = null
    private var rippleView: RippleView? = null
    private var networkBg: NetworkBackgroundView? = null

    // согласие на VPN → продолжаем отложенное подключение
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val dir = pendingConnect
            pendingConnect = null
            if (result.resultCode == RESULT_OK && dir != null) {
                doConnect(dir)
            } else {
                renderState(ConnState.DISCONNECTED)
                setStatus(getString(R.string.mayak_err_no_vpn_perm))
            }
        }

    // сканер QR (zxing) → разбираем как регистрационную ссылку
    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleRegLink(it) }
    }

    // POST_NOTIFICATIONS (API 33+) для уведомления «Подключено». Если выдали во время активного
    // коннекта — показываем уведомление сразу; отказ не критичен (просто не будет уведомления).
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && tunnel.isUp()) MayakNotification.show(this, GoTunnel.connectedLabel)
        }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = KeystoreSecureStore(this)
        session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store))
        tunnel = GoTunnel(this)

        if (session.hasToken()) {
            backend = MayakBackend(hostProvider())
            showHome(); loadDirections()
        } else {
            showLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        networkBg?.startAnimation() // фон оживает, только пока экран виден
        // при возврате на «Подключение…» возобновляем волны и НЕ трогаем состояние (оно переходное)
        if (connState == ConnState.CONNECTING) { rippleView?.startWaves(); return }
        // Пересинхронизируемся с фактическим состоянием НАШЕГО туннеля: он мог измениться, пока
        // приложение было свёрнуто (сам отвалился, или юзер включил VPN другим приложением → наш
        // VpnService погашен). Так экран всегда честно отражает реальность на возврате.
        if (isHomeShown) syncConnStateFromTunnel()
    }

    override fun onPause() {
        networkBg?.stopAnimation() // экономим, когда экран не на переднем плане
        rippleView?.stopWaves()
        super.onPause()
    }

    /**
     * Список адресов ядра. По умолчанию — публичный домен + IP-фолбэк (домен первым, при
     * недоступности :core сам переключится на IP). Рег-ссылка/QR могут сохранить свой адрес
     * (KEY_SERVER) — тогда используем его (а IP-фолбэк добавляем как страховку).
     */
    private fun hostProvider(): HostProvider {
        val saved = store.get(KEY_SERVER)?.trimEnd('/')
        val hosts = if (saved != null && saved !in DEFAULT_HOSTS) listOf(saved) + DEFAULT_HOSTS
        else DEFAULT_HOSTS
        return HostProvider(hosts)
    }

    // --- экран входа: логотип + название + карточка логин/пароль + QR + рег-ссылка ---

    private fun showLogin() {
        isHomeShown = false
        setContentView(R.layout.activity_mayak_login)
        dirsContainer = null
        status = findViewById(R.id.mayak_status)

        val emailField = findViewById<TextInputEditText>(R.id.mayak_login)
        val passField = findViewById<TextInputEditText>(R.id.mayak_password)

        setupThemeButton()
        findViewById<MaterialButton>(R.id.mayak_language_button).setOnClickListener { MayakLanguages.showDialog(this) }

        findViewById<MaterialButton>(R.id.mayak_sign_in).setOnClickListener {
            val email = emailField.text?.toString()?.trim().orEmpty()
            val pass = passField.text?.toString().orEmpty()
            if (email.isBlank() || pass.isBlank()) {
                setStatus(getString(R.string.mayak_err_fill_login)); return@setOnClickListener
            }
            doSignIn(email, pass)
        }
        // Регистрация и личный кабинет — в вебе (там же подтверждение email).
        findViewById<MaterialButton>(R.id.mayak_register).setOnClickListener { openUrl(CABINET_URL) }
        findViewById<MaterialButton>(R.id.mayak_scan_qr).setOnClickListener {
            scanQr.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false))
        }
        findViewById<MaterialButton>(R.id.mayak_paste_link).setOnClickListener { showPasteLinkDialog() }
    }

    /**
     * Разбор регистрационной ссылки mayak://reg?email=..&password=..[&server=..] → автологин.
     * email — новый параметр (login оставлен как алиас для совместимости). server необязателен:
     * без него используем дефолтные адреса (домен + IP).
     */
    private fun handleRegLink(raw: String) {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull()
        val server = uri?.getQueryParameter("server")?.trimEnd('/')
        val email = uri?.getQueryParameter("email") ?: uri?.getQueryParameter("login")
        val password = uri?.getQueryParameter("password")
        if (uri?.scheme != "mayak" || email.isNullOrBlank() || password.isNullOrBlank()) {
            setStatus(getString(R.string.mayak_err_bad_link)); return
        }
        doSignIn(email, password, serverOverride = server?.takeIf { it.isNotBlank() })
    }

    private fun showPasteLinkDialog() {
        val input = TextInputEditText(this).apply { hint = getString(R.string.mayak_paste_link_hint) }
        val wrapper = TextInputLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        // предзаполним из буфера обмена, если там ссылка
        clipboardText()?.let { if (it.startsWith("mayak://")) input.setText(it) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_paste_link_title))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.mayak_ok)) { _, _ -> handleRegLink(input.text.toString()) }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /**
     * Кнопка-переключатель темы в шапке (есть и на входе, и на главном).
     * Иконка отражает текущий режим (солнце/луна/авто); тап — цикл
     * Системная → Светлая → Тёмная → … с пересозданием активити.
     */
    private fun setupThemeButton() {
        val btn = findViewById<MaterialButton>(R.id.mayak_theme_button) ?: return
        btn.setIconResource(MayakPrefs.iconFor(MayakPrefs.themeMode(this)))
        btn.setOnClickListener {
            val next = MayakPrefs.nextMode(MayakPrefs.themeMode(this))
            Toast.makeText(
                this,
                "${getString(R.string.mayak_theme)}: ${getString(MayakPrefs.labelFor(next))}",
                Toast.LENGTH_SHORT
            ).show()
            MayakPrefs.setThemeMode(this, next) // пересоздаст активити → иконка обновится при пересборке
            recreate()
        }
    }

    /** Вход по email. serverOverride (из рег-ссылки) сохраняем как приоритетный адрес ядра. */
    private fun doSignIn(email: String, password: String, serverOverride: String? = null) {
        if (serverOverride != null) store.put(KEY_SERVER, serverOverride)
        backend = MayakBackend(hostProvider())
        setStatus(getString(R.string.mayak_status_signing_in))
        lifecycleScope.launch {
            try {
                session.login(backend!!, email, password)
                showHome(); loadDirections(forceRefresh = true)
            } catch (e: MayakApiException) {
                when (e.status) {
                    403 -> showEmailNotVerified()
                    401 -> setStatus(getString(R.string.mayak_err_bad_creds))
                    else -> setStatus(humanError(e))
                }
            } catch (e: Exception) { setStatus(humanError(e)) }
        }
    }

    /** 403 email_not_verified: понятное сообщение + предложение открыть кабинет для подтверждения. */
    private fun showEmailNotVerified() = runOnUiThread {
        setStatus(getString(R.string.mayak_err_email_not_verified))
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.mayak_err_email_not_verified))
            .setPositiveButton(getString(R.string.mayak_open_cabinet)) { _, _ -> openUrl(CABINET_URL) }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    /** Открыть URL во внешнем браузере (кабинет/политика/условия). */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { setStatus(getString(R.string.mayak_err_bad_link)) }
    }

    // --- главный экран (Happ-стиль): круг-подключение + список стран с флагами ---

    private fun showHome() {
        isHomeShown = true
        setContentView(R.layout.activity_mayak_home)
        status = findViewById(R.id.mayak_status)
        dirsContainer = findViewById(R.id.mayak_dirs_container)
        // Кнопка «Обновить» — явно перетянуть список стран с сервера (новые направления без перелогина).
        findViewById<View?>(R.id.mayak_refresh_dirs)?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            loadDirections(forceRefresh = true)
        }
        connectCircle = findViewById(R.id.mayak_connect_circle)
        connectIcon = findViewById(R.id.mayak_connect_icon)
        connectGlow = findViewById(R.id.mayak_connect_glow)
        timerView = findViewById(R.id.mayak_timer)
        ipView = findViewById(R.id.mayak_ip)
        rippleView = findViewById(R.id.mayak_ripple)
        networkBg = findViewById(R.id.mayak_network_bg)
        // волны стартуют от края круга (176dp/2)
        rippleView?.coreRadiusPx = 88f * resources.displayMetrics.density

        setupThemeButton()
        findViewById<MaterialButton>(R.id.mayak_settings_button).setOnClickListener {
            startActivity(Intent(this, MayakSettingsActivity::class.java))
            MayakTransitions.applyAxis(this) // плавный переход к настройкам
        }

        // Тап с press-feedback: лёгкое сжатие 0.96 + haptic-tick, затем toggle.
        connectCircle?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pressSqueeze(v)
            toggleConnect()
        }

        // Восстанавливаем состояние круга после пересоздания Activity (смена темы/языка, возврат
        // в приложение при живом туннеле). tunnel.isUp() честен — backend процесс-скоупный (GoTunnel),
        // состояние НЕ теряется. Таймер стартует с фактического момента НАШЕГО коннекта, а не «с возврата».
        connState = if (tunnel.isUp()) ConnState.CONNECTED else ConnState.DISCONNECTED
        if (connState == ConnState.CONNECTED) {
            startTimer()
            MayakNotification.show(this, GoTunnel.connectedLabel) // персист-метка направления
        } else {
            MayakNotification.clear(this)
        }
        renderState(connState)
        fadeInContent() // тонкий fade-through при заходе на главный (login→home)
    }

    /** Лёгкий fade-through контента экрана (вместо мгновенной подмены setContentView). */
    private fun fadeInContent() {
        if (reducedMotion()) return
        val root = findViewById<View>(android.R.id.content)
        root?.let {
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(280).start()
        }
    }

    /** Лёгкое сжатие круга при нажатии (даёт тактильную «кнопочность»). Уважает reduced-motion. */
    private fun pressSqueeze(v: View) {
        if (reducedMotion()) return
        v.animate().cancel()
        v.scaleX = 0.96f; v.scaleY = 0.96f
        v.animate().scaleX(1f).scaleY(1f).setDuration(160)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    /** Системный «убрать анимацию» (Settings.Global.ANIMATOR_DURATION_SCALE == 0). */
    private fun reducedMotion(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /**
     * Загрузка направлений. По умолчанию берёт кэш (in-memory переживает пересоздание Activity при
     * смене темы → сеть не дёргается). forceRefresh=true — принудительный рефетч (после логина или
     * фейловера). «Загрузку…» показываем только когда реально идём в сеть, без мигания при кэше.
     */
    // loadDirections — cache-then-refresh: мгновенно показываем кэш (если есть), затем ВСЕГДА тянем свежий
    // список с сервера. Новые направления появляются сами, БЕЗ перелогина (баг владельца 2026-06-28: кэш
    // залипал до выхода/входа). Перерисовываем только когда список реально изменился и мы отключены —
    // чтобы не дёргать UI при активном подключении.
    private fun loadDirections(forceRefresh: Boolean = false) {
        val b = backend ?: return
        if (!session.hasCachedDirections()) setStatus(getString(R.string.mayak_status_loading))
        lifecycleScope.launch {
            try {
                // 1) мгновенный показ кэша (быстрый UI), если список ещё пуст
                if (directions.isEmpty() && session.hasCachedDirections()) {
                    renderDirections(session.directions(b, false))
                }
                // 2) свежий список с сервера (обновляет кэш) — НО не при простом пересоздании Activity.
                // Смена темы (recreate) в пределах TTL → кэш свеж → в сеть НЕ идём (баг владельца 06-27:
                // сеть дёргалась даже на смене темы). Устарел/переоткрытие/явный рефреш → тянем свежий,
                // новые направления появляются сами без перелогина (баг владельца 06-28).
                if (forceRefresh || !session.directionsFresh(DIRECTIONS_TTL_MS)) {
                    val fresh = session.directions(b, true)
                    val changed = fresh.map { it.id } != directions.map { it.id }
                    if (directions.isEmpty() || (changed && connState == ConnState.DISCONNECTED)) {
                        renderDirections(fresh)
                    }
                }
            } catch (e: Exception) {
                if (directions.isEmpty()) setStatus(humanError(e))
            }
        }
    }

    /** Перерисовать список стран + восстановить выбор (последняя выбранная, иначе первая).
     *  measure=true → после отрисовки замерить пинг до серверов и пересортировать по нему (только когда
     *  отключены). measure=false — просто отрисовать в данном порядке (используется при пере-сортировке). */
    private fun renderDirections(dirs: List<Direction>, measure: Boolean = true) {
        directions = dirs
        val container = dirsContainer ?: return
        container.removeAllViews()
        rowViews.clear()
        if (dirs.isEmpty()) {
            setStatus(getString(R.string.mayak_err_empty_dirs)); return
        }
        for (d in dirs) {
            val row = countryRow(d)
            container.addView(row)
            rowViews.add(row)
        }
        val lastId = MayakPrefs.lastDirectionId(this@MayakActivity)
        val initial = dirs.firstOrNull { it.id == lastId } ?: dirs.first()
        selectDir(initial)
        if (connState == ConnState.DISCONNECTED) {
            setStatus(getString(R.string.mayak_status_disconnected))
            if (measure) measureAndSortPings()
        }
    }

    /** Текст пинга для строки: «…» пока меряем, «—» недоступен, «42 мс» — задержка. */
    private fun pingText(d: Direction): String {
        if (d.pingHost == null) return ""
        if (!pingResults.containsKey(d.id)) return "…"
        val ms = pingResults[d.id] ?: return "—"
        return getString(R.string.mayak_ping_ms, ms)
    }

    /** Замерить ICMP-пинг до серверов всех направлений (параллельно) и пересортировать список по ping
     *  (меньше — выше). Только когда отключены (иначе пинг шёл бы через туннель = неверно). Идёт напрямую
     *  к IP сервера (ping_host из ядра) — честная задержка устройство→сервер. */
    private fun measureAndSortPings() {
        val toPing = directions.filter { it.pingHost != null }
        if (toPing.isEmpty()) return
        pingJob?.cancel()
        pingJob = lifecycleScope.launch {
            val results = toPing.map { d ->
                async { d.id to MayakPing.ping(d.pingHost!!) }
            }.awaitAll()
            results.forEach { (id, ms) -> pingResults[id] = ms }
            if (connState != ConnState.DISCONNECTED) return@launch // подключились за время замера — не трогаем UI
            // пересортировать по ping (недоступные/неизмеренные — в конец), показать мс
            val sorted = directions.sortedBy { pingResults[it.id] ?: Int.MAX_VALUE }
            renderDirections(sorted, measure = false)
        }
    }

    /** Строка-страна: ВЕКТОРНЫЙ флаг + название + шеврон; тап = выбор (без подключения). */
    private fun countryRow(d: Direction): View {
        val container = dirsContainer
        val row = LayoutInflater.from(this).inflate(R.layout.mayak_country_row, container, false)
        row.findViewById<ImageView>(R.id.mayak_row_flag).setImageResource(MayakFlags.drawableForCode(d.code))
        row.findViewById<TextView>(R.id.mayak_row_name).text = d.displayLabel()
        row.findViewById<TextView>(R.id.mayak_row_ping).text = pingText(d)
        row.tag = d.id
        row.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            selectDir(d)
        }
        return row
    }

    /** Выбрать страну: подсветить строку, запомнить выбор. Не подключает. */
    private fun selectDir(d: Direction) {
        selectedDir = d
        MayakPrefs.setLastDirectionId(this, d.id)
        // ПРЕДЗАГРУЗКА конфига /connect заранее (тёплый кэш к моменту коннекта). M4: отменяем предыдущую
        // предзагрузку — быстрое переключение стран не плодит параллельные /connect. Кэш живёт в session
        // (переживает смену темы) → если он уже тёплый, повторно /connect НЕ гоняем (смена темы молчит).
        preloadJob?.cancel()
        preloadJob = backend?.takeIf { !session.hasCachedConnect(d.id) }
            ?.let { b -> lifecycleScope.launch { runCatching { session.preloadConnect(b, d) } } }
        for (row in rowViews) {
            val isSel = (row.tag as? Long) == d.id
            row.setBackgroundResource(if (isSel) R.drawable.mayak_row_selected else android.R.color.transparent)
            if (isSel && !reducedMotion()) {
                row.alpha = 0.6f
                row.animate().alpha(1f).setDuration(150).start()
            }
        }
    }

    /** Тап по кругу: подключиться к выбранной стране или отключиться. */
    private fun toggleConnect() {
        when (connState) {
            ConnState.CONNECTED -> disconnect()
            ConnState.CONNECTING -> cancelConnect() // тап во время подключения = ОТМЕНА (а не «игнор»/повторный коннект)
            ConnState.DISCONNECTED -> {
                val d = selectedDir
                if (d == null) { setStatus(getString(R.string.mayak_select_country_first)); return }
                connectTo(d)
            }
        }
    }

    /** Отмена идущего подключения: гасим корутину коннекта + туннель, возвращаем экран в DISCONNECTED. */
    private fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        pendingConnect = null
        lifecycleScope.launch { runCatching { tunnel.down() } }
        stopTimer()
        MayakNotification.clear(this)
        renderState(ConnState.DISCONNECTED)
        setStatus(getString(R.string.mayak_status_cancelled))
    }

    private fun connectTo(d: Direction) {
        maybeRequestNotifPermission() // спросим разрешение на уведомление в момент коннекта (естественный контекст)
        val prepare = GoBackend.VpnService.prepare(this)
        if (prepare != null) {
            pendingConnect = d
            renderState(ConnState.CONNECTING)
            vpnPermission.launch(prepare)
        } else doConnect(d)
    }

    private fun doConnect(d: Direction) {
        val b = backend ?: return
        renderState(ConnState.CONNECTING)
        setStatus(getString(R.string.mayak_status_connecting, d.name))
        connectJob = lifecycleScope.launch {
            try {
                // Конфиг берём из ПРЕДЗАГРУЖЕННОГО кэша (наполняется при выборе страны), чтобы в момент
                // подключения НЕ дёргать api.mayakvpn.ru: РФ-DPI (сотовая) палит наш VPN-домен в TLS/DNS
                // рядом с хендшейком и режет туннель. См. memory mobile-dpi-api-domain-leak-2026-06-28.
                val paths = session.takeCachedConnect(d.id) ?: session.connect(b, d) // M4: одноразово (нет переиспользования устаревшего)
                val direct = paths.directConf
                val relay = paths.relayConf
                if (direct == null && relay == null) {
                    fail(getString(R.string.mayak_status_no_egress)); return@launch
                }

                // Прямой путь приоритетен. Сервер добавляет пира в течение ~15с (sync-таймер),
                // поэтому пробу egress повторяем несколько раз, прежде чем сдаться.
                if (direct != null) {
                    tunnel.up(direct)
                    setStatus(getString(R.string.mayak_status_probing))
                    val ip = probeWithRetry()
                    if (ip != null) { onConnected(ip); return@launch }
                }

                if (relay == null) { fail(getString(R.string.mayak_status_no_egress)); return@launch }
                // Резерв: прямого не было вовсе или он не прошёл пробу.
                if (direct != null) setStatus(getString(R.string.mayak_status_relay_switch))
                tunnel.up(relay)
                val ip = probeWithRetry()
                if (ip != null) onConnected(ip) else fail(getString(R.string.mayak_status_no_egress))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // пользователь отменил подключение (тап по кнопке) — гасим туннель, БЕЗ ошибки/инвалидации.
                runCatching { tunnel.down() }
                throw e
            } catch (e: Exception) {
                runCatching { tunnel.down() }
                // Коннект упал — топология/направление могли измениться: сбрасываем кэш направлений,
                // чтобы следующая загрузка пошла в ядро за свежим списком (фейловер).
                session.invalidateDirections()
                fail(humanError(e))
            } finally {
                connectJob = null
            }
        }
    }

    /** Несколько попыток egress-пробы (пир появляется на сервере не сразу). */
    private suspend fun probeWithRetry(): String? {
        repeat(PROBE_ATTEMPTS) { attempt ->
            val ip = probe.externalIp()
            if (ip != null) return ip
            if (attempt < PROBE_ATTEMPTS - 1) delay(PROBE_DELAY_MS)
        }
        return null
    }

    private fun onConnected(ip: String) = runOnUiThread {
        connState = ConnState.CONNECTED
        renderState(ConnState.CONNECTED)
        // таймер/IP появляются с лёгким fade (не резким visibility).
        ipView?.let {
            it.text = getString(R.string.mayak_ip_label, ip)
            it.visibility = View.VISIBLE
            fadeIn(it)
        }
        timerView?.let { fadeIn(it) }
        successHaptic()
        startTimer()
        // Постоянное уведомление «Подключено» (флаг+направление); метку персистим в GoTunnel (процесс-
        // скоупно) — на повторном открытии покажем то же направление.
        GoTunnel.connectedLabel = MayakNotification.labelFor(this, selectedDir)
        MayakNotification.show(this, GoTunnel.connectedLabel)
        Toast.makeText(this, getString(R.string.mayak_connected), Toast.LENGTH_SHORT).show()
    }

    /** Success-haptic при подтверждении подключения (CONFIRM с API30, иначе обычный тик). */
    private fun successHaptic() {
        val v = connectCircle ?: return
        val feedback = if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.VIRTUAL_KEY
        v.performHapticFeedback(feedback)
    }

    private fun fadeIn(v: View) {
        if (reducedMotion()) { v.alpha = 1f; return }
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(220).start()
    }

    private fun fail(message: String) = runOnUiThread {
        connState = ConnState.DISCONNECTED
        renderState(ConnState.DISCONNECTED)
        setStatus(message)
    }

    private fun disconnect() {
        renderState(ConnState.CONNECTING)
        lifecycleScope.launch {
            runCatching { tunnel.down() }
            stopTimer()
            MayakNotification.clear(this@MayakActivity)
            connState = ConnState.DISCONNECTED
            renderState(ConnState.DISCONNECTED)
            setStatus(getString(R.string.mayak_status_disconnected))
        }
    }

    /** Применяет визуальное состояние круга/иконки/статуса/таймера + анимацию (пульс/glow). */
    private fun renderState(state: ConnState) = runOnUiThread {
        connState = state // единый источник истины: connState всегда синхронен с отрисованным состоянием
        val circleBg = when (state) {
            ConnState.DISCONNECTED -> R.drawable.mayak_circle_disconnected
            ConnState.CONNECTING -> R.drawable.mayak_circle_connecting
            ConnState.CONNECTED -> R.drawable.mayak_circle_connected
        }
        connectCircle?.setBackgroundResource(circleBg)
        val iconTint = when (state) {
            ConnState.CONNECTED -> R.color.mayak_circle_icon_on
            else -> R.color.mayak_circle_icon_off
        }
        connectIcon?.let {
            ImageViewCompat.setImageTintList(it, ContextCompat.getColorStateList(this, iconTint))
        }
        // contentDescription круга меняется по состоянию (доступность, см. дизайн-ревью §3.6).
        connectCircle?.contentDescription = getString(
            when (state) {
                ConnState.DISCONNECTED -> R.string.mayak_a11y_connect
                ConnState.CONNECTING -> R.string.mayak_a11y_connecting
                ConnState.CONNECTED -> R.string.mayak_a11y_disconnect
            }
        )
        when (state) {
            ConnState.DISCONNECTED -> {
                stopPulse()
                stopGlowBreath()
                setGlow(0f)
                rippleView?.stopWaves()
                networkBg?.setConnected(false)
                timerView?.visibility = View.GONE
                ipView?.visibility = View.GONE
                if (::status.isInitialized) status.text = getString(R.string.mayak_status_disconnected)
            }
            ConnState.CONNECTING -> {
                startPulse()
                setGlow(0.35f)
                rippleView?.startWaves() // от кнопки расходятся волны (sonar/активация)
                if (::status.isInitialized) status.text = getString(R.string.mayak_connecting)
            }
            ConnState.CONNECTED -> {
                stopPulse()
                rampGlow(1f)            // яркая вспышка-ореол
                startGlowBreath()       // затем ровное «дыхание» свечения — круг живой
                rippleView?.bloom()     // финальная вспышка-волна
                networkBg?.setConnected(true) // фон-сеть оживает ярче
                timerView?.visibility = View.VISIBLE
                if (::status.isInitialized) status.text = getString(R.string.mayak_connected)
            }
        }
    }

    /** Ровное «дыхание» ореола в состоянии «Под защитой» (alpha 0.7↔1.0, ~2.4с). */
    private fun startGlowBreath() {
        stopGlowBreath()
        val glow = connectGlow ?: return
        if (reducedMotion()) { glow.alpha = 1f; return }
        glowBreath = ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.7f).apply {
            startDelay = 420 // после ramp-вспышки
            duration = 2400
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopGlowBreath() {
        glowBreath?.cancel()
        glowBreath = null
    }

    /**
     * Пульс кольца на «Подключение…»: scale 1.0↔1.08 + alpha, цикл ~1.2с, ease-in-out.
     * Reduced-motion: пульса нет — оставляем статичный круг (статус всё равно меняется текстом).
     */
    private fun startPulse() {
        stopPulse()
        val circle = connectCircle ?: return
        if (reducedMotion()) { circle.scaleX = 1f; circle.scaleY = 1f; circle.alpha = 1f; return }
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            circle,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f),
        ).apply {
            duration = 1200
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        connectCircle?.let { it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f }
    }

    /** Мгновенно задать прозрачность ореола (отменив возможную идущую анимацию). */
    private fun setGlow(alpha: Float) {
        connectGlow?.let { it.animate().cancel(); it.alpha = alpha }
    }

    /** Плавно «разгореть» ореол до target (вспышка при connected). Reduced-motion → мгновенно. */
    private fun rampGlow(target: Float) {
        val glow = connectGlow ?: return
        if (reducedMotion()) { glow.alpha = target; return }
        glow.animate().alpha(target).setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    // --- таймер сессии ---

    private fun startTimer() {
        // Источник истины по началу сессии — GoTunnel (момент НАШЕГО up(), процесс-скоупный). После
        // пересоздания Activity таймер продолжает считать реальный аптайм подключения, а не «с возврата».
        // Fallback на now() — только если по какой-то причине метка отсутствует (напр. туннель поднят вне up()).
        sessionStartElapsed = GoTunnel.connectedSinceElapsed ?: SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val sec = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000
                timerView?.text = formatDuration(sec)
                delay(1000)
            }
        }
    }

    /**
     * Привести UI к фактическому состоянию НАШЕГО туннеля (вызов из onResume). Следим ТОЛЬКО за нашим
     * коннектом: tunnel.isUp() true лишь для туннеля, поднятого через наш backend — VPN другого
     * приложения (Happ и т.п.) сюда не попадёт (наш VpnService при этом погашен → isUp()=false).
     */
    private fun syncConnStateFromTunnel() {
        val target = if (tunnel.isUp()) ConnState.CONNECTED else ConnState.DISCONNECTED
        if (connState == target) return // уже синхронно — не дёргаем анимации/рендер зря
        if (target == ConnState.CONNECTED) {
            startTimer()
            MayakNotification.show(this, GoTunnel.connectedLabel)
        } else {
            stopTimer()
            MayakNotification.clear(this)
        }
        renderState(target)
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        runOnUiThread { timerView?.text = formatDuration(0) }
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    // --- helpers ---

    private fun humanError(e: Throwable): String = when (e) {
        is MayakApiException -> "Ошибка ядра (${e.status}): ${e.message}"
        is NoReachableHostException -> "Ядро недоступно: ${e.message}"
        else -> "Ошибка: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }

    private fun setStatus(text: String) = runOnUiThread {
        if (::status.isInitialized) status.text = text
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val KEY_SERVER = "server_url" // доступен из настроек для сборки того же HostProvider (диаг-лог)

        // Адреса ядра по умолчанию: публичный домен (LE-серт, система доверия) ПЕРВЫМ,
        // затем IP-фолбэк (наш CA, см. network_security_config + res/raw/mayak_ca.pem).
        // :core делает фейловер по сетевым ошибкам и «залипает» на рабочем — поэтому пока
        // DNS домена не разъехался, всё едет через IP, а как только домен поднимется — через домен.
        val DEFAULT_HOSTS = listOf( // доступен из настроек для сборки HostProvider (диаг-лог)
            "https://api.mayakvpn.ru",
            "https://45.132.18.167:8443",
        )

        // Веб-кабинет: регистрация, подтверждение email, политика/условия.
        private const val CABINET_URL = "https://cabinet.mayakvpn.ru"
        const val PRIVACY_URL = "https://cabinet.mayakvpn.ru/#/privacy"
        const val TERMS_URL = "https://cabinet.mayakvpn.ru/#/terms"

        // Сервер добавляет пира sync-таймером (~15с) → повторяем egress-пробу до ~24с.
        private const val PROBE_ATTEMPTS = 6
        private const val PROBE_DELAY_MS = 4_000L

        // Свежесть кэша направлений: в пределах TTL пересоздание Activity (смена темы) НЕ рефетчит
        // список из сети; спустя TTL переоткрытие приложения дотягивает свежий (новые направления
        // появляются сами). Явный рефреш (кнопка) и логин рефетчат всегда. 5 минут — баланс «не дёргать
        // сеть на смене темы» ↔ «показать новые направления без перелогина».
        private const val DIRECTIONS_TTL_MS = 5 * 60 * 1000L
    }
}

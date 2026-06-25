// Экран «Маяк VPN» (бета MVP). Брендовый дизайн на XML-разметке (Material 3):
// вход — логотип-маяк + название + карточка с полями логин/пароль, кнопки «Войти», «Сканировать QR»,
// «Вставить рег-ссылку» (в ссылке зашиты адрес ядра + логин/пароль — юзеру ничего вводить не надо).
// Главный экран — список стран → подключение со сквозной пробой и авто-резервом (прямой → резерв).
// Тема следует системе (DayNight) или ручному выбору (MayakPrefs); язык — ru/be/kk/uz/en/de/fr.
package org.amnezia.awg.mayak

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
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
    private var directions: List<Direction> = emptyList()
    private var selectedDir: Direction? = null
    private val rowViews = mutableListOf<View>()
    private var timerJob: Job? = null
    private var sessionStartElapsed = 0L

    // вьюхи круга/таймера (на главном экране)
    private var connectCircle: View? = null
    private var connectIcon: ImageView? = null
    private var timerView: TextView? = null
    private var ipView: TextView? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        store = KeystoreSecureStore(this)
        session = MayakSession(store, AwgKeyProvider())
        tunnel = GoTunnel(this)

        if (session.hasToken()) {
            backend = MayakBackend(hostProvider())
            showHome(); loadDirections()
        } else {
            showLogin()
        }
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
                showHome(); loadDirections()
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
        setContentView(R.layout.activity_mayak_home)
        status = findViewById(R.id.mayak_status)
        dirsContainer = findViewById(R.id.mayak_dirs_container)
        connectCircle = findViewById(R.id.mayak_connect_circle)
        connectIcon = findViewById(R.id.mayak_connect_icon)
        timerView = findViewById(R.id.mayak_timer)
        ipView = findViewById(R.id.mayak_ip)

        setupThemeButton()
        findViewById<MaterialButton>(R.id.mayak_settings_button).setOnClickListener {
            startActivity(Intent(this, MayakSettingsActivity::class.java))
        }

        connectCircle?.setOnClickListener { toggleConnect() }

        // Восстанавливаем состояние круга после пересоздания (смена темы/языка).
        connState = if (tunnel.isUp()) ConnState.CONNECTED else ConnState.DISCONNECTED
        if (connState == ConnState.CONNECTED) startTimer() // таймер заново (без точного старта — с момента возврата)
        renderState(connState)
    }

    private fun loadDirections() {
        val b = backend ?: return
        setStatus(getString(R.string.mayak_status_loading))
        lifecycleScope.launch {
            try {
                val dirs = session.directions(b)
                directions = dirs
                val container = dirsContainer ?: return@launch
                container.removeAllViews()
                rowViews.clear()
                if (dirs.isEmpty()) {
                    setStatus(getString(R.string.mayak_err_empty_dirs)); return@launch
                }
                for (d in dirs) {
                    val row = countryRow(d)
                    container.addView(row)
                    rowViews.add(row)
                }
                // Выбор по умолчанию: последняя выбранная страна, иначе первая.
                val lastId = MayakPrefs.lastDirectionId(this@MayakActivity)
                val initial = dirs.firstOrNull { it.id == lastId } ?: dirs.first()
                selectDir(initial)
                if (connState == ConnState.DISCONNECTED) {
                    setStatus(getString(R.string.mayak_status_disconnected))
                }
            } catch (e: Exception) { setStatus(humanError(e)) }
        }
    }

    /** Строка-страна: флаг + название + шеврон; тап = выбор (без подключения). */
    private fun countryRow(d: Direction): View {
        val container = dirsContainer
        val row = LayoutInflater.from(this).inflate(R.layout.mayak_country_row, container, false)
        row.findViewById<TextView>(R.id.mayak_row_flag).text = MayakFlags.forCode(d.code)
        row.findViewById<TextView>(R.id.mayak_row_name).text =
            if (d.code.isNotBlank()) "${d.name} (${d.code})" else d.name
        row.tag = d.id
        row.setOnClickListener { selectDir(d) }
        return row
    }

    /** Выбрать страну: подсветить строку, запомнить выбор. Не подключает. */
    private fun selectDir(d: Direction) {
        selectedDir = d
        MayakPrefs.setLastDirectionId(this, d.id)
        for (row in rowViews) {
            val isSel = (row.tag as? Long) == d.id
            row.setBackgroundResource(if (isSel) R.drawable.mayak_row_selected else android.R.color.transparent)
        }
    }

    /** Тап по кругу: подключиться к выбранной стране или отключиться. */
    private fun toggleConnect() {
        when (connState) {
            ConnState.CONNECTED -> disconnect()
            ConnState.CONNECTING -> { /* идёт подключение — игнорируем повторный тап */ }
            ConnState.DISCONNECTED -> {
                val d = selectedDir
                if (d == null) { setStatus(getString(R.string.mayak_select_country_first)); return }
                connectTo(d)
            }
        }
    }

    private fun connectTo(d: Direction) {
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
        lifecycleScope.launch {
            try {
                val paths = session.connect(b, d)
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
            } catch (e: Exception) {
                runCatching { tunnel.down() }
                fail(humanError(e))
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
        ipView?.let { it.text = getString(R.string.mayak_ip_label, ip); it.visibility = View.VISIBLE }
        startTimer()
        Toast.makeText(this, getString(R.string.mayak_connected), Toast.LENGTH_SHORT).show()
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
            connState = ConnState.DISCONNECTED
            renderState(ConnState.DISCONNECTED)
            setStatus(getString(R.string.mayak_status_disconnected))
        }
    }

    /** Применяет визуальное состояние круга/иконки/статуса/таймера. */
    private fun renderState(state: ConnState) = runOnUiThread {
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
        when (state) {
            ConnState.DISCONNECTED -> {
                timerView?.visibility = View.GONE
                ipView?.visibility = View.GONE
                if (::status.isInitialized) status.text = getString(R.string.mayak_status_disconnected)
            }
            ConnState.CONNECTING -> {
                if (::status.isInitialized) status.text = getString(R.string.mayak_connecting)
            }
            ConnState.CONNECTED -> {
                timerView?.visibility = View.VISIBLE
                if (::status.isInitialized) status.text = getString(R.string.mayak_connected)
            }
        }
    }

    // --- таймер сессии ---

    private fun startTimer() {
        sessionStartElapsed = SystemClock.elapsedRealtime()
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val sec = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000
                timerView?.text = formatDuration(sec)
                delay(1000)
            }
        }
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
        private const val KEY_SERVER = "server_url"

        // Адреса ядра по умолчанию: публичный домен (LE-серт, система доверия) ПЕРВЫМ,
        // затем IP-фолбэк (наш CA, см. network_security_config + res/raw/mayak_ca.pem).
        // :core делает фейловер по сетевым ошибкам и «залипает» на рабочем — поэтому пока
        // DNS домена не разъехался, всё едет через IP, а как только домен поднимется — через домен.
        private val DEFAULT_HOSTS = listOf(
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
    }
}

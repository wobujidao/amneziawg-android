// Экран «Маяк VPN» (бета MVP). Брендовый дизайн на XML-разметке (Material 3):
// вход — логотип-маяк + название + карточка с полями логин/пароль, кнопки «Войти», «Сканировать QR»,
// «Вставить рег-ссылку» (в ссылке зашиты адрес ядра + логин/пароль — юзеру ничего вводить не надо).
// Главный экран — список стран → подключение со сквозной пробой и авто-резервом (прямой → резерв).
// Есть переключатель языка (ru/be/kk/uz/en/de/fr) — меняет локаль рантайм через AppCompatDelegate.
package org.amnezia.awg.mayak

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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

    // согласие на VPN → продолжаем отложенное подключение
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val dir = pendingConnect
            pendingConnect = null
            if (result.resultCode == RESULT_OK && dir != null) doConnect(dir)
            else setStatus(getString(R.string.mayak_err_no_vpn_perm))
        }

    // сканер QR (zxing) → разбираем как регистрационную ссылку
    private val scanQr = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleRegLink(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KeystoreSecureStore(this)
        session = MayakSession(store, AwgKeyProvider())
        tunnel = GoTunnel(this)

        val savedServer = store.get(KEY_SERVER)
        if (session.hasToken() && savedServer != null) {
            backend = MayakBackend(HostProvider(listOf(savedServer)))
            showHome(); loadDirections()
        } else {
            showLogin()
        }
    }

    // --- экран входа: логотип + название + карточка логин/пароль + QR + рег-ссылка ---

    private fun showLogin() {
        setContentView(R.layout.activity_mayak_login)
        dirsContainer = null
        status = findViewById(R.id.mayak_status)

        val loginField = findViewById<TextInputEditText>(R.id.mayak_login)
        val passField = findViewById<TextInputEditText>(R.id.mayak_password)

        findViewById<MaterialButton>(R.id.mayak_language_button).setOnClickListener { showLanguageDialog() }

        findViewById<MaterialButton>(R.id.mayak_sign_in).setOnClickListener {
            val login = loginField.text?.toString().orEmpty()
            val pass = passField.text?.toString().orEmpty()
            if (login.isBlank() || pass.isBlank()) {
                setStatus(getString(R.string.mayak_err_fill_login)); return@setOnClickListener
            }
            val server = store.get(KEY_SERVER) ?: DEFAULT_SERVER
            doSignIn(server, login, pass)
        }
        findViewById<MaterialButton>(R.id.mayak_scan_qr).setOnClickListener {
            scanQr.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false))
        }
        findViewById<MaterialButton>(R.id.mayak_paste_link).setOnClickListener { showPasteLinkDialog() }
    }

    /** Разбор регистрационной ссылки mayak://reg?server=..&login=..&password=.. → автологин. */
    private fun handleRegLink(raw: String) {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull()
        val server = uri?.getQueryParameter("server")
        val login = uri?.getQueryParameter("login")
        val password = uri?.getQueryParameter("password")
        if (uri?.scheme != "mayak" || server.isNullOrBlank() || login.isNullOrBlank() || password.isNullOrBlank()) {
            setStatus(getString(R.string.mayak_err_bad_link)); return
        }
        doSignIn(server.trimEnd('/'), login, password)
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

    /** Выбор языка интерфейса: меняем локаль рантайм, appcompat сам её сохраняет (autoStoreLocales). */
    private fun showLanguageDialog() {
        val names = LANGS.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_language))
            .setItems(names) { _, which ->
                val tag = LANGS[which].first
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            .setNegativeButton(getString(R.string.mayak_cancel), null)
            .show()
    }

    private fun doSignIn(server: String, login: String, password: String) {
        if (server.isBlank()) { setStatus(getString(R.string.mayak_err_bad_link)); return }
        store.put(KEY_SERVER, server)
        backend = MayakBackend(HostProvider(listOf(server)))
        setStatus(getString(R.string.mayak_status_signing_in))
        lifecycleScope.launch {
            try {
                session.login(backend!!, login, password)
                showHome(); loadDirections()
            } catch (e: Exception) { setStatus(humanError(e)) }
        }
    }

    // --- главный экран: страны + статус ---

    private fun showHome() {
        setContentView(R.layout.activity_mayak_home)
        status = findViewById(R.id.mayak_status)
        dirsContainer = findViewById(R.id.mayak_dirs_container)

        findViewById<MaterialButton>(R.id.mayak_disconnect).setOnClickListener {
            lifecycleScope.launch {
                try { tunnel.down(); setStatus(getString(R.string.mayak_status_disconnected)) }
                catch (e: Exception) { setStatus(humanError(e)) }
            }
        }
        findViewById<MaterialButton>(R.id.mayak_logout).setOnClickListener {
            lifecycleScope.launch { runCatching { tunnel.down() } }
            session.logout(); showLogin()
        }
    }

    private fun loadDirections() {
        val b = backend ?: return
        setStatus(getString(R.string.mayak_status_loading))
        lifecycleScope.launch {
            try {
                val dirs = session.directions(b)
                val container = dirsContainer ?: return@launch
                container.removeAllViews()
                setStatus(if (dirs.isEmpty()) getString(R.string.mayak_err_empty_dirs) else getString(R.string.mayak_status_ready))
                for (d in dirs) container.addView(
                    countryButton(if (d.code.isNotBlank()) "${d.name} (${d.code})" else d.name) { connectTo(d) }
                )
            } catch (e: Exception) { setStatus(humanError(e)) }
        }
    }

    private fun connectTo(d: Direction) {
        val prepare = GoBackend.VpnService.prepare(this)
        if (prepare != null) { pendingConnect = d; vpnPermission.launch(prepare) } else doConnect(d)
    }

    private fun doConnect(d: Direction) {
        val b = backend ?: return
        setStatus(getString(R.string.mayak_status_connecting, d.name))
        lifecycleScope.launch {
            try {
                val paths = session.connect(b, d)
                val direct = paths.directConf
                val relay = paths.relayConf
                // Прямой путь приоритетен, но если его нет — резерв становится основной попыткой.
                // no_egress показываем только когда оба отсутствуют или оба не прошли пробу.
                if (direct == null && relay == null) {
                    setStatus(getString(R.string.mayak_status_no_egress)); return@launch
                }

                if (direct != null) {
                    tunnel.up(direct)
                    setStatus(getString(R.string.mayak_status_probing))
                    val ip = probe.externalIp()
                    if (ip != null) { setStatus(getString(R.string.mayak_status_connected_direct, ip)); return@launch }
                }

                if (relay == null) { setStatus(getString(R.string.mayak_status_no_egress)); return@launch }
                // Резерв: либо прямого не было вовсе, либо он не прошёл пробу.
                if (direct != null) setStatus(getString(R.string.mayak_status_relay_switch))
                tunnel.up(relay)
                val ip = probe.externalIp()
                setStatus(
                    if (ip != null) getString(R.string.mayak_status_connected_relay, ip)
                    else getString(R.string.mayak_status_no_egress)
                )
            } catch (e: Exception) { setStatus(humanError(e)) }
        }
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

    /** Кнопка-страна в списке: инфлейтим брендовый стиль Mayak.Button.Country из ресурса. */
    private fun countryButton(label: String, onClick: () -> Unit): MaterialButton {
        val container = dirsContainer
        val btn = LayoutInflater.from(this)
            .inflate(R.layout.mayak_country_button, container, false) as MaterialButton
        btn.text = label
        btn.setOnClickListener { onClick() }
        return btn
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_SERVER = "server_url"
        // адрес ядра по умолчанию для ручного входа; рег-ссылка/QR переопределяют. Заполним при живом ядре.
        private const val DEFAULT_SERVER = ""

        // Языки интерфейса: BCP-47 тег → отображаемое имя (на своём языке).
        private val LANGS = listOf(
            "ru" to "Русский",
            "be" to "Беларуская",
            "kk" to "Қазақша",
            "uz" to "Oʻzbekcha",
            "en" to "English",
            "de" to "Deutsch",
            "fr" to "Français",
        )
    }
}

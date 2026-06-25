// Экран «Маяк VPN» (бета MVP). Вход: логотип + название по центру, поля логин/пароль, кнопки
// «Сканировать QR» и «Вставить регистрационную ссылку» (в ссылке зашиты адрес ядра + логин/пароль —
// юзеру ничего вводить не надо). Дальше: список стран → подключение со сквозной пробой и авто-резервом.
// UI программный, без action bar (тема MayakTheme). Тексты — из ресурсов (ru/be/kk/uz/en/de/fr).
package org.amnezia.awg.mayak

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
    private lateinit var dirsContainer: LinearLayout

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

    // --- экран входа: логотип + название + логин/пароль + QR + рег-ссылка ---

    private fun showLogin() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(56), dp(32), dp(32))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_mayak_logo)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        }
        val tagline = TextView(this).apply {
            text = getString(R.string.mayak_tagline)
            textSize = 14f; alpha = 0.7f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(32))
        }

        val login = field(getString(R.string.mayak_login_hint), "")
        val pass = field(getString(R.string.mayak_password_hint), "", password = true)
        status = TextView(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }

        val signIn = primaryButton(getString(R.string.mayak_sign_in)) {
            if (login.text.isBlank() || pass.text.isBlank()) {
                setStatus(getString(R.string.mayak_err_fill_login)); return@primaryButton
            }
            val server = store.get(KEY_SERVER) ?: DEFAULT_SERVER
            doSignIn(server, login.text.toString(), pass.text.toString())
        }
        val scan = secondaryButton(getString(R.string.mayak_scan_qr)) {
            scanQr.launch(ScanOptions().setOrientationLocked(false).setBeepEnabled(false))
        }
        val paste = secondaryButton(getString(R.string.mayak_paste_link)) { showPasteLinkDialog() }

        root.addView(logo); root.addView(title); root.addView(tagline)
        root.addView(login); root.addView(pass); root.addView(signIn)
        root.addView(spacer(dp(8)))
        root.addView(scan); root.addView(paste); root.addView(status)
        setContentView(wrap(root))
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
        val input = EditText(this).apply { hint = getString(R.string.mayak_paste_link_hint) }
        // предзаполним из буфера обмена, если там ссылка
        clipboardText()?.let { if (it.startsWith("mayak://")) input.setText(it) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_paste_link_title))
            .setView(input)
            .setPositiveButton(getString(R.string.mayak_ok)) { _, _ -> handleRegLink(input.text.toString()) }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_mayak_logo)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name); textSize = 22f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16))
        }
        status = TextView(this).apply { setPadding(0, dp(16), 0, dp(8)) }
        dirsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(logo); root.addView(title)
        root.addView(TextView(this).apply { text = getString(R.string.mayak_pick_country); setPadding(0, dp(8), 0, dp(4)) })
        root.addView(dirsContainer)
        root.addView(status)
        root.addView(secondaryButton(getString(R.string.mayak_disconnect)) {
            lifecycleScope.launch {
                try { tunnel.down(); setStatus(getString(R.string.mayak_status_disconnected)) }
                catch (e: Exception) { setStatus(humanError(e)) }
            }
        })
        root.addView(secondaryButton(getString(R.string.mayak_logout)) {
            lifecycleScope.launch { runCatching { tunnel.down() } }
            session.logout(); showLogin()
        })
        setContentView(wrap(root))
    }

    private fun loadDirections() {
        val b = backend ?: return
        setStatus(getString(R.string.mayak_status_loading))
        lifecycleScope.launch {
            try {
                val dirs = session.directions(b)
                dirsContainer.removeAllViews()
                setStatus(if (dirs.isEmpty()) getString(R.string.mayak_err_empty_dirs) else getString(R.string.mayak_status_ready))
                for (d in dirs) dirsContainer.addView(
                    primaryButton(if (d.code.isNotBlank()) "${d.name} (${d.code})" else d.name) { connectTo(d) }
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
                if (direct == null) { setStatus(getString(R.string.mayak_status_no_egress)); return@launch }

                tunnel.up(direct)
                setStatus(getString(R.string.mayak_status_probing))
                var ip = probe.externalIp()
                if (ip != null) { setStatus(getString(R.string.mayak_status_connected_direct, ip)); return@launch }

                val relay = paths.relayConf
                if (relay == null) { setStatus(getString(R.string.mayak_status_no_egress)); return@launch }
                setStatus(getString(R.string.mayak_status_relay_switch))
                tunnel.up(relay)
                ip = probe.externalIp()
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
        status.text = text
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun wrap(v: View) = ScrollView(this).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH); addView(v)
    }

    private fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, h) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_SERVER = "server_url"
        // адрес ядра по умолчанию для ручного входа; рег-ссылка/QR переопределяют. Заполним при живом ядре.
        private const val DEFAULT_SERVER = ""
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

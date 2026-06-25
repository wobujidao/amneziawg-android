// Экран «Маяк» (бета MVP): вход → список стран → подключение со сквозной пробой и авто-резервом.
// Заменяет стоковые экраны импорта конфигов amnezia. UI программный (без XML) — меньше связок.
// Логика — в :core (MayakBackend/ConfRenderer) и MayakSession; туннель — GoTunnel поверх GoBackend.
package org.amnezia.awg.mayak

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

    // запрос согласия на VPN; по успеху продолжаем отложенное подключение
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val dir = pendingConnect
            pendingConnect = null
            if (result.resultCode == RESULT_OK && dir != null) {
                doConnect(dir)
            } else {
                setStatus("Нет разрешения на VPN")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = KeystoreSecureStore(this)
        session = MayakSession(store, AwgKeyProvider())
        tunnel = GoTunnel(this)

        val savedServer = store.get(KEY_SERVER)
        if (session.hasToken() && savedServer != null) {
            backend = MayakBackend(HostProvider(listOf(savedServer)))
            showHome()
            loadDirections()
        } else {
            showLogin()
        }
    }

    // --- экран входа ---

    private fun showLogin() {
        val root = column()
        val server = field("Адрес ядра (https://...)", store.get(KEY_SERVER) ?: "https://")
        val login = field("Логин", "")
        val pass = field("Пароль", "", password = true)
        status = statusView()
        val btn = Button(this).apply {
            text = "Войти"
            setOnClickListener {
                val url = server.text.toString().trim().trimEnd('/')
                if (url.isBlank() || login.text.isBlank() || pass.text.isBlank()) {
                    setStatus("Заполни адрес, логин и пароль"); return@setOnClickListener
                }
                store.put(KEY_SERVER, url)
                backend = MayakBackend(HostProvider(listOf(url)))
                setStatus("Вход…")
                lifecycleScope.launch {
                    try {
                        session.login(backend!!, login.text.toString(), pass.text.toString())
                        showHome(); loadDirections()
                    } catch (e: Exception) {
                        setStatus(humanError(e))
                    }
                }
            }
        }
        root.addView(header("Маяк — вход"))
        root.addView(server); root.addView(login); root.addView(pass); root.addView(btn); root.addView(status)
        setContentView(wrap(root))
    }

    // --- главный экран: страны + статус ---

    private lateinit var dirsContainer: LinearLayout

    private fun showHome() {
        val root = column()
        root.addView(header("Маяк"))
        status = statusView()
        val disconnect = Button(this).apply {
            text = "Отключить"
            setOnClickListener {
                lifecycleScope.launch {
                    try { tunnel.down(); setStatus("Отключено") } catch (e: Exception) { setStatus(humanError(e)) }
                }
            }
        }
        val logout = Button(this).apply {
            text = "Выйти"
            setOnClickListener {
                lifecycleScope.launch { runCatching { tunnel.down() } }
                session.logout(); showLogin()
            }
        }
        dirsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply { text = "Выбери страну:"; setPadding(0, 24, 0, 8) })
        root.addView(dirsContainer)
        root.addView(status)
        root.addView(disconnect)
        root.addView(logout)
        setContentView(wrap(root))
    }

    private fun loadDirections() {
        val b = backend ?: return
        setStatus("Загрузка стран…")
        lifecycleScope.launch {
            try {
                val dirs = session.directions(b)
                dirsContainer.removeAllViews()
                if (dirs.isEmpty()) { setStatus("Ядро не вернуло ни одной страны") }
                else setStatus("Готов")
                for (d in dirs) {
                    dirsContainer.addView(Button(this@MayakActivity).apply {
                        text = if (d.code.isNotBlank()) "${d.name} (${d.code})" else d.name
                        setOnClickListener { connectTo(d) }
                    })
                }
            } catch (e: Exception) {
                setStatus(humanError(e))
            }
        }
    }

    private fun connectTo(d: Direction) {
        val prepare = GoBackend.VpnService.prepare(this)
        if (prepare != null) {
            pendingConnect = d
            vpnPermission.launch(prepare)
        } else {
            doConnect(d)
        }
    }

    private fun doConnect(d: Direction) {
        val b = backend ?: return
        setStatus("Подключение к ${d.name}…")
        lifecycleScope.launch {
            try {
                val paths = session.connect(b, d)
                val direct = paths.directConf
                if (direct == null) { setStatus("Ядро не дало прямой конфиг"); return@launch }

                // 1) пробуем прямой путь + сквозная проба egress
                tunnel.up(direct)
                setStatus("Прямой поднят, проверяю выход…")
                var ip = probe.externalIp()
                if (ip != null) { setStatus("Подключено (прямой). Внешний IP: $ip"); return@launch }

                // 2) проба не прошла → авто-резерв на релей (если есть)
                val relay = paths.relayConf
                if (relay == null) { setStatus("Прямой не вышел в сеть, резерва нет"); return@launch }
                setStatus("Прямой не вышел — переключаюсь на резерв…")
                tunnel.up(relay)
                ip = probe.externalIp()
                setStatus(if (ip != null) "Подключено (резерв). Внешний IP: $ip" else "Оба пути не вышли в сеть")
            } catch (e: Exception) {
                setStatus(humanError(e))
            }
        }
    }

    // --- helpers ---

    private fun humanError(e: Throwable): String = when (e) {
        is MayakApiException -> "Ошибка ядра (${e.status}): ${e.message}"
        is NoReachableHostException -> "Ядро недоступно: ${e.message}"
        else -> "Ошибка: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            status.text = text
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
    }

    private fun wrap(v: View) = ScrollView(this).apply { addView(v) }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 24f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 32)
    }

    private fun statusView() = TextView(this).apply { setPadding(0, 24, 0, 0) }

    private fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    companion object {
        private const val KEY_SERVER = "server_url"
    }
}

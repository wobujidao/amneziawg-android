// Настройки «Маяк»: выбор темы (свет/тёмная/системная) и языка. Тема — через MayakPrefs
// (AppCompatDelegate + персист), язык — через общий MayakLanguages-диалог.
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.activity.LogViewerActivity
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend

class MayakSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_settings)

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
        // Диагностика: открываем встроенный лог-вьюер (logcat всего AWG-движка: хендшейки, ошибки),
        // оттуда юзер делится логом (кнопка Share). Раньше до него не было входа из Маяк-UI.
        findViewById<MaterialButton>(R.id.mayak_settings_logs).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
            MayakTransitions.applyAxis(this)
        }
        findViewById<MaterialButton>(R.id.mayak_settings_send_log).setOnClickListener { sendLog(it as MaterialButton) }
        findViewById<MaterialButton>(R.id.mayak_settings_logout).setOnClickListener { confirmLogout() }

        // Тумблер «Использовать IPv6» (SPEC-0014): по умолч. ВКЛ. При выкл клиент срезает v6 из конфига
        // при следующем подключении (кэш конфига v6-полный, стрип на apply) → IPv6 идёт мимо туннеля.
        val speedSwitch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_speed)
        speedSwitch.isChecked = MayakPrefs.showSpeed(this)
        speedSwitch.setOnCheckedChangeListener { _, checked -> MayakPrefs.setShowSpeed(this, checked) }
        val ipv6Switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mayak_settings_ipv6)
        ipv6Switch.isChecked = MayakPrefs.useIpv6(this)
        ipv6Switch.setOnCheckedChangeListener { _, checked ->
            MayakPrefs.setUseIpv6(this, checked)
            // Применится при следующем коннекте (текущий туннель не трогаем, чтобы не рвать сессию молча).
            Toast.makeText(this, R.string.mayak_settings_ipv6_applied, Toast.LENGTH_SHORT).show()
        }

        val group = findViewById<RadioGroup>(R.id.mayak_theme_group)
        // Отметим текущий режим без срабатывания листенера.
        when (MayakPrefs.themeMode(this)) {
            MayakPrefs.THEME_LIGHT -> group.check(R.id.mayak_theme_light)
            MayakPrefs.THEME_DARK -> group.check(R.id.mayak_theme_dark)
            else -> group.check(R.id.mayak_theme_system)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.mayak_theme_light -> MayakPrefs.THEME_LIGHT
                R.id.mayak_theme_dark -> MayakPrefs.THEME_DARK
                else -> MayakPrefs.THEME_SYSTEM
            }
            if (mode != MayakPrefs.themeMode(this)) {
                MayakPrefs.setThemeMode(this, mode)
                // setDefaultNightMode пересоздаст активити с новой темой.
            }
        }
    }

    /**
     * Сбор и отправка диагностического лога на сервер (главное действие диагностики). Собираем
     * контекст устройства/сети + дамп logcat движка → POST /v1/client/diag-log. Требует входа.
     */
    private fun sendLog(button: MaterialButton) {
        val store = KeystoreSecureStore(this)
        val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store))
        if (!session.hasToken()) {
            Toast.makeText(this, R.string.mayak_settings_send_log_need_login, Toast.LENGTH_LONG).show()
            return
        }
        val saved = store.get(MayakActivity.KEY_SERVER)?.trimEnd('/')
        val hosts = if (saved != null && saved !in MayakActivity.DEFAULT_HOSTS)
            listOf(saved) + MayakActivity.DEFAULT_HOSTS else MayakActivity.DEFAULT_HOSTS
        val backend = MayakBackend(HostProvider(hosts))

        val original = button.text
        button.isEnabled = false
        button.setText(R.string.mayak_settings_send_log_sending)
        lifecycleScope.launch {
            val msg = try {
                val req = DiagCollector.collect(this@MayakSettingsActivity, direction = "", deviceId = session.deviceId())
                session.sendDiagLog(backend, req)
                getString(R.string.mayak_settings_send_log_ok)
            } catch (e: MayakApiException) {
                getString(R.string.mayak_settings_send_log_err, "HTTP ${e.status}: ${e.message}")
            } catch (e: Exception) {
                getString(R.string.mayak_settings_send_log_err, e.message ?: "ошибка сети")
            }
            button.isEnabled = true
            button.text = original
            Toast.makeText(this@MayakSettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Выход из аккаунта: гасим туннель, чистим сессию, возвращаемся на экран входа. */
    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mayak_logout))
            .setPositiveButton(getString(R.string.mayak_ok)) { _, _ ->
                val store = KeystoreSecureStore(this)
                val session = MayakSession(store, AwgKeyProvider(), AndroidHwidProvider(this, store))
                val tunnel = GoTunnel(this)
                lifecycleScope.launch {
                    runCatching { tunnel.down() }
                    session.logout()
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
}

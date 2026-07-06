// Настройки «Маяк»: выбор темы (свет/тёмная/системная) и языка. Тема — через MayakPrefs
// (AppCompatDelegate + персист), язык — через общий MayakLanguages-диалог.
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
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
import org.amnezia.awg.R
import org.amnezia.awg.activity.LogViewerActivity
import org.amnezia.awg.fragment.AppListDialogFragment
import org.amnezia.awg.mayak.core.HostProvider
import org.amnezia.awg.mayak.core.MayakApiException
import org.amnezia.awg.mayak.core.MayakBackend

class MayakSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_settings)
        MayakSystemBars.apply(this) // контраст иконок статус-бара/навбара под тему

        // Edge-to-edge: контент рисуется под системными барами (градиент во всю высоту). Отступаем контент на
        // высоту статус-бара сверху и НАВИГАЦИОННОЙ панели снизу — иначе кнопка «Выход» уезжала под навбар
        // (правка владельца 2026-07-06, скриншот). Адаптивно: жест-навигация тоньше, 3-кнопочная толще.
        val content = findViewById<View>(R.id.mayak_settings_content)
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = baseTop + bars.top, bottom = baseBottom + bars.bottom)
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
        // Диагностика: открываем встроенный лог-вьюер (logcat всего AWG-движка: хендшейки, ошибки),
        // оттуда юзер делится логом (кнопка Share). Раньше до него не было входа из Маяк-UI.
        findViewById<MaterialButton>(R.id.mayak_settings_logs).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
            MayakTransitions.applyAxis(this)
        }
        findViewById<MaterialButton>(R.id.mayak_settings_send_log).setOnClickListener { sendLog(it as MaterialButton) }
        findViewById<MaterialButton>(R.id.mayak_settings_logout).setOnClickListener { confirmLogout() }

        // Показываем, под каким email выполнен вход (запрос владельца: в приложении не было видно аккаунта).
        val acctStore = KeystoreSecureStore(this)
        val accountEmail = MayakSession(acctStore, AwgKeyProvider(), AndroidHwidProvider(this, acctStore)).email()
        findViewById<TextView>(R.id.mayak_settings_account).text = getString(
            R.string.mayak_settings_account,
            accountEmail ?: getString(R.string.mayak_settings_account_none),
        )

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
                    MayakNotification.show(
                        this, GoTunnel.connectedLabel, GoTunnel.connectedPingMs,
                        ipv6 = GoTunnel.egressIpv6 != null,
                    )
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

        // Split-туннель (SPEC-0018 F1): кнопка открывает диалог выбора приложений. Переиспользуем
        // upstream AppListDialogFragment (список интернет-приложений + вкладки исключить/включить),
        // результат сохраняем в MayakPrefs — применится при следующем коннекте (prepareConf).
        val splitButton = findViewById<MaterialButton>(R.id.mayak_settings_split)
        updateSplitButton(splitButton)
        supportFragmentManager.setFragmentResultListener(AppListDialogFragment.REQUEST_SELECTION, this) { _, bundle ->
            val apps = bundle.getStringArray(AppListDialogFragment.KEY_SELECTED_APPS)?.toSet() ?: emptySet()
            val excluded = bundle.getBoolean(AppListDialogFragment.KEY_IS_EXCLUDED, true)
            MayakPrefs.setSplitApps(this, apps, excluded)
            updateSplitButton(splitButton)
            Toast.makeText(this, R.string.mayak_settings_split_applied, Toast.LENGTH_SHORT).show()
        }
        splitButton.setOnClickListener {
            val current = ArrayList<String?>(MayakPrefs.splitApps(this))
            AppListDialogFragment.newInstance(current, MayakPrefs.splitExcluded(this))
                .show(supportFragmentManager, null)
        }

        // Значок приложения / маскировка (SPEC-0018 F2): диалог выбора пресета иконки+имени.
        findViewById<MaterialButton>(R.id.mayak_settings_disguise).setOnClickListener { showDisguiseDialog() }

        // Тема — сегментированный переключатель (Авто/Светлая/Тёмная). check() при инициализации дёрнет
        // листенер, но guard `mode != текущий` не даст лишнего setThemeMode/пересоздания.
        val group = findViewById<MaterialButtonToggleGroup>(R.id.mayak_theme_group)
        group.check(
            when (MayakPrefs.themeMode(this)) {
                MayakPrefs.THEME_LIGHT -> R.id.mayak_theme_light
                MayakPrefs.THEME_DARK -> R.id.mayak_theme_dark
                else -> R.id.mayak_theme_system
            }
        )
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.mayak_theme_light -> MayakPrefs.THEME_LIGHT
                R.id.mayak_theme_dark -> MayakPrefs.THEME_DARK
                else -> MayakPrefs.THEME_SYSTEM
            }
            if (mode != MayakPrefs.themeMode(this)) {
                MayakPrefs.setThemeMode(this, mode) // setDefaultNightMode пересоздаст активити с новой темой
            }
        }
    }

    /** Текст кнопки split-туннеля: без выбора — общее название, иначе со счётчиком выбранных приложений. */
    private fun updateSplitButton(button: MaterialButton) {
        val n = MayakPrefs.splitApps(this).size
        button.text = if (n == 0) getString(R.string.mayak_settings_split)
        else getString(R.string.mayak_settings_split_count, n)
    }

    /** Диалог маскировки (SPEC-0018 F2): выбор пресета иконки+имени. Применение — MayakDisguise.apply
     *  (переключает activity-alias, не убивая процесс → VPN не рвётся; иконка обновится через миг). */
    private fun showDisguiseDialog() {
        val aliases = MayakDisguise.ALL
        val labels = arrayOf(
            getString(R.string.app_name),
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

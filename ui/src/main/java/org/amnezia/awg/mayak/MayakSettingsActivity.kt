// Настройки «Маяк»: выбор темы (свет/тёмная/системная) и языка. Тема — через MayakPrefs
// (AppCompatDelegate + персист), язык — через общий MayakLanguages-диалог.
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import org.amnezia.awg.R

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
        findViewById<MaterialButton>(R.id.mayak_settings_logout).setOnClickListener { confirmLogout() }

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

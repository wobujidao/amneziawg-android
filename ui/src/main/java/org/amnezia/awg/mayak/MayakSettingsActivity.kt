// Настройки «Маяк»: выбор темы (свет/тёмная/системная) и языка. Тема — через MayakPrefs
// (AppCompatDelegate + персист), язык — через общий MayakLanguages-диалог.
package org.amnezia.awg.mayak

import android.content.Intent
import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.amnezia.awg.R

class MayakSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_settings)

        findViewById<MaterialButton>(R.id.mayak_settings_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.mayak_settings_language).setOnClickListener {
            MayakLanguages.showDialog(this)
        }
        findViewById<MaterialButton>(R.id.mayak_settings_about).setOnClickListener {
            startActivity(Intent(this, MayakAboutActivity::class.java))
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
}

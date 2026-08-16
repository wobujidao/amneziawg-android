// «О приложении»: версия (BuildConfig), на чём основано (AmneziaWG / amneziawg-go / протокол 3.0),
// лицензия, открытый код и стабы правовых разделов. Брендовый, DayNight.
package org.amnezia.awg.mayak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R

class MayakAboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_about)
        MayakSystemBars.apply(this) // контраст иконок статус-бара/навбара под тему

        // Отступы под системные панели — тот же приём, что в «Настройках». Статус-бар отдан
        // ЗАКРЕПЛЁННОЙ шапке, контент отступает на её высоту (высота зависит от инсета телефона,
        // поэтому меряем после разметки), снизу — на высоту навигационной панели.
        // 🔴 Почему это стало обязательным: с targetSdk 35 Android 15 рисует окно от края до края
        // ВСЕГДА, отказаться нельзя. Без отступов заголовок экрана оказывался под часами, а нижняя
        // карточка — под жестовой полосой (замер на эмуляторе API 35, 15-08).
        val content = findViewById<View>(R.id.mayak_about_content)
        val header = findViewById<View>(R.id.mayak_about_header)
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        val headerBaseTop = header.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = headerBaseTop + bars.top)
            header.post { v.updatePadding(top = baseTop + header.height, bottom = baseBottom + bars.bottom) }
            insets
        }
        ViewCompat.requestApplyInsets(content)

        findViewById<MaterialButton>(R.id.mayak_about_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }

        // Версия приложения из BuildConfig (заполняется gradle из amneziawgVersionName/Code).
        findViewById<TextView>(R.id.mayak_about_version).text =
            getString(R.string.mayak_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        // «Основано на»: версию движка gradle читает ИЗ go.mod при сборке (BuildConfig.AWG_GO_VERSION).
        // Раньше тут стояла руками написанная константа — и она отстала на версию (аудит 31-07, п. 19).
        //
        // Версия ПРОТОКОЛА строкой ниже — из той же величины, а не второй константой: 16-08 движок
        // подняли до 3.1, а рядом так и осталось «Протокол: AmneziaWG 3.0» — ровно та же беда, что
        // и в п. 19, просто в соседней строке. Одна правда — один источник.
        findViewById<TextView>(R.id.mayak_about_basedon).text = getString(
            R.string.mayak_based_on_body,
            BuildConfig.AWG_GO_VERSION,
            protocolVersion(BuildConfig.AWG_GO_VERSION),
        )

        findViewById<MaterialButton>(R.id.mayak_about_oss).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.mayak_oss_licenses))
                .setMessage(getString(R.string.mayak_oss_licenses_body))
                .setPositiveButton(getString(R.string.mayak_ok), null)
                .show()
        }

        // Правовые разделы открываем в вебе (единый источник истины — кабинет mayakvpn.ru).
        findViewById<MaterialButton>(R.id.mayak_about_privacy).setOnClickListener {
            openUrl(MayakHostList.privacyUrl(this))
        }
        findViewById<MaterialButton>(R.id.mayak_about_terms).setOnClickListener {
            openUrl(MayakHostList.termsUrl(this))
        }
        // Здесь была кнопка «Удалить аккаунт», уводившая в кабинет. Удаление теперь делается прямо в
        // приложении — в «Настройках», рядом с email аккаунта и кнопкой «Выйти» (аудит 2026-07-31,
        // п. 16: аудитор прошёл настройки целиком и пути удаления не нашёл, потому что он прятался на
        // «О приложении» и вёл на сайт). Одно действие — одно место, и это место — аккаунт.
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        /**
         * Версия ПРОТОКОЛА из версии движка: «v3.1.20260814» → «3.1». Третье число — дата сборки
         * движка, к протоколу отношения не имеет, и на экране ему делать нечего.
         *
         * Не разобралось — возвращаем пусто, и строка скажет «Протокол: AmneziaWG» без числа.
         * Врать номером хуже, чем его не назвать (тот же принцип, что и у версии движка: не нашли —
         * не выдумываем).
         */
        @JvmStatic
        fun protocolVersion(engineVersion: String): String {
            val parts = engineVersion.trimStart('v', 'V').split('.')
            if (parts.size < 2) return ""
            val major = parts[0].takeWhile { it.isDigit() }
            val minor = parts[1].takeWhile { it.isDigit() }
            if (major.isEmpty() || minor.isEmpty()) return ""
            return "$major.$minor"
        }
    }
}

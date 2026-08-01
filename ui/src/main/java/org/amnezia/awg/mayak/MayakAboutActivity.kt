// «О приложении»: версия (BuildConfig), на чём основано (AmneziaWG / amneziawg-go / протокол 2.0),
// лицензия, открытый код и стабы правовых разделов. Брендовый, DayNight.
package org.amnezia.awg.mayak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R

class MayakAboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_about)
        MayakSystemBars.apply(this) // контраст иконок статус-бара/навбара под тему

        findViewById<MaterialButton>(R.id.mayak_about_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }

        // Версия приложения из BuildConfig (заполняется gradle из amneziawgVersionName/Code).
        findViewById<TextView>(R.id.mayak_about_version).text =
            getString(R.string.mayak_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        // «Основано на»: версию движка gradle читает ИЗ go.mod при сборке (BuildConfig.AWG_GO_VERSION).
        // Раньше тут стояла руками написанная константа — и она отстала на версию (аудит 31-07, п. 19).
        findViewById<TextView>(R.id.mayak_about_basedon).text =
            getString(R.string.mayak_based_on_body, BuildConfig.AWG_GO_VERSION)

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

}

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

        // «Основано на»: версия движка amneziawg-go берётся из go.mod (libwg-go) — держим в синхроне.
        findViewById<TextView>(R.id.mayak_about_basedon).text =
            getString(R.string.mayak_based_on_body, AMNEZIAWG_GO_VERSION)

        findViewById<MaterialButton>(R.id.mayak_about_oss).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.mayak_oss_licenses))
                .setMessage(getString(R.string.mayak_oss_licenses_body))
                .setPositiveButton(getString(R.string.mayak_ok), null)
                .show()
        }

        // Правовые разделы открываем в вебе (единый источник истины — кабинет mayakvpn.ru).
        findViewById<MaterialButton>(R.id.mayak_about_privacy).setOnClickListener {
            openUrl(MayakActivity.PRIVACY_URL)
        }
        findViewById<MaterialButton>(R.id.mayak_about_terms).setOnClickListener {
            openUrl(MayakActivity.TERMS_URL)
        }
        // Удаление аккаунта: требование Google Play — путь из приложения обязателен. Предупреждаем о
        // необратимости и уводим в кабинет, где операция подтверждается паролем (вторую реализацию
        // необратимого удаления в приложении заводить не станем).
        findViewById<MaterialButton>(R.id.mayak_about_delete_account).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.mayak_delete_account))
                .setMessage(getString(R.string.mayak_delete_account_hint))
                .setPositiveButton(getString(R.string.mayak_open_cabinet)) { _, _ -> openUrl(DELETE_ACCOUNT_URL) }
                .setNegativeButton(getString(R.string.mayak_cancel), null)
                .show()
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        // Должно совпадать с tunnel/tools/libwg-go/go.mod (github.com/amnezia-vpn/amneziawg-go).
        private const val AMNEZIAWG_GO_VERSION = "v0.2.18"

        // Кабинет открывается на входе: там же, после логина, в «Настройках» лежит удаление аккаунта.
        private const val DELETE_ACCOUNT_URL = "https://cabinet.mayakvpn.ru/#/login"
    }
}

// Экран отрезанной сборки — клиентская половина порога старых сборок (см. :core/MinVersionGate.kt).
//
// Зачем он вообще. Порог `min_version_code` в version.json ставит панель, и он нужен ровно в одном
// случае: мы выпустили сборку, в которой что-то не работает (протокол, лестница подключения), и
// человека на ней надо сдвинуть. До этой правки приложение поле игнорировало, и такой человек видел
// не объяснение, а непонятные отказы ядра, — то есть шёл он в поддержку, а не обновляться.
//
// Почему отдельная Activity ПОВЕРХ главной, а не setContentView в MayakActivity (образец —
// MayakLockActivity, тот же приём и та же причина): подменять контент главной небезопасно, её
// асинхронные колбэки продолжают искать вьюхи своего экрана. Поверх — состояние главной цело.
//
// 🔴 Гейт НЕОТМЕНЯЕМЫЙ: кнопки «Позже» нет, «Назад» сворачивает приложение (внутрь не пускаем).
// Но выходы в поддержку и справку есть обязательно: если обновление у человека не встаёт (нет места,
// запрещена установка из источника, Play не открывается), экран без этих кнопок — тупик, из которого
// он не сможет даже спросить.
//
// Туннель НЕ трогаем. Гейт — про UI: он срабатывает раньше подключения, а рубить уже поднятое
// соединение значит оставить человека без интернета в ту же секунду, когда мы сообщаем плохую новость.
package org.amnezia.awg.mayak

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.mayak.core.AppVersionInfo
import org.amnezia.awg.mayak.core.OutdatedBuild

class MayakOutdatedActivity : AppCompatActivity() {

    private lateinit var verdict: OutdatedBuild
    private var apkUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_outdated)
        MayakSystemBars.apply(this)
        showing = true

        verdict = runCatching { OutdatedBuild.valueOf(intent?.getStringExtra(EXTRA_VERDICT) ?: "") }
            .getOrDefault(OutdatedBuild.OPEN_SITE) // не разобрали приказ — оставляем самый безобидный путь
        apkUrl = intent?.getStringExtra(EXTRA_APK_URL).orEmpty()
        val latestName = intent?.getStringExtra(EXTRA_LATEST_NAME).orEmpty()

        val action = findViewById<MaterialButton>(R.id.mayak_outdated_action)
        action.setText(
            when (verdict) {
                OutdatedBuild.OPEN_IN_PLAY -> R.string.mayak_update_open_play
                OutdatedBuild.OPEN_SITE -> R.string.mayak_outdated_open_site
                else -> R.string.mayak_update_now
            },
        )
        action.setOnClickListener { act() }

        findViewById<MaterialButton>(R.id.mayak_outdated_support).setOnClickListener {
            // Адрес аккаунта не передаём: гейт срабатывает и ДО входа, а угадывать «кто это» на
            // экране без сессии нечем. Версию и аппарат письмо подставит само (MayakSupport).
            MayakSupport.writeToSupport(this, null)
        }
        val help = findViewById<MaterialButton>(R.id.mayak_outdated_help)
        if (MayakHostList.helpUrl(this) == null) {
            help.visibility = View.GONE // адреса сайта нет — неработающую кнопку не показываем
        } else {
            help.setOnClickListener { MayakSupport.openHelp(this) }
        }

        // Версии внизу мелким: человек всё равно назовёт их первым делом, если напишет нам.
        findViewById<TextView>(R.id.mayak_outdated_versions).text = getString(
            R.string.mayak_outdated_versions,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            latestName.ifBlank { getString(R.string.mayak_outdated_version_unknown) },
        )
    }

    /** Единственное действие экрана. Канал решён заранее чистой функцией `outdatedBuild`. */
    private fun act() = when (verdict) {
        OutdatedBuild.OPEN_IN_PLAY -> MayakUpdater.openPlay(this)
        // knownBases берём из реестра доменов, а не из backend: экземпляра backend тут нет, а список
        // нужен, чтобы принять ссылку на APK только с нашего домена (MayakUpdater.sameSite).
        OutdatedBuild.UPDATE_FROM_SITE ->
            MayakUpdater.runUpdate(this, apkUrl, MayakHostList.effective(this, null))
        // Ссылки на APK нет или она непригодна — отправляем на сайт руками. Кнопки, которая молча
        // ничего не делает, на этом экране быть не может: уйти с него человеку больше некуда.
        else -> openSite()
    }

    private fun openSite() {
        val url = MayakHostList.siteUrl(this) ?: run {
            // Ни APK, ни адреса сайта — остаётся поддержка. Молчащая кнопка была бы тупиком.
            MayakSupport.writeToSupport(this, null)
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    override fun onDestroy() {
        showing = false // и при finish(), и при system-kill: даём главной решить заново
        super.onDestroy()
    }

    // «Назад» = свернуть приложение. Внутрь не пускаем: сборка отрезана, и «Позже» тут не бывает.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    companion object {
        private const val EXTRA_VERDICT = "mayak_verdict"
        private const val EXTRA_APK_URL = "mayak_apk_url"
        private const val EXTRA_LATEST_NAME = "mayak_latest_name"

        /** Экран уже показан — второй раз не запускаем (проверка порога может прийти и после поворота). */
        @Volatile
        var showing = false
            private set

        /** Показать экран поверх текущего. Зовётся из MayakActivity, когда `outdatedBuild` != NONE. */
        fun show(context: Context, verdict: OutdatedBuild, info: AppVersionInfo) {
            if (showing || verdict == OutdatedBuild.NONE) return
            showing = true // ставим ДО старта: запрос порога может вернуться дважды подряд
            val intent = Intent(context, MayakOutdatedActivity::class.java)
                .putExtra(EXTRA_VERDICT, verdict.name)
                .putExtra(EXTRA_APK_URL, info.apkUrl)
                .putExtra(EXTRA_LATEST_NAME, info.latestVersionName)
            runCatching { context.startActivity(intent) }.onFailure { showing = false }
        }
    }
}

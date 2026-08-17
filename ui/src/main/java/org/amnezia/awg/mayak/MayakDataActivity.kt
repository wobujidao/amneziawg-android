// «Какие данные мы собираем» — короткое раскрытие ВНУТРИ приложения.
//
// Зачем отдельный экран, если политика конфиденциальности есть и подробная: политика живёт в
// браузере и написана языком документа, а человек соглашается ЗДЕСЬ, галочкой на регистрации.
// 152-ФЗ требует осведомлённого согласия, а анкета Google Data Safety спрашивает ровно эти
// категории — то есть один и тот же список нужен и человеку, и закону, и магазину. Экран не
// заменяет политику, а ведёт к ней кнопкой.
//
// ⚠️ Тексты живут в strings.xml на двух языках и обязаны сходиться с web/privacy.html §2:
// разъедутся — это не мелкая неточность, а два разных обещания в двух местах.
package org.amnezia.awg.mayak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import org.amnezia.awg.R

class MayakDataActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mayak_data)
        MayakSystemBars.apply(this)

        // Отступы под системные панели — тот же приём, что в «О приложении»: с targetSdk 35+ окно
        // рисуется от края до края всегда, и без этого заголовок уезжает под часы.
        val content = findViewById<View>(R.id.mayak_data_content)
        val header = findViewById<View>(R.id.mayak_data_header)
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

        findViewById<MaterialButton>(R.id.mayak_data_back).setOnClickListener {
            finish(); MayakTransitions.applyAxisReverse(this)
        }
        findViewById<MaterialButton>(R.id.mayak_data_privacy).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MayakHostList.privacyUrl(this))))
            }
        }
    }
}

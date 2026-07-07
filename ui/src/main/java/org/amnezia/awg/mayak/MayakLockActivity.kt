// Экран блокировки ПОВЕРХ MayakActivity (запрос владельца 2026-07-06). Автоматически показывает системный
// BiometricPrompt (биометрия / системный PIN). Успех → MayakLock.unlocked=true, finish() (под ним открыта
// главная). Отмена → остаёмся с кнопками «Разблокировать» (повтор) и «Выйти» (свернуть, не пуская внутрь).
// FLAG_SECURE — экран не попадает в превью задач/скриншоты. Пока показан — контент главной скрыт (она под ним).
package org.amnezia.awg.mayak

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.amnezia.awg.R

class MayakLockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        MayakPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_mayak_lock)
        MayakSystemBars.apply(this)
        MayakLock.lockShowing = true
        findViewById<MaterialButton>(R.id.mayak_lock_unlock).setOnClickListener { prompt() }
        findViewById<MaterialButton>(R.id.mayak_lock_exit).setOnClickListener { moveTaskToBack(true) }
        prompt()
    }

    private fun prompt() {
        MayakLock.authenticate(this) { ok ->
            if (ok) finish() // разблокировано → закрываем экран, под ним главная
            // не ок — остаёмся; юзер жмёт «Разблокировать» (повтор) или «Выйти» (свернуть)
        }
    }

    override fun onDestroy() {
        MayakLock.lockShowing = false // и при finish(), и при system-kill: даём главной решить заново
        super.onDestroy()
    }

    // «Назад» на экране блокировки = свернуть приложение (внутрь без разблокировки не пускаем).
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}

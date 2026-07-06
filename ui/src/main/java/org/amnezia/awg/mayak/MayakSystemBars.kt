// Контраст системных баров под тему: в СВЕТЛОЙ теме иконки статус-бара/навбара (часы, сеть, батарея,
// кнопки навигации) должны быть ТЁМНЫМИ, в тёмной — светлыми. Баг владельца 2026-07-06: на светлой теме
// иконки были белые на светлом фоне → невидимы. MayakTheme (в отличие от апстрим-AppTheme) не задавал
// windowLightStatusBar, поэтому ставим appearance в коде — заодно корректно при рантайм-смене темы
// (Activity пересоздаётся → вызов повторяется).
package org.amnezia.awg.mayak

import android.app.Activity
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

object MayakSystemBars {
    /** Выставить светлые/тёмные иконки статус-бара и навбара по текущему режиму (свет/тёмная). */
    fun apply(activity: Activity) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        // light appearance = ТЁМНЫЕ иконки (для светлого фона). В тёмной теме — false → светлые иконки.
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }
}

// Контраст системных баров под тему: в СВЕТЛОЙ теме иконки статус-бара/навбара (часы, сеть, батарея,
// кнопки навигации) должны быть ТЁМНЫМИ, в тёмной — светлыми. Баг владельца 2026-07-06: на светлой теме
// иконки были белые на светлом фоне → невидимы. MayakTheme (в отличие от апстрим-AppTheme) не задавал
// windowLightStatusBar, поэтому ставим appearance в коде — заодно корректно при рантайм-смене темы
// (Activity пересоздаётся → вызов повторяется).
package org.amnezia.awg.mayak

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

object MayakSystemBars {
    /**
     * Выставить светлые/тёмные иконки статус-бара и навбара по текущему режиму (свет/тёмная)
     * и включить отрисовку ОТ КРАЯ ДО КРАЯ.
     *
     * Про край-до-края. Android 15 с targetSdk 35 включает его сам и отказаться не даёт, а более
     * старые версии — нет: там окно ужимается под панели. Из-за этого один и тот же код давал два
     * разных экрана, и отступы, посчитанные по инсетам, на старых телефонах приходили НУЛЯМИ
     * (панели уже учтены системой) — то есть зазор под статус-баром пропадал бы. Включаем режим
     * руками на всех версиях: одно поведение, одна арифметика отступов, одна проверка.
     * Цвет панелей при этом задаёт тема (прозрачные, styles.xml) — вызывать `setStatusBarColor`
     * из кода не нужно, он в Android 15 объявлен устаревшим.
     */
    fun apply(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        // light appearance = ТЁМНЫЕ иконки (для светлого фона). В тёмной теме — false → светлые иконки.
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }

    /**
     * Отступить вью на высоту системных панелей: сверху статус-бар, снизу навигационная панель
     * (жестовая полоса тоньше, три кнопки толще — поэтому меряем, а не пишем цифру).
     *
     * 🔴 Зачем это обязательно. С targetSdk 35 Android 15 рисует окно ОТ КРАЯ ДО КРАЯ всегда,
     * отказаться нельзя, и `android:statusBarColor` в теме там ничего не значит. Экран без отступов
     * получает заголовок под часами, а нижнюю кнопку — под жестовой полосой.
     *
     * Годится для экранов с ОДНИМ корневым блоком (главный, вход, гейт, блокировка). Там, где шапка
     * закреплена поверх прокрутки («Настройки», «О приложении»), отступ считается от высоты шапки —
     * это делается на месте, здесь такого варианта нет намеренно.
     *
     * `bottom = false` — для экранов, где низ уже отодвигает кто-то другой (на главном это штамп
     * версии, он сам держит отступ над жестовой полосой): иначе отступ сложится дважды.
     * Боковые инсеты добавляются ВСЕГДА: в портрете они нулевые, а в альбоме туда уезжает
     * навигационная панель и вырез камеры.
     */
    fun padForBars(view: View, bottom: Boolean = true) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + if (bottom) bars.bottom else 0,
            )
            insets
        }
        // 🔴 Без этой строки экран, который ПОДМЕНИЛИ на живом окне (вход → главный: там setContentView,
        // а не новая Activity), остаётся без отступов до первого события инсетов — то есть до поворота
        // экрана или перезапуска. Замерено 15-08 на эмуляторе Android 15: сразу после входа шапка
        // главного экрана налезала на часы, а после перезапуска приложения тот же код давал верный
        // отступ. Система сама дозовёт слушателя только при ИЗМЕНЕНИИ инсетов, а подмена контента
        // изменением не считается — просим пересчёт руками.
        ViewCompat.requestApplyInsets(view)
    }
}

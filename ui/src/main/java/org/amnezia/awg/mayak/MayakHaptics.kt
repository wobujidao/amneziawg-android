// Тактильный отклик — в одном месте и с выключателем (директива владельца 2026-07-01: «настройка
// тактильного и звукового отклика на включение/выключение»).
//
// Почему отдельный объект, а не `view.performHapticFeedback(...)` по коду. Вызовы были рассыпаны по
// экрану (тап по кнопке, выбор страны, переключатели), и выключить их человеку было нечем: настройка
// без единой точки прохода превратилась бы в «выключил, а половина осталась». Теперь вибрирует всё
// через этот объект, и он один смотрит на тумблер.
//
// Звуковой половины директивы здесь НЕТ — сознательно. Своих звуков в приложении нет ни одного, а
// системный клик на подключение VPN звучит инородно; заводить звуковую тему ради двух событий дорого
// и спорно. Записано в APP-BACKLOG как отдельный вопрос владельцу, а не сделано молча.
package org.amnezia.awg.mayak

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

object MayakHaptics {

    /** Короткий отклик на действие человека: тап по кнопке, выбор страны, переключатель. */
    fun tap(view: View) {
        if (!enabled(view.context)) return
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Отклик на долгое нажатие (взять строку страны для перетаскивания). */
    fun longPress(view: View) {
        if (!enabled(view.context)) return
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Отклик на СОБЫТИЕ — туннель поднялся или упал. Отличается от тапа намеренно: человек нажал
     * кнопку и через секунды получил подтверждение, что защита действительно включилась, а не только
     * «нажатие принято». Именно этого просил владелец.
     *
     * CONFIRM/REJECT появились в API 30; ниже — обычный длинный отклик, он есть везде с API 24.
     */
    fun stateChanged(view: View, connected: Boolean) {
        if (!enabled(view.context)) return
        val effect = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && connected -> HapticFeedbackConstants.CONFIRM
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(effect)
    }

    private fun enabled(context: Context): Boolean = MayakPrefs.hapticsEnabled(context)
}

// Тонкие переходы между экранами «Маяк» (login→home, →settings/about). Используем родные
// overridePendingTransition с fade-through + лёгким сдвигом по Z — субтильно, без джанка.
// На Android 14+ есть overrideActivityTransition, но для совместимости держим классический путь.
package org.amnezia.awg.mayak

import android.app.Activity
import org.amnezia.awg.R

object MayakTransitions {
    /** Применить переход «вперёд» (open). Вызывать сразу после startActivity. */
    @Suppress("DEPRECATION")
    fun applyAxis(activity: Activity) {
        activity.overridePendingTransition(R.anim.mayak_axis_in, R.anim.mayak_axis_out)
    }

    /** Применить переход «назад» (close). Вызывать сразу после finish(). */
    @Suppress("DEPRECATION")
    fun applyAxisReverse(activity: Activity) {
        activity.overridePendingTransition(R.anim.mayak_axis_in_reverse, R.anim.mayak_axis_out_reverse)
    }
}

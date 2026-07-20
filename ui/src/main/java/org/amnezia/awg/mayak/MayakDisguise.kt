// Маскировка приложения (SPEC-0018 F2): переключение лаунчер-иконки+имени через activity-alias.
// Прячет сам факт наличия VPN на телефоне (иконка/имя как у «Погоды»/«Заметок»/«Калькулятора»).
package org.amnezia.awg.mayak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Ровно ОДИН alias enabled в любой момент. Имена алиасов — ПОЛНЫЕ (см. манифест: в debug-сборке
 * applicationId = <pkg>.debug ≠ namespace, а имя компонента строится от namespace; поэтому
 * ComponentName(packageName, ПОЛНОЕ_имя) корректно указывает на компонент). Переключение — с
 * DONT_KILL_APP: НЕ убиваем процесс (иначе оборвётся VPN-сервис); лаунчер обновит иконку в течение
 * нескольких секунд (иногда нужен ручной перезапуск — предупреждаем пользователя).
 */
object MayakDisguise {
    const val DEFAULT = "org.amnezia.awg.mayak.disguise.Default"       // маяк на тёмном (дефолт)
    const val MAYAK_LIGHT = "org.amnezia.awg.mayak.disguise.MayakLight" // маяк на белом
    const val WEATHER = "org.amnezia.awg.mayak.disguise.Weather"
    const val NOTES = "org.amnezia.awg.mayak.disguise.Notes"
    const val CALC = "org.amnezia.awg.mayak.disguise.Calc"

    /** Все пресеты в порядке показа (первый — обычный «Маяк» тёмный, второй — светлый вариант). */
    val ALL = listOf(DEFAULT, MAYAK_LIGHT, WEATHER, NOTES, CALC)

    /** Включает выбранный alias и выключает остальные (гарантируя ровно один активный лаунчер). */
    fun apply(context: Context, alias: String) {
        val pm = context.packageManager
        // Сначала включаем целевой, ПОТОМ выключаем прочие — чтобы ни на миг не остаться без иконки.
        setState(pm, context.packageName, alias, true)
        for (a in ALL) if (a != alias) setState(pm, context.packageName, a, false)
    }

    /** Текущий активный alias (по состоянию компонентов; DEFAULT, если явно ничего не включалось). */
    fun current(context: Context): String {
        val pm = context.packageManager
        for (a in ALL) {
            if (pm.getComponentEnabledSetting(ComponentName(context.packageName, a))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            ) return a
        }
        return DEFAULT
    }

    private fun setState(pm: PackageManager, pkg: String, alias: String, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        pm.setComponentEnabledSetting(ComponentName(pkg, alias), state, PackageManager.DONT_KILL_APP)
    }
}

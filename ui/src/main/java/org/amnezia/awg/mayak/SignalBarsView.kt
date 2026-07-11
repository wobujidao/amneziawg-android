package org.amnezia.awg.mayak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.amnezia.awg.R

/**
 * Индикатор «сигнала» страны — 3 полоски (как Wi-Fi), заполнены до [level] (0..3), цвет по уровню.
 * Заменяет шеврон в строке-стране (SPEC-0031 «быстрейший вверху»). Уровень берётся из СЕРВЕРНОГО хинта
 * ([Direction.signalLevel]) — без клиентского пинга, поэтому не создаёт нагрузки на серверы при масштабе.
 * Клиентский RTT-замер (позже, разреженно по открытию списка + кэш) сможет уточнить уровень.
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val barCount = 3
    private var level = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.mayak_on_surface_muted)
        alpha = 60
    }
    private val rect = RectF()

    /** Установить уровень 0..3 (0 = мёртв/нет сигнала). Перерисовка только при изменении. */
    fun setLevel(newLevel: Int) {
        val clamped = newLevel.coerceIn(0, barCount)
        if (clamped != level) {
            level = clamped
            invalidate()
        }
    }

    private fun levelColor(): Int = when (level) {
        3 -> Color.parseColor("#2E9E4F") // зелёный — быстро/свободно
        2 -> Color.parseColor("#E6A700") // жёлтый — средне
        1 -> Color.parseColor("#E0631B") // оранжевый — загружено
        else -> Color.parseColor("#C0392B") // красный — недоступно
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * 0.16f
        val barW = (w - gap * (barCount - 1)) / barCount
        val radius = barW * 0.35f
        val color = levelColor()
        for (i in 0 until barCount) {
            // высота полоски растёт слева-направо: 45%, 72%, 100%
            val frac = 0.45f + 0.275f * i
            val barH = h * frac
            val left = i * (barW + gap)
            val top = h - barH
            rect.set(left, top, left + barW, h)
            val filled = i < level
            fill.color = color
            canvas.drawRoundRect(rect, radius, radius, if (filled) fill else track)
        }
    }
}

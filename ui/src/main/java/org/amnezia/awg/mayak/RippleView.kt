// Концентрические волны от кнопки подключения (sonar/активация, см. DESIGN-VISION §1).
// CONNECTING → несколько колец расходятся наружу со сдвигом по фазе и затухают по мере роста.
// CONNECTED → короткая «вспышка»-bloom (одно быстрое яркое кольцо), затем покой.
// Лёгкая: один Paint, рисуем N окружностей по фазе; per-frame только меняем радиус (без layout).
// Reduced-motion → анимации нет (волны не идут; статус всё равно сообщается текстом).
package org.amnezia.awg.mayak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.amnezia.awg.R

class RippleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val baseColor = ContextCompat.getColor(context, R.color.mayak_ripple)

    private var running = false
    private var bloom = false
    private var bloomStart = 0L
    // фазы трёх колец (0..1), стартуют со сдвигом 1/3 — непрерывный поток волн
    private val phases = floatArrayOf(0f, 0.33f, 0.66f)
    private var lastFrame = 0L

    // радиус кнопки (px) — кольца начинаются от её края
    var coreRadiusPx = 0f

    private fun reducedMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** Запустить непрерывные волны (состояние «Подключение…»). */
    fun startWaves() {
        if (running || reducedMotion()) return
        running = true
        bloom = false
        lastFrame = 0L
        postOnAnimation(frameTick)
    }

    /** Остановить волны (отключено). */
    fun stopWaves() {
        running = false
        bloom = false
        removeCallbacks(frameTick)
        invalidate()
    }

    /** Финальная «вспышка»-bloom при подтверждении подключения. */
    fun bloom() {
        running = false
        removeCallbacks(frameTick)
        if (reducedMotion()) { invalidate(); return }
        bloom = true
        bloomStart = SystemClock.uptimeMillis()
        postOnAnimation(frameTick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = (minOf(width, height) / 2f)
        val start = coreRadiusPx.coerceAtLeast(maxR * 0.45f)

        if (bloom) {
            val t = ((SystemClock.uptimeMillis() - bloomStart) / 360f).coerceIn(0f, 1f)
            val r = start + (maxR - start) * t
            paint.strokeWidth = 6f * (1f - t) + 2f
            paint.color = withAlpha(baseColor, (200 * (1f - t)).toInt())
            canvas.drawCircle(cx, cy, r, paint)
            return
        }
        if (!running) return
        for (p in phases) {
            val r = start + (maxR - start) * p
            val alpha = (160 * (1f - p)).toInt().coerceIn(0, 255)
            paint.strokeWidth = 3.5f * (1f - p) + 1f
            paint.color = withAlpha(baseColor, alpha)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private val frameTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
            lastFrame = now
            if (bloom) {
                invalidate()
                if (now - bloomStart >= 380L) { bloom = false; invalidate(); return }
                postOnAnimationDelayed(this, FRAME_MS)
                return
            }
            if (!running) return
            for (i in phases.indices) {
                phases[i] += SPEED * dt
                if (phases[i] > 1f) phases[i] -= 1f
            }
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS)
        }
    }

    override fun onDetachedFromWindow() {
        stopWaves()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FRAME_MS = 33L  // ~30 FPS
        private const val SPEED = 0.45f   // фаза/сек: волна доходит до края за ~2.2с
    }
}

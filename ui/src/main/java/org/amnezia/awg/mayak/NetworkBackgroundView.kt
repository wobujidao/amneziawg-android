// Абстрактный сетевой фон (Phase-2 MVP, см. DESIGN-VISION): стилизованная «карта-сеть» —
// узлы по миру (вкл. ~RU и ~NL) + дуги между ними + «бегущие» по дугам частицы. Рисуется ЛОКАЛЬНО
// на Canvas (без картографического SDK и БЕЗ сети — приватность, см. ресёрч app-map-background.md).
//
// Производительность: статический слой (узлы + бледные дуги) кэшируется в Bitmap при изменении
// размера; каждый кадр перерисовываются ТОЛЬКО движущиеся частицы (дёшево). Кадры ограничены ~30 FPS,
// чтобы не отнимать ресурсы у пульса/волн круга. Reduced-motion → анимация выключена (статичный слой).
package org.amnezia.awg.mayak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.amnezia.awg.R
import kotlin.math.hypot

class NetworkBackgroundView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // Узлы в нормализованных координатах (0..1) — стилизованный «разброс по миру».
    // Помечены примерно: запад/центр/восток, плюс «RU» и «NL» как смысловые точки.
    private data class Node(val x: Float, val y: Float)

    private val nodes = listOf(
        Node(0.12f, 0.34f), // Сев. Америка
        Node(0.22f, 0.55f), // Юж. Америка
        Node(0.46f, 0.30f), // NL/Зап. Европа
        Node(0.50f, 0.52f), // Африка
        Node(0.62f, 0.26f), // RU/Вост. Европа
        Node(0.78f, 0.40f), // Азия
        Node(0.86f, 0.62f), // Австралия
        Node(0.34f, 0.22f), // север
        Node(0.70f, 0.58f), // ю-в Азия
    )

    // Дуги между парами узлов (индексы). Несколько «магистралей».
    private val edges = listOf(
        0 to 2, 2 to 4, 4 to 5, 5 to 6, 2 to 3, 3 to 1, 0 to 7, 7 to 4, 5 to 8, 8 to 6,
    )

    // Частица бежит по дуге: фаза 0..1 + скорость; разные старты, чтобы не синхронились.
    private data class Spark(val edge: Int, var phase: Float, val speed: Float)
    private val sparks = edges.mapIndexed { i, _ ->
        Spark(i, (i * 0.137f) % 1f, 0.06f + (i % 3) * 0.02f)
    }

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPath = Path()

    private var staticLayer: Bitmap? = null
    private var connected = false
    private var running = false
    private var lastFrame = 0L

    private val nodeColor = ContextCompat.getColor(context, R.color.mayak_net_node)
    private val lineColor = ContextCompat.getColor(context, R.color.mayak_net_line)
    private val sparkColor = ContextCompat.getColor(context, R.color.mayak_net_spark)

    init {
        nodePaint.color = nodeColor
        linePaint.color = lineColor
        sparkPaint.color = sparkColor
    }

    private fun reducedMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** Подключено → частицы чуть ярче/живее. */
    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        sparkPaint.alpha = if (value) 255 else Color.alpha(sparkColor)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildStaticLayer(w, h)
    }

    /** Кэшируем статический слой (узлы + бледные дуги) — не рисуем его каждый кадр. */
    private fun rebuildStaticLayer(w: Int, h: Int) {
        staticLayer?.recycle()
        if (w <= 0 || h <= 0) { staticLayer = null; return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for ((a, b) in edges) {
            val (sx, sy) = px(nodes[a], w, h)
            val (ex, ey) = px(nodes[b], w, h)
            arcPath.reset()
            arcPath.moveTo(sx, sy)
            // Лёгкая дуга (control-точка приподнята), а не прямая — «как соединения по глобусу».
            val mx = (sx + ex) / 2f
            val my = (sy + ey) / 2f - hypot(ex - sx, ey - sy) * 0.18f
            arcPath.quadTo(mx, my, ex, ey)
            c.drawPath(arcPath, linePaint)
        }
        for (n in nodes) {
            val (cx, cy) = px(n, w, h)
            c.drawCircle(cx, cy, 3.5f, nodePaint)
        }
        staticLayer = bmp
    }

    private fun px(n: Node, w: Int, h: Int): Pair<Float, Float> = (n.x * w) to (n.y * h)

    /** Точка на квадратичной дуге по параметру t (для бегущей частицы). */
    private fun pointOnArc(a: Int, b: Int, t: Float, w: Int, h: Int): Pair<Float, Float> {
        val (sx, sy) = px(nodes[a], w, h)
        val (ex, ey) = px(nodes[b], w, h)
        val mx = (sx + ex) / 2f
        val my = (sy + ey) / 2f - hypot(ex - sx, ey - sy) * 0.18f
        val u = 1 - t
        val x = u * u * sx + 2 * u * t * mx + t * t * ex
        val y = u * u * sy + 2 * u * t * my + t * t * ey
        return x to y
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        staticLayer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (reducedMotion()) return // статичный фон, без бегущих частиц

        val w = width; val h = height
        val radius = if (connected) 3.5f else 2.5f
        for (s in sparks) {
            val (a, b) = edges[s.edge]
            val (x, y) = pointOnArc(a, b, s.phase, w, h)
            canvas.drawCircle(x, y, radius, sparkPaint)
        }
    }

    /** Покадровое продвижение фаз (~30 FPS). */
    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1000f
            lastFrame = now
            val boost = if (connected) 1.6f else 1f
            for (s in sparks) {
                s.phase += s.speed * boost * dt
                if (s.phase > 1f) s.phase -= 1f
            }
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS) // кап ~30 FPS
        }
    }

    fun startAnimation() {
        if (running || reducedMotion()) return
        running = true
        lastFrame = 0L
        postOnAnimation(frameTick)
    }

    fun stopAnimation() {
        running = false
        removeCallbacks(frameTick)
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        staticLayer?.recycle(); staticLayer = null
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FRAME_MS = 33L // ~30 FPS, чтобы не джанкать анимацию круга
    }
}

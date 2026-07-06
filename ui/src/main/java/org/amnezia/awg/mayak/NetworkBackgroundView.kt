// Фон главного экрана «Маяк»: ТОЧЕЧНАЯ КАРТА МИРА + дуга-маршрут «устройство → страна выхода».
// Рисуется ЛОКАЛЬНО на Canvas (без картографического SDK и БЕЗ сети — приватность). Континенты заданы
// грубыми полигонами (lon/lat), точки суши кэшируются в Bitmap при изменении размера; каждый кадр
// перерисовываются только: дуга-маршрут (анимация «затекания» + бегущий импульс), подсветка точек у
// выхода, узлы. Метафора «маяк»: тёплое золотое свечение маршрута/подсветки. Тема — через ресурсы
// (values / values-night), reduced-motion → без бегущего импульса. См. DESIGN-VISION + прототип
// docs/assets/design/mayak-home-prototype.html в монорепо.
package org.amnezia.awg.mayak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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

    // Грубые полигоны континентов в (lon,lat). Точность декоративная — читается как карта мира.
    private val continents: Array<Array<FloatArray>> = arrayOf(
        arrayOf(f(-168,65),f(-165,68),f(-156,71),f(-130,70),f(-124,69),f(-100,70),f(-82,73),f(-74,68),f(-62,60),f(-56,52),f(-64,46),f(-70,43),f(-74,40),f(-81,31),f(-80,25),f(-90,30),f(-95,29),f(-97,26),f(-98,22),f(-105,22),f(-104,18),f(-96,16),f(-88,16),f(-83,9),f(-77,8),f(-83,14),f(-92,18),f(-99,24),f(-110,24),f(-117,32),f(-124,40),f(-125,48),f(-131,54),f(-141,59),f(-152,58),f(-162,60)), // N.America
        arrayOf(f(-46,60),f(-30,60),f(-20,70),f(-22,77),f(-40,83),f(-58,76),f(-53,66)), // Greenland
        arrayOf(f(-81,8),f(-77,8),f(-70,12),f(-60,10),f(-50,0),f(-44,-2),f(-35,-6),f(-38,-13),f(-48,-25),f(-58,-34),f(-64,-41),f(-66,-45),f(-71,-52),f(-75,-50),f(-73,-44),f(-71,-33),f(-71,-18),f(-78,-6),f(-80,-4)), // S.America
        arrayOf(f(-10,37),f(-9,43),f(-2,43),f(3,47),f(-2,49),f(-4,58),f(5,60),f(10,64),f(18,68),f(25,70),f(30,66),f(30,60),f(27,56),f(19,54),f(13,54),f(12,45),f(16,40),f(19,42),f(24,41),f(27,37),f(20,37),f(13,41),f(6,43),f(-2,36)), // Europe
        arrayOf(f(-5,50),f(-2,53),f(-3,58),f(-8,57),f(-6,52)), // British Isles
        arrayOf(f(-16,15),f(-17,21),f(-10,31),f(-2,36),f(10,37),f(11,33),f(20,32),f(25,32),f(33,31),f(35,24),f(43,12),f(51,12),f(51,6),f(43,4),f(41,-4),f(40,-15),f(35,-22),f(27,-33),f(20,-35),f(16,-29),f(12,-17),f(9,-1),f(5,5),f(-4,5),f(-8,4),f(-13,8)), // Africa
        arrayOf(f(43,-25),f(47,-24),f(50,-16),f(49,-12),f(45,-16)), // Madagascar
        arrayOf(f(26,40),f(27,45),f(24,55),f(33,60),f(30,68),f(45,68),f(60,70),f(73,72),f(100,77),f(130,73),f(142,66),f(160,61),f(170,66),f(178,68),f(168,60),f(155,52),f(142,48),f(132,42),f(122,40),f(121,32),f(112,22),f(109,21),f(105,10),f(100,6),f(95,16),f(90,22),f(88,22),f(80,8),f(77,8),f(72,20),f(68,24),f(58,25),f(57,37),f(48,30),f(44,38),f(36,40),f(36,36),f(27,36)), // Asia
        arrayOf(f(96,5),f(105,-6),f(115,-8),f(123,-9),f(135,-4),f(141,-8),f(130,-1),f(118,3),f(104,6)), // Indonesia
        arrayOf(f(130,31),f(135,34),f(140,37),f(142,42),f(139,38),f(134,34)), // Japan
        arrayOf(f(113,-22),f(122,-18),f(130,-12),f(137,-12),f(143,-11),f(146,-18),f(151,-24),f(153,-28),f(150,-38),f(143,-39),f(135,-35),f(129,-32),f(118,-34),f(114,-28)), // Australia
        arrayOf(f(166,-46),f(171,-41),f(175,-37),f(177,-40),f(168,-46)), // New Zealand
    )

    private val mapTop = 0.11f; private val mapBot = 0.60f
    private data class Dot(val x: Float, val y: Float)
    private val dots = ArrayList<Dot>()
    private var litDots = ArrayList<Dot>()

    // Устройство (условно РФ) и точка выхода (по выбранной стране). lon/lat → нормализ.
    private val dev = floatArrayOf(lon(37.6f), lat(55.75f))
    private var exit = floatArrayOf(lon(4.9f), lat(52.37f)) // по умолчанию — Нидерланды

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val devPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var staticLayer: Bitmap? = null
    private var connected = false
    private var running = false
    private var routeProg = 1f // 0..1 «затекание» дуги

    private val cDot = ContextCompat.getColor(context, R.color.mayak_map_dot)
    private val cLit = ContextCompat.getColor(context, R.color.mayak_map_lit)
    private val cRoute = ContextCompat.getColor(context, R.color.mayak_map_route)
    private val cGlow = ContextCompat.getColor(context, R.color.mayak_map_glow)
    private val cDev = ContextCompat.getColor(context, R.color.mayak_map_device)

    init {
        dotPaint.color = cDot
        litPaint.color = cLit
        glowPaint.color = cGlow
        devPaint.color = cDev
    }

    private fun reducedMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** Подключено → маршрут «затекает» и светится; отключено — тускло. */
    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        routeProg = if (value && !reducedMotion()) 0f else if (value) 1f else 0f
        invalidate()
    }

    /** Точка выхода по названию страны (грубый матч по подстроке; неизвестное → Нидерланды). */
    fun setExitByName(name: String?) {
        val n = name?.lowercase()?.trim() ?: return
        val p = when {
            n.contains("сша") || n.contains("америк") || n.contains("united states") || n.contains("usa") -> -74.0f to 40.7f
            n.contains("нидерл") || n.contains("netherl") || n.contains("голланд") -> 4.9f to 52.37f
            n.contains("герман") || n.contains("german") || n.contains("deutsch") -> 13.4f to 52.5f
            n.contains("франц") || n.contains("france") -> 2.35f to 48.85f
            n.contains("британ") || n.contains("великобр") || n.contains("англ") || n.contains("united kingdom") || n.contains("uk") -> -0.1f to 51.5f
            n.contains("росс") || n.contains("russia") -> 37.6f to 55.75f
            n.contains("финлянд") || n.contains("finland") -> 24.9f to 60.2f
            n.contains("швец") || n.contains("sweden") -> 18.1f to 59.3f
            n.contains("япон") || n.contains("japan") -> 139.7f to 35.7f
            n.contains("сингап") || n.contains("singapore") -> 103.8f to 1.35f
            n.contains("турц") || n.contains("turkey") -> 28.98f to 41.0f
            else -> 4.9f to 52.37f
        }
        setExit(p.first, p.second)
    }

    fun setExit(lonDeg: Float, latDeg: Float) {
        exit = floatArrayOf(lon(lonDeg), lat(latDeg))
        recomputeLit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild(w, h)
    }

    private fun rebuild(w: Int, h: Int) {
        staticLayer?.recycle()
        dots.clear()
        if (w <= 0 || h <= 0) { staticLayer = null; return }
        val cols = 132; val rows = 68
        for (i in 0 until cols) for (j in 0 until rows) {
            val nx = i / (cols - 1f); val ny = j / (rows - 1f)
            if (inAny(nx, ny)) dots.add(Dot(nx * w, mapY(ny) * h))
        }
        // статический слой — тусклые точки суши
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (d in dots) c.drawCircle(d.x, d.y, 1.5f, dotPaint)
        staticLayer = bmp
        recomputeLit()
    }

    private fun recomputeLit() {
        val w = width; val h = height
        litDots = ArrayList()
        if (w <= 0 || h <= 0) return
        val ex = exit[0] * w; val ey = mapY(exit[1]) * h
        for (d in dots) if (hypot(d.x - ex, d.y - ey) < h * 0.075f) litDots.add(d)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        staticLayer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val ax = dev[0] * w; val ay = mapY(dev[1]) * h
        val bx = exit[0] * w; val by = mapY(exit[1]) * h
        val prog = if (connected) routeProg else 0f

        // подсветка точек у выхода (только при подключении)
        if (connected && prog > 0.9f) {
            litPaint.style = Paint.Style.FILL
            for (d in litDots) canvas.drawCircle(d.x, d.y, 2f, litPaint)
        }

        // дуга-маршрут
        val mx = (ax + bx) / 2f
        val my = (ay + by) / 2f - hypot(bx - ax, by - ay) * 0.28f
        // базовая дуга видна ВСЕГДА (муть-золото), даже без подключения — «вот куда идёт трафик»
        faintPaint.color = cRoute; faintPaint.alpha = if (connected) 90 else 130; faintPaint.strokeWidth = 3f
        path.reset(); path.moveTo(ax, ay); path.quadTo(mx, my, bx, by)
        canvas.drawPath(path, faintPaint)
        if (prog > 0f) {
            routePaint.color = cRoute; routePaint.alpha = 235; routePaint.strokeWidth = 3.5f
            val n = (64 * prog).toInt()
            path.reset()
            for (i in 0..n) {
                val s = i / 64f
                val u = 1 - s
                val x = u * u * ax + 2 * u * s * mx + s * s * bx
                val y = u * u * ay + 2 * u * s * my + s * s * by
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, routePaint)
            // бегущий импульс
            if (prog >= 1f && !reducedMotion()) {
                val s = ((SystemClock.uptimeMillis() % 1400) / 1400f)
                val u = 1 - s
                val x = u * u * ax + 2 * u * s * mx + s * s * bx
                val y = u * u * ay + 2 * u * s * my + s * s * by
                canvas.drawCircle(x, y, 5f, glowPaint)
            }
        }
        // узлы: устройство (светлая/тёмная точка) + выход (золото) — оба видны ВСЕГДА
        devPaint.style = Paint.Style.FILL
        canvas.drawCircle(ax, ay, 4f, devPaint)
        glowPaint.style = Paint.Style.FILL
        val onExit = connected && prog > 0.9f
        glowPaint.alpha = if (onExit) 255 else 175
        canvas.drawCircle(bx, by, if (onExit) 5f else 4f, glowPaint)
        glowPaint.alpha = 255
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (connected && routeProg < 1f) routeProg = (routeProg + 0.03f).coerceAtMost(1f)
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS)
        }
    }

    fun startAnimation() {
        if (running) return
        running = true
        if (!reducedMotion()) postOnAnimation(frameTick) else invalidate()
    }

    fun stopAnimation() { running = false; removeCallbacks(frameTick) }

    override fun onDetachedFromWindow() {
        stopAnimation(); staticLayer?.recycle(); staticLayer = null
        super.onDetachedFromWindow()
    }

    // helpers
    private fun mapY(ny: Float) = mapTop + (mapBot - mapTop) * ny
    private fun inAny(x: Float, y: Float): Boolean { for (p in continents) if (inside(x, y, p)) return true; return false }
    private fun inside(x: Float, y: Float, poly: Array<FloatArray>): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i][0]; val yi = poly[i][1]; val xj = poly[j][0]; val yj = poly[j][1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    companion object {
        private const val FRAME_MS = 33L
        private fun lon(d: Float) = (d + 180f) / 360f
        private fun lat(d: Float) = (90f - d) / 180f
        private fun f(lonI: Int, latI: Int) = floatArrayOf(lon(lonI.toFloat()), lat(latI.toFloat()))
    }
}

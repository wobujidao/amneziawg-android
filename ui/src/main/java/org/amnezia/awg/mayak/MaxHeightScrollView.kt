package org.amnezia.awg.mayak

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * ScrollView с потолком высоты: обычный `wrap_content` (сжимается под контент — не занимает
 * места больше, чем реально нужно), но если содержимого больше потолка [android:maxHeight],
 * дальше включается прокрутка, а не выход экрана за пределы.
 *
 * Чинит «пустоту под списком стран» (docs/APP-BACKLOG.md, дизайн-заход 02-08): карточка
 * «Выберите страну» с обычным `layout_weight=1` растягивалась на весь низ экрана даже когда
 * строк всего две — ниже них оставалось ~500px пустого места и в светлой, и в тёмной теме.
 * Потолок нужен, чтобы при росте списка (сейчас 2 страны, дальше — больше) карточка не выросла
 * настолько, что уйдёт за нижний край экрана: экран не прокручивается целиком (верх должен
 * оставаться на месте, правка владельца 2026-07-18), поэтому сама прокрутка живёт здесь.
 *
 * `android:maxHeight` — штатный атрибут платформы (используется `ImageView`/`TextView`), но
 * базовый `View`/`ViewGroup` его не считывает сам — здесь просто применяем разбор к `ScrollView`.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ScrollView(context, attrs, defStyle) {

    private val maxHeightPx: Int

    init {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        maxHeightPx = a.getDimensionPixelSize(0, Int.MAX_VALUE)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx == Int.MAX_VALUE) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}

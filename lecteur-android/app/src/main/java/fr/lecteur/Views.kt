package fr.lecteur

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import kotlin.math.abs

/** Conteneur au format 16:9. */
class RatioLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
    }
}

/** Défilement horizontal qui cohabite avec le glissement entre les écrans. */
class NestedHScroll @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(ctx, attrs) {
    private var startX = 0f
    private var startY = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x
                startY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - startX
                val dy = e.y - startY
                if (abs(dy) > abs(dx) || !canScrollHorizontally(if (dx < 0) 1 else -1)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return super.onInterceptTouchEvent(e)
    }
}

package fr.telecommande

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Le pavé directionnel : anneau blanc (haut/bas/gauche/droite) et bouton OK au centre. */
@SuppressLint("ViewConstructor")
class DpadView(ctx: Context, private val onKey: (String, Boolean) -> Unit) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EAED.toInt() }
    private val ringPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBDC1C6.toInt() }
    private val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BODY }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202124.toInt() }
    private val centerPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3C4043.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA0A6.toInt() }
    private val oval = RectF()
    private var pressed: String? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (250 * d).toInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = if (w > 0) min(w, max) else max
        setMeasuredDimension(s, s)
    }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val radius get() = min(width, height) / 2f - 2 * d

    override fun onDraw(c: Canvas) {
        val r = radius
        c.drawCircle(cx, cy, r, ring)
        val p = pressed
        if (p != null && p != "ok") {
            val start = when (p) { "right" -> -45f; "down" -> 45f; "left" -> 135f; else -> 225f }
            oval.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(oval, start, 90f, true, ringPressed)
        }
        c.drawCircle(cx, cy, r * 0.47f, gap)
        c.drawCircle(cx, cy, r * 0.41f, if (p == "ok") centerPressed else center)
        val m = r * 0.735f
        val dr = 3.5f * d
        c.drawCircle(cx, cy - m, dr, dot)
        c.drawCircle(cx, cy + m, dr, dot)
        c.drawCircle(cx - m, cy, dr, dot)
        c.drawCircle(cx + m, cy, dr, dot)
    }

    private fun zoneAt(x: Float, y: Float): String? {
        val dx = x - cx
        val dy = y - cy
        val dist = hypot(dx, dy)
        val r = radius
        if (dist > r * 1.08f) return null
        if (dist <= r * 0.44f) return "ok"
        val a = Math.toDegrees(atan2(dy, dx).toDouble())
        return when {
            a >= -45 && a < 45 -> "right"
            a >= 45 && a < 135 -> "down"
            a >= -135 && a < -45 -> "up"
            else -> "left"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val z = zoneAt(e.x, e.y) ?: return false
                pressed = z
                if (Prefs.vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKey(z, true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed?.let { onKey(it, false) }
                pressed = null
                invalidate()
            }
        }
        return true
    }
}

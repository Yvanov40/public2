package fr.telecommande

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

/** Petits outils pour construire l'interface en code. */
object Ui {
    const val BG = 0xFF0F1013.toInt()
    const val BODY = 0xFF1C1E22.toInt()
    const val KEY = 0xFF2E3138.toInt()
    const val LIGHT = 0xFFE8EAED.toInt()
    const val ICON = 0xFFE8EAED.toInt()
    const val ICON_DARK = 0xFF3C4043.toInt()
    const val TEXT2 = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFF8AB4F8.toInt()

    fun dp(c: Context, v: Number) = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokeColor != null) setStroke(strokePx, strokeColor)
        }

    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Texte lisible sur un fond de cette couleur. */
    fun onColor(bg: Int): Int {
        val l = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        return if (l > 160) 0xFF202124.toInt() else Color.WHITE
    }

    fun text(c: Context, s: String, sizeSp: Float = 15f, color: Int = ICON, bold: Boolean = false) =
        TextView(c).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** Rangée : les vues sont réparties avec des espaces égaux entre elles. */
    fun spread(c: Context, vararg views: View): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            if (i > 0) addView(Space(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(v)
        }
    }

    /**
     * Rend une vue « touche » : appui = touche enfoncée, relâché = touche relâchée,
     * comme sur une vraie télécommande (maintenir pour répéter).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun bindKey(v: View, id: String, onKey: (String, Boolean) -> Unit) {
        v.contentDescription = Keys.get(id)?.label ?: id
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 0.55f
                    if (Prefs.vibrate) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKey(id, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.alpha = 1f
                    onKey(id, false)
                }
            }
            true
        }
    }

    /** Bouton rond avec icône. */
    fun roundKey(c: Context, icon: Int, sizeDp: Int, light: Boolean = false, iconDp: Int = 24): FrameLayout {
        val f = FrameLayout(c)
        f.background = oval(if (light) LIGHT else KEY)
        val iv = ImageView(c)
        iv.setImageResource(icon)
        iv.setColorFilter(if (light) ICON_DARK else ICON)
        f.addView(iv, FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER))
        f.layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        return f
    }
}

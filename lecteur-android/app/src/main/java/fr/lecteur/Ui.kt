package fr.lecteur

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.color.DynamicColors

/** Thème (couleur, noir pur) et police choisis dans les réglages. */
object Ui {
    fun applyTheme(a: Activity) {
        val opt = Settings.colors.firstOrNull { it.key == Settings.color } ?: Settings.colors[0]
        if (opt.key == "auto") {
            DynamicColors.applyToActivityIfAvailable(a)
        } else {
            // Palette fixe de l'appli : les couleurs dynamiques calculées ne s'appliquent pas
            // sur tous les téléphones (HyperOS notamment)
            a.theme.applyStyle(opt.overlay, true)
        }
        val night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night && Settings.black) a.theme.applyStyle(R.style.ThemeOverlay_Lecteur_Black, true)
    }

    private var cachedKey: String? = null
    private var cachedFace: Typeface? = null

    private fun family(): Typeface? {
        val key = Settings.font
        if (key != cachedKey) {
            cachedKey = key
            cachedFace = Settings.fonts.firstOrNull { it.first == key }?.third?.let { Typeface.create(it, Typeface.NORMAL) }
        }
        return cachedFace
    }

    /** Applique la police choisie à tous les textes d'une vue. */
    fun applyFont(v: View) {
        val face = family() ?: return
        when (v) {
            is TextView -> v.setTypeface(Typeface.create(face, v.typeface?.style ?: Typeface.NORMAL))
            is ViewGroup -> for (i in 0 until v.childCount) applyFont(v.getChildAt(i))
        }
    }

    /**
     * Barre de navigation du téléphone (◀ ● ■) : même fond que l'appli, sans le voile gris
     * qu'Android ajoute par-dessus, et des icônes claires ou sombres selon le thème.
     */
    @Suppress("DEPRECATION")
    fun styleSystemBars(a: Activity, color: Int, darkBackground: Boolean) {
        val w = a.window
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            w.isNavigationBarContrastEnforced = false
            w.isStatusBarContrastEnforced = false
        }
        w.navigationBarColor = color
        w.statusBarColor = color
        androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
            isAppearanceLightNavigationBars = !darkBackground
            isAppearanceLightStatusBars = !darkBackground
        }
    }
}

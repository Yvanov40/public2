package fr.lecteur

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/** Réglages de l'utilisateur. */
object Settings {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    class ColorOpt(val key: String, val label: String, val seed: Int, val overlay: Int)

    val colors = listOf(
        ColorOpt("auto", "Fond d'écran", 0, 0),
        ColorOpt("red", "Rouge", 0xFFB4231F.toInt(), R.style.ThemeOverlay_Lecteur_Red),
        ColorOpt("orange", "Orange", 0xFFC46A00.toInt(), R.style.ThemeOverlay_Lecteur_Orange),
        ColorOpt("pink", "Rose", 0xFFC2477F.toInt(), R.style.ThemeOverlay_Lecteur_Pink),
        ColorOpt("purple", "Violet", 0xFF6750A4.toInt(), R.style.ThemeOverlay_Lecteur_Purple),
        ColorOpt("blue", "Bleu", 0xFF0B57D0.toInt(), R.style.ThemeOverlay_Lecteur_Blue),
        ColorOpt("teal", "Turquoise", 0xFF008A8A.toInt(), R.style.ThemeOverlay_Lecteur_Teal),
        ColorOpt("green", "Vert", 0xFF1E8E3E.toInt(), R.style.ThemeOverlay_Lecteur_Green)
    )

    /** clé → (libellé, famille de police Android, null = police du système) */
    val fonts = listOf(
        Triple("system", "Système", null),
        Triple("condensed", "Condensée", "sans-serif-condensed"),
        Triple("light", "Fine", "sans-serif-light"),
        Triple("medium", "Épaisse", "sans-serif-medium"),
        Triple("serif", "Serif", "serif"),
        Triple("mono", "Machine", "monospace"),
        Triple("casual", "Manuscrite", "casual")
    )

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    var layout: String
        get() = str("layout", "cards")
        set(v) = put("layout", v)

    var columns: Int
        get() = p.getInt("columns", 1)
        set(v) = p.edit().putInt("columns", v).apply()

    var tileScale: Float
        get() = p.getFloat("tileScale", 1f)
        set(v) = p.edit().putFloat("tileScale", v).apply()

    var color: String
        get() = str("color", "auto")
        set(v) = put("color", v)

    var themeMode: String
        get() = str("theme", "system")
        set(v) = put("theme", v)

    var black: Boolean
        get() = p.getBoolean("black", false)
        set(v) = p.edit().putBoolean("black", v).apply()

    var font: String
        get() = str("font", "system")
        set(v) = put("font", v)

    var maxHeight: Int
        get() = p.getInt("maxHeight", 1080)
        set(v) = p.edit().putInt("maxHeight", v).apply()

    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    var skipSilence: Boolean
        get() = bool("skipSilence", false)
        set(v) = setBool("skipSilence", v)

    /** Égaliseur de volume retiré (grésillements sur certains téléphones) : toujours désactivé. */
    var normalize: Boolean
        get() = false
        set(v) = setBool("normalize", false)

    var autoplay: Boolean
        get() = bool("autoplay", false)
        set(v) = setBool("autoplay", v)

    var pip: Boolean
        get() = bool("pip", false)
        set(v) = setBool("pip", v)

    var sponsorBlock: Boolean
        get() = bool("sponsorBlock", true)
        set(v) = setBool("sponsorBlock", v)

    /** clé SponsorBlock → libellé */
    val sbCategories = listOf(
        "sponsor" to "Sponsors",
        "selfpromo" to "Autopromotion",
        "interaction" to "Rappels « abonne-toi »",
        "intro" to "Intros / attentes",
        "outro" to "Génériques de fin",
        "music_offtopic" to "Hors-sujet (musique)"
    )

    fun sbEnabled(cat: String) = bool("sb_$cat", cat in setOf("sponsor", "selfpromo", "interaction"))
    fun setSb(cat: String, v: Boolean) = setBool("sb_$cat", v)


    var fallback: Boolean
        get() = bool("fallback", true)
        set(v) = setBool("fallback", v)

    var checkNew: Boolean
        get() = bool("checkNew", true)
        set(v) = setBool("checkNew", v)

    var autoDownload: Boolean
        get() = bool("autoDownload", false)
        set(v) = setBool("autoDownload", v)

    /** Podcasts : ordre des épisodes (new, old, long, short) et filtre (all, unplayed, started, played). */
    var podSort: String
        get() = p.getString("podSort", "new") ?: "new"
        set(v) = p.edit().putString("podSort", v).apply()

    var podFilter: String
        get() = p.getString("podFilter", "all") ?: "all"
        set(v) = p.edit().putString("podFilter", v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0)
        set(v) = p.edit().putLong("lastUpdateCheck", v).apply()

    val cards get() = layout == "cards"

    fun applyNightMode() = AppCompatDelegate.setDefaultNightMode(
        when (themeMode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}

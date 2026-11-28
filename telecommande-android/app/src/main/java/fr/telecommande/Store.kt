package fr.telecommande

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Hid.init(this)
    }
}

object Prefs {
    private lateinit var p: SharedPreferences
    fun init(c: Context) { p = c.getSharedPreferences("reglages", Context.MODE_PRIVATE) }

    var lastDevice: String?
        get() = p.getString("dernier_appareil", null)
        set(v) { p.edit().putString("dernier_appareil", v).apply() }

    /** Les touches de volume du téléphone règlent le volume de la TV. */
    var phoneVolume: Boolean
        get() = p.getBoolean("volume_tel", true)
        set(v) { p.edit().putBoolean("volume_tel", v).apply() }

    var vibrate: Boolean
        get() = p.getBoolean("vibrer", true)
        set(v) { p.edit().putBoolean("vibrer", v).apply() }

    /** Pause entre deux étapes d'un raccourci (ms). */
    var stepDelay: Int
        get() = p.getInt("delai_etape", 350)
        set(v) { p.edit().putInt("delai_etape", v).apply() }

    var shortcutsJson: String?
        get() = p.getString("raccourcis", null)
        set(v) { p.edit().putString("raccourcis", v).apply() }
}

/** Un raccourci : un bouton de couleur qui joue une séquence de touches sur la TV. */
class Shortcut(var name: String, var color: Int, var steps: MutableList<String>)

object Shortcuts {
    val colors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFE50914.toInt(), 0xFFFF0000.toInt(), 0xFF00A8E1.toInt(),
        0xFF113CCF.toInt(), 0xFF1DB954.toInt(), 0xFFF5C518.toInt(), 0xFF8E44AD.toInt(),
        0xFFFF6D00.toInt(), 0xFF2E3138.toInt(),
    )

    val list: MutableList<Shortcut> by lazy { load() }

    private fun load(): MutableList<Shortcut> {
        val json = Prefs.shortcutsJson
        if (json == null) {
            // Les quatre boutons de la télécommande d'origine, à enregistrer une première fois
            return mutableListOf(
                Shortcut("YouTube", 0xFFFF0000.toInt(), mutableListOf()),
                Shortcut("Netflix", 0xFFE50914.toInt(), mutableListOf()),
                Shortcut("Prime Video", 0xFF00A8E1.toInt(), mutableListOf()),
                Shortcut("Disney+", 0xFF113CCF.toInt(), mutableListOf()),
            )
        }
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val st = o.optJSONArray("etapes") ?: JSONArray()
                Shortcut(o.optString("nom"), o.optInt("couleur", colors[0]),
                    MutableList(st.length()) { st.getString(it) })
            }
        } catch (_: Exception) { mutableListOf() }
    }

    fun save() {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("nom", s.name).put("couleur", s.color)
            .put("etapes", JSONArray(s.steps)))
        Prefs.shortcutsJson = arr.toString()
    }

    /** Texte lisible d'une étape (pour l'éditeur). */
    fun describe(step: String): String {
        val arg = step.substringAfter(':', "")
        return when (step.substringBefore(':')) {
            "k" -> Keys.get(arg)?.label ?: arg
            "w" -> "Pause ${arg} ms"
            "t" -> "Texte « $arg »"
            else -> step
        }
    }
}

package fr.telecommande

import java.text.Normalizer

/**
 * Une touche de la télécommande.
 * - [KB] : touche de clavier (page HID 0x07), rapport n° 1
 * - [CC] : touche « multimédia » (page HID Consumer 0x0C), rapport n° 2
 * [holdMs] > 0 : appui long simulé (ex. réglages = appui long sur Accueil sous Google TV).
 */
enum class Kind { KB, CC }

data class Key(val id: String, val label: String, val kind: Kind, val code: Int, val holdMs: Long = 0)

object Keys {
    private val list = listOf(
        Key("up", "Haut", Kind.KB, 0x52),
        Key("down", "Bas", Kind.KB, 0x51),
        Key("left", "Gauche", Kind.KB, 0x50),
        Key("right", "Droite", Kind.KB, 0x4F),
        Key("ok", "OK", Kind.KB, 0x28),
        Key("back", "Retour", Kind.CC, 0x224),      // AC Back -> KEYCODE_BACK
        Key("home", "Accueil", Kind.CC, 0x223),     // AC Home -> KEYCODE_HOME
        Key("settings", "Réglages", Kind.CC, 0x223, holdMs = 1100),
        Key("search", "Assistant / recherche", Kind.CC, 0x221), // AC Search -> KEYCODE_SEARCH
        Key("menu", "Menu", Kind.CC, 0x40),         // KEYCODE_MENU
        Key("power", "Marche/arrêt", Kind.CC, 0x30),
        Key("tv", "TV", Kind.CC, 0x89),             // KEYCODE_TV
        Key("guide", "Guide", Kind.CC, 0x8D),       // KEYCODE_GUIDE
        Key("volup", "Volume +", Kind.CC, 0xE9),
        Key("voldown", "Volume −", Kind.CC, 0xEA),
        Key("mute", "Muet", Kind.CC, 0xE2),
        Key("chup", "Chaîne +", Kind.CC, 0x9C),
        Key("chdown", "Chaîne −", Kind.CC, 0x9D),
        Key("playpause", "Lecture/pause", Kind.CC, 0xCD),
        Key("ff", "Avance rapide", Kind.CC, 0xB3),
        Key("rew", "Retour rapide", Kind.CC, 0xB4),
        Key("next", "Suivant", Kind.CC, 0xB5),
        Key("prev", "Précédent", Kind.CC, 0xB6),
        Key("backspace", "Effacer", Kind.KB, 0x2A),
        Key("esc", "Échap", Kind.KB, 0x29),
    )
    private val byId = list.associateBy { it.id }

    fun get(id: String): Key? = byId[id]

    /** Codes clavier (disposition QWERTY US, celle d'Android TV par défaut) : caractère -> (code, majuscule ?) */
    fun charToHid(c: Char): Pair<Int, Boolean>? {
        if (c in 'a'..'z') return Pair(0x04 + (c - 'a'), false)
        if (c in 'A'..'Z') return Pair(0x04 + (c - 'A'), true)
        if (c in '1'..'9') return Pair(0x1E + (c - '1'), false)
        if (c == '0') return Pair(0x27, false)
        return when (c) {
            ' ' -> Pair(0x2C, false)
            '\n' -> Pair(0x28, false)
            '-' -> Pair(0x2D, false); '_' -> Pair(0x2D, true)
            '=' -> Pair(0x2E, false); '+' -> Pair(0x2E, true)
            '[' -> Pair(0x2F, false); '{' -> Pair(0x2F, true)
            ']' -> Pair(0x30, false); '}' -> Pair(0x30, true)
            '\\' -> Pair(0x31, false); '|' -> Pair(0x31, true)
            ';' -> Pair(0x33, false); ':' -> Pair(0x33, true)
            '\'' -> Pair(0x34, false); '"' -> Pair(0x34, true)
            '`' -> Pair(0x35, false); '~' -> Pair(0x35, true)
            ',' -> Pair(0x36, false); '<' -> Pair(0x36, true)
            '.' -> Pair(0x37, false); '>' -> Pair(0x37, true)
            '/' -> Pair(0x38, false); '?' -> Pair(0x38, true)
            '!' -> Pair(0x1E, true); '@' -> Pair(0x1F, true)
            '#' -> Pair(0x20, true); '$' -> Pair(0x21, true)
            '%' -> Pair(0x22, true); '^' -> Pair(0x23, true)
            '&' -> Pair(0x24, true); '*' -> Pair(0x25, true)
            '(' -> Pair(0x26, true); ')' -> Pair(0x27, true)
            '’' -> Pair(0x34, false)
            else -> null
        }
    }

    /** Retire les accents (é -> e) : le clavier HID ne connaît que l'ASCII. */
    fun plain(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace('œ', 'o').replace('æ', 'a').replace('Œ', 'O').replace('Æ', 'A')
}

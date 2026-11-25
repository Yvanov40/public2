package fr.lecteur

/** Une vidéo telle qu'affichée dans les listes. */
data class Vid(
    val url: String,
    val title: String,
    val uploader: String?,
    val uploaderUrl: String?,
    val thumb: String?,
    val duration: Long,
    val meta: String? = null,
    val date: Long = 0
)

/** Une chaîne YouTube. */
data class Chan(val url: String, val name: String, val avatar: String?)

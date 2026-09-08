package eu.kanade.tachiyomi.animeextension.fr.frunified

/**
 * Libellés bilingues (français / anglais) de l'extension.
 *
 * [t] reçoit le libellé français (référence) et sa traduction anglaise ; la langue
 * active est choisie dans les réglages ([FrSettings.KEY_UI_LANGUAGE]). FR Unifié
 * n'est pas qu'une extension française : les utilisateurs anglophones choisissent
 * « English » dans la section « Général ».
 */
object L10n {
    const val FR = "fr"
    const val EN = "en"

    /** Langues proposées dans les réglages (« Langue de l'application »). */
    val UI_LANGUAGE_LABELS = linkedMapOf(FR to "Français", EN to "English")

    val current: String get() = FrSettings.uiLanguage

    fun t(fr: String, en: String): String = if (current == EN) en else fr
}

package eu.kanade.tachiyomi.animeextension.fr.frunified

import java.util.Locale

/**
 * Libellé uniforme et lisible d'un flux, dans l'ordre langue · qualité · source · moteur :
 *
 * ```
 * (VF) 1080p · flemmix · Nuvio · Uqload
 *  │     │       │        │        └ détail facultatif (lecteur, torrent, nom de fichier…)
 *  │     │       │        └ moteur : Nuvio ou Stremio
 *  │     │       └ source : site Nuvio ou addon Stremio
 *  │     └ qualité
 *  └ langue audio
 * ```
 *
 * Le rendu est volontairement déterministe : Aniyomi ne conserve que le titre d'une
 * vidéo lorsqu'il trie ou regroupe les flux, [parse] permet donc de retrouver la
 * langue, la qualité et la source depuis n'importe quel titre produit ici.
 */
data class StreamLabel(
    val language: String?,
    val quality: Int?,
    val source: String,
    val engine: String,
    val detail: String? = null,
) {
    fun render(): String = buildString {
        val header = listOfNotNull(language?.let { "($it)" }, quality?.let(::qualityText)).joinToString(" ")
        if (header.isNotEmpty()) {
            append(header)
            append(SEPARATOR)
        }
        append(clean(source).ifBlank { "Source" })
        append(SEPARATOR)
        append(clean(engine).ifBlank { ENGINE_NUVIO })
        detail?.let(::clean)?.takeIf(String::isNotBlank)?.let {
            append(SEPARATOR)
            append(it)
        }
    }

    companion object {
        const val SEPARATOR = " · "
        const val ENGINE_NUVIO = "Nuvio"
        const val ENGINE_STREMIO = "Stremio"

        /** Langues audio reconnues, de la plus recherchée à la moins recherchée. */
        val LANGUAGE_ORDER = listOf("VF", "VFF", "VFQ", "MULTI", "VOSTFR", "VO")

        /** Qualités proposées au classement, de la plus courante à la plus rare. */
        val QUALITY_VALUES = listOf(4320, 2160, 1440, 1080, 720, 540, 480, 360)

        private val ENGINES = setOf(ENGINE_NUVIO, ENGINE_STREMIO)
        private val HEADER = Regex("^(?:\\(([^()]+)\\))?\\s*(8K|4K|\\d{3,4}p)?$")
        private const val LANGUAGE_TOKENS = "VOSTFR|VOSTF|VOST|VFQ|VFF|VF|MULTI|TRUEFRENCH|FRENCH|VO"
        private const val QUALITY_TOKENS =
            "4320p?|2160p?|1440p?|1080p?|720p?|540p?|480p?|360p?|8K|4K|UHD|FULL ?HD|FHD|HD|SD"
        private val BRACKETED_TAG = Regex("(?i)[\\[(]\\s*(?:$LANGUAGE_TOKENS|$QUALITY_TOKENS)\\s*[\\])]")
        private val LOOSE_TAG = Regex("(?i)(^|[^A-Z0-9])(?:$LANGUAGE_TOKENS|$QUALITY_TOKENS)(?=[^A-Z0-9]|$)")
        private const val EPISODE_TOKENS = "S\\d{1,2}\\s*E\\d{1,3}|(?:saison|season|épisode|episode|ep)\\s*\\d{1,4}"
        private val EPISODE_TAG = Regex("(?i)(^|[^A-Z0-9])(?:$EPISODE_TOKENS)(?=[^A-Z0-9]|$)")
        private val PUNCTUATION = Regex("[\\[\\]()|•·]+")
        private val LOOSE_SEPARATORS = Regex("[\\s.,_\\-]*\\s[\\s.,_\\-]*")
        private val SPACES = Regex("\\s+")
        private const val DETAIL_LIMIT = 48

        fun qualityText(quality: Int): String = when (quality) {
            4320 -> "8K"
            2160 -> "4K"
            else -> "${quality}p"
        }

        /** Libellé long d'une qualité pour les réglages. */
        fun qualityLabel(quality: Int): String = when (quality) {
            4320 -> "8K (4320p)"
            2160 -> "4K (2160p)"
            1440 -> "1440p (QHD)"
            1080 -> "1080p (Full HD)"
            720 -> "720p (HD)"
            480 -> "480p (SD)"
            360 -> "360p"
            else -> "${quality}p"
        }

        /** Libellé long d'une langue pour les réglages. */
        fun languageLabel(language: String): String = when (language) {
            "VF" -> "VF — doublage français"
            "VFF" -> "VFF — doublage français de France"
            "VFQ" -> "VFQ — doublage québécois"
            "MULTI" -> "MULTI — plusieurs pistes audio"
            "VOSTFR" -> "VOSTFR — version originale sous-titrée"
            "VO" -> "VO — version originale"
            else -> language
        }

        /** Convertit un jeton de qualité (`1080p`, `4K`, `HD`, `FULLHD`…) en résolution. */
        fun qualityValue(token: String): Int? {
            val upper = token.trim().uppercase(Locale.ROOT).replace(" ", "")
            return when (upper) {
                "4320", "4320P", "8K" -> 4320

                "2160", "2160P", "4K", "UHD" -> 2160

                "1440", "1440P", "QHD" -> 1440

                "1080", "1080P", "FULLHD", "FHD" -> 1080

                "720", "720P", "HD" -> 720

                "480", "480P", "SD" -> 480

                "360", "360P" -> 360

                else -> Regex("^(\\d{3,4})P?$").matchEntire(upper)?.groupValues?.get(1)?.toIntOrNull()
                    ?.takeIf { it in 144..8640 }
            }
        }

        /** Retrouve les composantes d'un titre produit par [render] ; `null` pour un titre étranger. */
        fun parse(title: String): StreamLabel? {
            val parts = title.split(SEPARATOR).map(String::trim)
            if (parts.size < 2) return null
            val header = HEADER.matchEntire(parts[0])?.takeIf { parts[0].isNotEmpty() && parts.size >= 3 }
            val offset = if (header != null) 1 else 0
            val engine = parts.getOrNull(offset + 1)?.takeIf { it in ENGINES } ?: return null
            val language = header?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
            val quality = header?.groupValues?.getOrNull(2)?.takeIf(String::isNotBlank)?.let { value ->
                when (value) {
                    "8K" -> 4320
                    "4K" -> 2160
                    else -> value.removeSuffix("p").toIntOrNull()
                }
            }
            val detail = parts.drop(offset + 2).joinToString(SEPARATOR).ifBlank { null }
            return StreamLabel(language, quality, parts[offset], engine, detail)
        }

        /**
         * Langue d'un titre de flux : celle affichée en tête lorsqu'il a été produit par [render]
         * (le nom de la source n'est alors jamais réinterprété), sinon celle détectée dans le texte.
         */
        fun languageIn(title: String): String? {
            val label = parse(title) ?: return languageOf(title)
            return label.language
        }

        /** Détecte VF / VFF / VFQ / MULTI / VOSTFR / VO dans un texte libre. */
        fun languageOf(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val upper = text.uppercase(Locale.ROOT)
            Regex("(?<!\\d)(\\d{3,4})P(?![A-Z0-9])").find(upper)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 144..8640 }?.let { return it }
            return when {
                hasToken(upper, "VOSTFR|VOSTF|VOST") -> "VOSTFR"
                hasToken(upper, "VFQ") -> "VFQ"
                hasToken(upper, "VFF") -> "VFF"
                upper.contains("TRUEFRENCH") -> "VF"
                hasToken(upper, "VF|FRENCH") -> "VF"
                hasToken(upper, "MULTI") -> "MULTI"
                hasToken(upper, "VO") -> "VO"
                else -> null
            }
        }

        /** Convertit un code de langue (`fr`, `tr`, `en-US`…) en étiquette courte. */
        fun languageFromCode(code: String?): String? {
            val value = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.takeIf(String::isNotBlank)
                ?: return null
            return when (value) {
                "fr", "fre", "fra", "french", "français", "francais", "vf" -> "VF"
                "vostfr", "vost" -> "VOSTFR"
                "multi" -> "MULTI"
                "vo" -> "VO"
                else -> value.takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.uppercase(Locale.ROOT)
            }
        }

        /**
         * Langue déduite de la déclaration d'un provider lorsqu'un flux n'en indique aucune.
         * Un provider français peut servir de la VF comme de la VOSTFR : on ne devine pas.
         */
        fun languageFromProvider(languages: List<String>): String? {
            val codes = languages
                .map { it.lowercase(Locale.ROOT).substringBefore('-').trim() }
                .filter(String::isNotBlank)
                .distinct()
            val single = codes.singleOrNull() ?: return null
            if (single == "fr") return null
            return languageFromCode(single)
        }

        /** Résolution verticale déduite d'un texte libre (`1080p`, `Full HD`, `4K`…). */
        fun qualityOf(text: String?): Int? {
            if (text.isNullOrBlank()) return null
            val upper = text.uppercase(Locale.ROOT)
            return when {
                upper.contains("4320") || hasToken(upper, "8K") -> 4320
                upper.contains("2160") || hasToken(upper, "4K|UHD") -> 2160
                upper.contains("1440") -> 1440
                upper.contains("1080") || hasToken(upper, "FULL ?HD|FHD|FULLHD") -> 1080
                upper.contains("720") || hasToken(upper, "HD") -> 720
                upper.contains("480") || hasToken(upper, "SD") -> 480
                upper.contains("360") -> 360
                else -> null
            }
        }

        /**
         * Détail lisible d'un flux : le libellé brut débarrassé de la source, des titres
         * connus de l'œuvre et des étiquettes de langue et de qualité déjà affichées en
         * tête, puis borné à [DETAIL_LIMIT] caractères.
         */
        fun detail(
            parts: List<String?>,
            source: String,
            prefix: String? = null,
            noise: List<String> = emptyList(),
        ): String? {
            var text = parts.filterNotNull().joinToString(" ").replace('\n', ' ')
            (listOf(source) + noise).filter { it.length >= 3 }.sortedByDescending(String::length).forEach {
                text = text.replace(it, " ", ignoreCase = true)
            }
            text = text.replace(BRACKETED_TAG, " ")
            text = text.replace(LOOSE_TAG, "$1 ")
            text = text.replace(EPISODE_TAG, "$1 ")
            text = text.replace(PUNCTUATION, " ")
                .replace(LOOSE_SEPARATORS, " ")
                .trim { it.isWhitespace() || it in "-–_,.:|/" }
            val body = text.take(DETAIL_LIMIT).trim().takeIf(String::isNotBlank)
            return listOfNotNull(prefix?.takeIf(String::isNotBlank), body).joinToString(" ").ifBlank { null }
        }

        /** Nom de serveur : « Nuvio · flemmix : VF, VOSTFR ». */
        fun hosterName(engine: String, source: String, titles: List<String>): String {
            val languages = titles.mapNotNull(::languageIn).distinct().sortedBy(::languageRank)
            val base = "${clean(engine)}$SEPARATOR${clean(source).ifBlank { "Source" }}"
            return if (languages.isEmpty()) base else "$base : ${languages.joinToString(", ")}"
        }

        /** Langues annoncées par un nom de serveur produit par [hosterName]. */
        fun hosterLanguages(hosterName: String): List<String> {
            if (!hosterName.contains(" : ")) return emptyList()
            return hosterName.substringAfterLast(" : ").split(',').map(String::trim).filter(String::isNotBlank)
        }

        /** Rang d'une langue dans [LANGUAGE_ORDER] (inconnue = dernière). */
        fun languageRank(language: String?): Int =
            LANGUAGE_ORDER.indexOf(language).let { if (it < 0) LANGUAGE_ORDER.size else it }

        private fun hasToken(upper: String, tokens: String): Boolean =
            Regex("(^|[^A-Z0-9])(?:$tokens)([^A-Z0-9]|$)").containsMatchIn(upper)

        private fun clean(value: String): String =
            value.replace(SEPARATOR, " - ").replace('·', '-').replace(SPACES, " ").trim()
    }
}

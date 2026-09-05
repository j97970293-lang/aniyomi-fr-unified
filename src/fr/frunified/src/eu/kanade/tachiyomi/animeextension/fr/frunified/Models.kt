package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.json.JSONObject

/** Identifiant stable d'une fiche du catalogue unifié. */
data class CatalogId(
    val catalog: String,
    val kind: String,
    val id: String,
    val season: Int? = null,
) {
    fun serialize(): String = buildString {
        append(catalog)
        append('/')
        append(kind)
        append('/')
        append(id)
        season?.let {
            append("/season/")
            append(it)
        }
    }

    companion object {
        fun parse(raw: String?): CatalogId? {
            if (raw.isNullOrBlank()) return null
            val clean = raw.substringAfter("://", raw)
                .substringAfter("frunified/", raw)
                .trim('/')
            val parts = clean.split('/')
            val start = parts.indexOfFirst { it in setOf("tmdb", "anilist", "mal") }
            if (start < 0 || parts.size < start + 3) return null
            val season = if (parts.getOrNull(start + 3) == "season") {
                parts.getOrNull(start + 4)?.toIntOrNull()
            } else {
                null
            }
            return CatalogId(
                catalog = parts[start],
                kind = parts[start + 1],
                id = parts[start + 2],
                season = season,
            )
        }
    }
}

data class CatalogItem(
    val id: CatalogId,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val rating10: Double? = null,
    val episodeCount: Int? = null,
    val format: String? = null,
) {
    val titles: List<String>
        get() = listOfNotNull(title, originalTitle).distinct()

    fun toSAnime(): SAnime = SAnime.create().apply {
        url = id.serialize()
        title = this@CatalogItem.title
        thumbnail_url = posterUrl
        background_url = backdropUrl ?: posterUrl
        description = buildString {
            overview?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }
            val meta = listOfNotNull(
                year?.let { "Année : $it" },
                rating10?.let { "Note : ${"%.1f".format(it)}/10" },
            )
            if (meta.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(meta.joinToString(" · "))
            }
        }.ifBlank { null }
        status = SAnime.UNKNOWN
        fetch_type = if (id.kind == "tv") FetchType.Seasons else FetchType.Episodes
    }
}

/** Données nécessaires à la résolution des flux d'un film ou épisode. */
data class PlayPayload(
    val kind: String,
    val titles: List<String>,
    val year: Int?,
    val season: Int? = null,
    val episode: Int? = null,
    val absoluteEpisode: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val anilistId: Int? = null,
    val malId: Int? = null,
) {
    val isSeries: Boolean get() = kind != "movie"
    val primaryTitle: String get() = titles.firstOrNull().orEmpty()

    fun serialize(): String {
        val raw = JSONObject().apply {
            put("kind", kind)
            put("titles", titles.joinToString("|"))
            year?.let { put("year", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            absoluteEpisode?.let { put("abs", it) }
            tmdbId?.let { put("tmdb", it) }
            imdbId?.let { put("imdb", it) }
            anilistId?.let { put("anilist", it) }
            malId?.let { put("mal", it) }
        }.toString()
        return "play/" +
            Base64.encodeToString(raw.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        fun parse(raw: String?): PlayPayload? = runCatching {
            if (raw.isNullOrBlank()) return@runCatching null
            val encoded = raw.substringAfter("play/", raw)
            val text = if (encoded.startsWith("{")) {
                encoded
            } else {
                String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            }
            val json = JSONObject(text)
            PlayPayload(
                kind = json.optString("kind", "movie"),
                titles = json.optString("titles").split('|').filter { it.isNotBlank() },
                year = json.optInt("year").takeIf { it > 0 },
                season = json.optInt("season").takeIf { json.has("season") },
                episode = json.optInt("episode").takeIf { json.has("episode") },
                absoluteEpisode = json.optInt("abs").takeIf { json.has("abs") },
                tmdbId = json.optInt("tmdb").takeIf { it > 0 },
                imdbId = json.optString("imdb").takeIf { it.startsWith("tt") },
                anilistId = json.optInt("anilist").takeIf { it > 0 },
                malId = json.optInt("mal").takeIf { it > 0 },
            )
        }.getOrNull()
    }
}

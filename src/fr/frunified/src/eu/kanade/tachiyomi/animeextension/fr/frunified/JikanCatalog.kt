package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object JikanCatalog {
    private const val API = "https://api.jikan.moe/v4"
    private const val EPISODE_CACHE_MS = 6 * 60 * 60 * 1000L
    private val episodeCountCache = ConcurrentHashMap<String, Pair<Long, Int>>()

    private suspend fun fetch(path: String): JSONObject? =
        runCatching { FrRuntime.getJson("$API/$path") }.getOrNull()

    fun item(json: JSONObject): CatalogItem? {
        val id = json.optInt("mal_id").takeIf { it > 0 } ?: return null
        val title = if (FrSettings.catalogLanguage.startsWith("ja")) {
            json.optString("title_japanese").takeIf { it.isNotBlank() && it != "null" }
                ?: json.optString("title").takeIf(String::isNotBlank)
        } else {
            json.optString("title_english").takeIf { it.isNotBlank() && it != "null" }
                ?: json.optString("title").takeIf(String::isNotBlank)
        } ?: return null
        val original = json.optString("title").takeIf { it.isNotBlank() && !it.equals(title, true) }
        val type = json.optString("type")
        return CatalogItem(
            id = CatalogId("mal", "anime", id.toString()),
            title = title,
            originalTitle = original,
            year = json.optInt("year").takeIf { it > 0 }
                ?: json.optJSONObject("aired")?.optJSONObject("prop")?.optJSONObject("from")
                    ?.optInt("year")?.takeIf { it > 0 },
            posterUrl = json.optJSONObject("images")?.optJSONObject("jpg")?.let {
                it.optString("large_image_url").ifBlank { it.optString("image_url") }
            }?.takeIf(String::isNotBlank),
            overview = json.optString("synopsis").takeIf { it.isNotBlank() && it != "null" },
            rating10 = json.optDouble("score").takeIf { !it.isNaN() && it > 0 },
            episodeCount = json.optInt("episodes").takeIf { it > 0 },
            format = if (type.equals("Movie", true)) "MOVIE" else type,
        )
    }

    private fun list(root: JSONObject?): List<CatalogItem> {
        val data = root?.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { index -> data.optJSONObject(index)?.let(::item) }
    }

    suspend fun row(kind: String, page: Int): List<CatalogItem> = when (kind) {
        "trending" -> list(fetch("seasons/now?page=$page&sfw=true"))
        "top" -> list(fetch("top/anime?page=$page&sfw=true"))
        else -> list(fetch("top/anime?filter=bypopularity&page=$page&sfw=true"))
    }

    suspend fun search(query: String, page: Int): List<CatalogItem> =
        list(fetch("anime?q=${URLEncoder.encode(query, "UTF-8")}&page=$page&sfw=true&limit=25"))

    suspend fun details(id: String): JSONObject? = fetch("anime/$id/full")?.optJSONObject("data")

    /**
     * AniList et la fiche Jikan laissent `episodes` à null pour les séries en cours.
     * L'endpoint paginé des épisodes expose cependant sa dernière page : deux
     * requêtes suffisent donc pour retrouver le dernier numéro réellement publié.
     */
    suspend fun episodeCount(id: String): Int? {
        val now = System.currentTimeMillis()
        episodeCountCache[id]?.let { (expires, count) -> if (expires > now) return count }
        val count = episodeCount(id) { path -> fetchEpisodePage(path) } ?: return null
        episodeCountCache[id] = (now + EPISODE_CACHE_MS) to count
        return count
    }

    internal suspend fun episodeCount(
        id: String,
        fetchPage: suspend (String) -> JSONObject?,
    ): Int? {
        val first = fetchPage("anime/$id/episodes?page=1") ?: return null
        val lastPage = first.optJSONObject("pagination")?.optInt("last_visible_page", 1)
            ?.coerceIn(1, 50) ?: 1
        val last = if (lastPage == 1) {
            first
        } else {
            // Ne jamais prendre la page 1 pour la dernière : cela mettrait en cache 100
            // épisodes pour One Piece lorsqu'une seule requête Jikan est limitée.
            fetchPage("anime/$id/episodes?page=$lastPage") ?: return null
        }
        val data = last.optJSONArray("data") ?: return null
        val highestNumber = (0 until data.length()).maxOfOrNull { index ->
            data.optJSONObject(index)?.optInt("mal_id", 0) ?: 0
        }?.takeIf { it > 0 }
        val count = highestNumber
            ?: (((lastPage - 1) * 100) + data.length()).takeIf { it > 0 }
            ?: return null
        return count.takeIf { it in 1..5_000 }
    }

    private suspend fun fetchEpisodePage(path: String): JSONObject? {
        repeat(3) { attempt ->
            fetch(path)?.let { return it }
            if (attempt < 2) delay(1_100L)
        }
        return null
    }

    fun allTitles(data: JSONObject): List<String> {
        val base = listOf("title_english", "title", "title_japanese").mapNotNull { key ->
            data.optString(key).takeIf { it.isNotBlank() && it != "null" }
        }
        val synonyms = data.optJSONArray("title_synonyms")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val variants = data.optJSONArray("titles")?.let { array ->
            (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.optString("title")?.takeIf(String::isNotBlank)
            }
        }.orEmpty()
        return (base + synonyms + variants).distinct()
    }
}

object AnimeCatalog {
    suspend fun row(kind: String, page: Int): List<CatalogItem> {
        val sort = when (kind) {
            "trending" -> "TRENDING_DESC"
            "top" -> "SCORE_DESC"
            else -> "POPULARITY_DESC"
        }
        val aniList = if (FrSettings.useAniListCatalog) {
            runCatching { AniListCatalog.row(sort, page) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val jikan = if (aniList.isEmpty() && FrSettings.useJikanCatalog) {
            runCatching { JikanCatalog.row(kind, page) }.getOrDefault(emptyList())
        } else {
            aniList
        }
        return if (jikan.isEmpty() && FrSettings.useTmdbCatalog) {
            runCatching { TmdbCatalog.animeRow(kind, page) }.getOrDefault(emptyList())
        } else {
            jikan
        }
    }

    /**
     * Recherche d'animés : AniList, puis Jikan, puis TMDB en repli.
     * En [quick] (recherche rapide), les replis Jikan et TMDB sont sautés : AniList répond
     * en une requête, alors que Jikan est limité en débit et TMDB filtre après coup.
     */
    suspend fun search(query: String, page: Int, quick: Boolean = false): List<CatalogItem> {
        val aniList = if (FrSettings.useAniListCatalog) {
            runCatching { AniListCatalog.search(query, page) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (quick && FrSettings.useAniListCatalog) return aniList
        val jikan = if (aniList.isEmpty() && FrSettings.useJikanCatalog) {
            runCatching { JikanCatalog.search(query, page) }.getOrDefault(emptyList())
        } else {
            aniList
        }
        if (quick) return jikan
        return if (jikan.isEmpty() && FrSettings.useTmdbCatalog) {
            runCatching { TmdbCatalog.animeSearch(query, page) }.getOrDefault(emptyList())
        } else {
            jikan
        }
    }
}

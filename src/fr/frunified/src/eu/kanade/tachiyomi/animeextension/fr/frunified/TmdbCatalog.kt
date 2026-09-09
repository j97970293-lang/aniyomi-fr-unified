package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object TmdbCatalog {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"
    private const val CACHE_TTL = 15 * 60 * 1000L

    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val all = linkedMapOf(
            "api_key" to FrSettings.tmdbApiKey,
            "language" to FrSettings.catalogLanguage,
            "region" to FrSettings.catalogRegion,
            "include_adult" to "false",
        )
        all.putAll(params)
        return "$API/${path.trimStart('/')}?" +
            all.entries.joinToString("&") {
                "${enc(it.key)}=${enc(it.value)}"
            }
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val requestUrl = url(path, params)
        val now = System.currentTimeMillis()
        cache[requestUrl]?.let { (expires, value) -> if (expires > now) return value }
        val json = trySuspend { FrRuntime.getJson(requestUrl) }.getOrNull() ?: return null
        cache[requestUrl] = (now + CACHE_TTL) to json
        return json
    }

    fun image(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG/$size$it" }

    fun toItem(json: JSONObject, forcedKind: String? = null): CatalogItem? {
        val id = json.optInt("id").takeIf { it > 0 } ?: return null
        val kind = forcedKind ?: json.optString("media_type").ifBlank {
            if (json.has("title") || json.has("release_date")) "movie" else "tv"
        }
        if (kind !in setOf("movie", "tv")) return null
        val title = json.optString("title").ifBlank { json.optString("name") }.ifBlank { return null }
        val original = json.optString("original_title").ifBlank { json.optString("original_name") }
            .takeIf { it.isNotBlank() && it != "null" && !it.equals(title, true) }
        val date = json.optString("release_date").ifBlank { json.optString("first_air_date") }
        return CatalogItem(
            id = CatalogId("tmdb", kind, id.toString()),
            title = title,
            originalTitle = original,
            year = date.take(4).toIntOrNull(),
            posterUrl = image(json.optString("poster_path")),
            backdropUrl = image(json.optString("backdrop_path"), "w1280"),
            overview = json.optString("overview").takeIf { it.isNotBlank() && it != "null" },
            rating10 = json.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 },
            episodeCount = json.optInt("number_of_episodes").takeIf { it > 0 },
        )
    }

    private fun list(root: JSONObject?, forcedKind: String? = null): List<CatalogItem> {
        val results: JSONArray = root?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.let { toItem(it, forcedKind) }
        }
    }

    suspend fun search(query: String, page: Int = 1): List<CatalogItem> =
        list(get("search/multi", mapOf("query" to query, "page" to page.toString())))

    /** Tertiary anime fallback used only while both AniList and Jikan are unavailable. */
    suspend fun animeSearch(query: String, page: Int = 1): List<CatalogItem> {
        val results = get("search/multi", mapOf("query" to query, "page" to page.toString()))
            ?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val json = results.optJSONObject(index) ?: return@mapNotNull null
            val genres = json.optJSONArray("genre_ids")
            val isAnimation = genres != null && (0 until genres.length()).any { genres.optInt(it) == 16 }
            val isJapanese = json.optString("original_language").equals("ja", true)
            if (isAnimation && isJapanese) toItem(json) else null
        }
    }

    suspend fun animeRow(kind: String, page: Int): List<CatalogItem> {
        val sort = when (kind) {
            "top" -> "vote_average.desc"
            else -> "popularity.desc"
        }
        val common = mapOf(
            "with_genres" to "16",
            "with_original_language" to "ja",
            "sort_by" to sort,
            "vote_count.gte" to if (kind == "top") "100" else "0",
        )
        val tv = row("discover/tv", page, common, "tv")
        val movies = row("discover/movie", page, common, "movie")
        return (tv + movies).sortedByDescending { it.rating10 ?: 0.0 }
    }

    suspend fun row(
        path: String,
        page: Int,
        params: Map<String, String> = emptyMap(),
        kind: String? = null,
    ): List<CatalogItem> = list(get(path, params + ("page" to page.toString())), kind)

    suspend fun details(id: CatalogId): JSONObject? {
        val append = if (id.kind == "tv") {
            "credits,videos,external_ids,recommendations,content_ratings,aggregate_credits"
        } else {
            "credits,videos,external_ids,recommendations,release_dates"
        }
        return get("${id.kind}/${id.id}", mapOf("append_to_response" to append))
    }

    suspend fun season(seriesId: String, number: Int): JSONObject? = get("tv/$seriesId/season/$number")

    suspend fun alternativeTitles(id: CatalogId): List<String> {
        val root = get("${id.kind}/${id.id}/alternative_titles") ?: return emptyList()
        val values = root.optJSONArray("titles") ?: root.optJSONArray("results") ?: return emptyList()
        return (0 until values.length()).mapNotNull { index ->
            val entry = values.optJSONObject(index) ?: return@mapNotNull null
            val country = entry.optString("iso_3166_1")
            if (country in setOf("FR", "BE", "CA", "US", "GB", "JP")) {
                entry.optString("title").takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.distinct()
    }

    suspend fun searchBest(query: String, year: Int?, knownTitles: List<String> = listOf(query)): CatalogItem? {
        val items = search(query, 1)
        val best = items.maxByOrNull { item ->
            item.titles.maxOfOrNull { candidateTitle ->
                TitleMatch.score(knownTitles, candidateTitle, year, item.year)
            } ?: 0.0
        } ?: return null
        val bestScore = best.titles.maxOfOrNull { candidateTitle ->
            TitleMatch.score(knownTitles, candidateTitle, year, best.year)
        } ?: 0.0
        return best.takeIf { bestScore >= 0.50 }
    }

    suspend fun findTmdbId(payload: PlayPayload): Int? {
        payload.tmdbId?.let { return it }
        // Avec l'année d'abord, puis sans : les fiches AniList/MAL datent souvent d'une autre
        // année que TMDB (première diffusion vs sortie française) ou n'en ont pas du tout.
        val years = if (payload.year != null) listOf(payload.year, null) else listOf(null)
        for (year in years) {
            var best: Pair<Double, Int>? = null
            for (title in payload.titles.take(6)) {
                val item = trySuspend { searchBest(title, year, payload.titles) }.getOrNull() ?: continue
                val id = item.id.id.toIntOrNull() ?: continue
                val score = item.titles.maxOfOrNull { TitleMatch.score(payload.titles, it, year, item.year) } ?: 0.0
                if (best == null || score > best!!.first) best = score to id
            }
            best?.let { return it.second }
        }
        return null
    }

    suspend fun imdbId(payload: PlayPayload): String? {
        payload.imdbId?.let { return it }
        val tmdb = findTmdbId(payload) ?: return null
        val kind = if (payload.isSeries) "tv" else "movie"
        return details(CatalogId("tmdb", kind, tmdb.toString()))
            ?.optJSONObject("external_ids")
            ?.optString("imdb_id")
            ?.takeIf { it.startsWith("tt") }
    }
}

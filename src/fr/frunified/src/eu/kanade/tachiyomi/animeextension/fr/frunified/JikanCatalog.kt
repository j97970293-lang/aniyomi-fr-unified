package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.json.JSONObject
import java.net.URLEncoder

object JikanCatalog {
    private const val API = "https://api.jikan.moe/v4"

    private suspend fun fetch(path: String): JSONObject? =
        runCatching { FrRuntime.getJson("$API/$path") }.getOrNull()

    fun item(json: JSONObject): CatalogItem? {
        val id = json.optInt("mal_id").takeIf { it > 0 } ?: return null
        val title = json.optString("title_english").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("title").takeIf(String::isNotBlank)
            ?: return null
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
        val aniList = runCatching { AniListCatalog.row(sort, page) }.getOrDefault(emptyList())
        val jikan = aniList.ifEmpty {
            runCatching { JikanCatalog.row(kind, page) }.getOrDefault(emptyList())
        }
        return jikan.ifEmpty {
            runCatching { TmdbCatalog.animeRow(kind, page) }.getOrDefault(emptyList())
        }
    }

    suspend fun search(query: String, page: Int): List<CatalogItem> {
        val aniList = runCatching { AniListCatalog.search(query, page) }.getOrDefault(emptyList())
        val jikan = aniList.ifEmpty {
            runCatching { JikanCatalog.search(query, page) }.getOrDefault(emptyList())
        }
        return jikan.ifEmpty {
            runCatching { TmdbCatalog.animeSearch(query, page) }.getOrDefault(emptyList())
        }
    }
}

package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.json.JSONObject

object AniListCatalog {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private const val MEDIA_FIELDS = """
        id
        idMal
        title { romaji english native userPreferred }
        synonyms
        description(asHtml: false)
        startDate { year }
        seasonYear
        format
        status
        episodes
        nextAiringEpisode { episode airingAt }
        duration
        averageScore
        genres
        coverImage { extraLarge large }
        bannerImage
    """

    private suspend fun query(query: String, variables: Map<String, Any?>): JSONObject? {
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject(variables))
        }.toString()
        return trySuspend { FrRuntime.postJson(ENDPOINT, body) }.getOrNull()
    }

    fun item(media: JSONObject): CatalogItem? {
        val id = media.optInt("id").takeIf { it > 0 } ?: return null
        val titles = media.optJSONObject("title")
        val preferredKeys = when (FrSettings.catalogLanguage.substringBefore('-')) {
            "ja" -> listOf("native", "romaji", "english", "userPreferred")
            "en" -> listOf("english", "userPreferred", "romaji", "native")
            else -> listOf("english", "romaji", "userPreferred", "native")
        }
        val title = preferredKeys
            .firstNotNullOfOrNull { key ->
                titles?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
            } ?: return null
        val alternate = listOf("romaji", "english", "native")
            .firstNotNullOfOrNull { key ->
                titles?.optString(key)?.takeIf { it.isNotBlank() && it != "null" && !it.equals(title, true) }
            }
        return CatalogItem(
            id = CatalogId("anilist", "anime", id.toString()),
            title = title,
            originalTitle = alternate,
            year = media.optInt("seasonYear").takeIf { it > 0 }
                ?: media.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 },
            posterUrl = media.optJSONObject("coverImage")?.let {
                it.optString("extraLarge").ifBlank { it.optString("large") }
            }?.takeIf { it.isNotBlank() && it != "null" },
            backdropUrl = media.optString("bannerImage").takeIf { it.isNotBlank() && it != "null" },
            overview = media.optString("description").takeIf { it.isNotBlank() && it != "null" }
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&quot;", "\"")
                ?.replace("&#039;", "'"),
            rating10 = media.optInt("averageScore").takeIf { it > 0 }?.div(10.0),
            episodeCount = media.optInt("episodes").takeIf { it > 0 },
            format = media.optString("format").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    suspend fun search(term: String, page: Int = 1, perPage: Int = 25): List<CatalogItem> {
        val gql = """
            query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH, isAdult: false) { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()
        return mediaList(query(gql, mapOf("search" to term, "page" to page, "perPage" to perPage)))
    }

    suspend fun row(sort: String, page: Int = 1, perPage: Int = 25): List<CatalogItem> {
        val gql = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(type: ANIME, sort: $sort, isAdult: false) { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()
        return mediaList(query(gql, mapOf("page" to page, "perPage" to perPage)))
    }

    suspend fun details(id: String): JSONObject? {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                $MEDIA_FIELDS
                studios(isMain: true) { nodes { name } }
                characters(sort: ROLE, perPage: 12) {
                  edges { role voiceActors(language: FRENCH) { name { full } } node { name { full } } }
                }
                relations { edges { relationType node { id type format title { userPreferred } coverImage { large } } } }
              }
            }
        """.trimIndent()
        return query(gql, mapOf("id" to id.toIntOrNull()))
            ?.optJSONObject("data")
            ?.optJSONObject("Media")
    }

    fun allTitles(media: JSONObject): List<String> {
        val titles = media.optJSONObject("title")
        val base = listOf("userPreferred", "english", "romaji", "native").mapNotNull { key ->
            titles?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
        }
        val synonyms = media.optJSONArray("synonyms")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return (base + synonyms).distinct()
    }

    private fun mediaList(root: JSONObject?): List<CatalogItem> {
        val array = root?.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::item) }
    }
}

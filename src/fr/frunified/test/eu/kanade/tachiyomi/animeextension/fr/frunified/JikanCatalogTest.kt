package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JikanCatalogTest {
    @Test
    fun paginatedEpisodeCountUsesTheHighestEpisodeOnTheLastPage() = runBlocking {
        val requested = mutableListOf<String>()
        val count = JikanCatalog.episodeCount("21") { path ->
            requested += path
            when (path.substringAfter("page=")) {
                "1" -> JSONObject(
                    """{"pagination":{"last_visible_page":12},"data":[{"mal_id":1},{"mal_id":100}]}""",
                )

                "12" -> JSONObject("""{"data":[{"mal_id":1101},{"mal_id":1168}]}""")

                else -> null
            }
        }

        assertEquals(1168, count)
        assertEquals(
            listOf("anime/21/episodes?page=1", "anime/21/episodes?page=12"),
            requested,
        )
    }

    @Test
    fun failureOfTheLastPageNeverFallsBackToOneHundred() = runBlocking {
        val count = JikanCatalog.episodeCount("21") { path ->
            if (path.endsWith("page=1")) {
                JSONObject("""{"pagination":{"last_visible_page":12},"data":[{"mal_id":100}]}""")
            } else {
                null
            }
        }

        assertNull(count)
    }

    @Test
    fun implausibleCountsAreRejected() = runBlocking {
        val count = JikanCatalog.episodeCount("x") {
            JSONObject("""{"pagination":{"last_visible_page":1},"data":[{"mal_id":9001}]}""")
        }
        assertNull(count)
    }
}

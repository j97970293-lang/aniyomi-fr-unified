package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

class JikanCatalogNetworkTest {
    @Test
    fun onePieceIsNotLimitedToTwentyFourEpisodes() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")
        val count = JikanCatalog.episodeCount("21") { path ->
            runCatching {
                JSONObject(URI("https://api.jikan.moe/v4/$path").toURL().readText())
            }.getOrNull()
        }
        assertTrue("Unexpected One Piece count: $count", count != null && count >= 1168)
    }
}

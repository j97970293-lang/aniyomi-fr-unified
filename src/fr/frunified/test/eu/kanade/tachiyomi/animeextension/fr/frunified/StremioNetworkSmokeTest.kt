package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.URI

class StremioNetworkSmokeTest {
    private val addon = "https://nuvio-french-providers.onrender.com"

    @Test
    fun snixiAddonContractIsReachable() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")

        val manifest = readJson("$addon/manifest.json")
        val resources = manifest.getJSONArray("resources")
        assertTrue(
            "Snixi manifest no longer advertises streams",
            (0 until resources.length()).any { resources.optString(it) == "stream" },
        )
        assertTrue(
            "Snixi stream route no longer returns the Stremio contract",
            readJson("$addon/stream/series/tt0388629:1:1.json").optJSONArray("streams") != null,
        )
    }

    @Test
    fun snixiAddonReturnsPlayableStreamsWithHeaders() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")

        val cases = listOf(
            "series/tt0388629:1:1" to PlayPayload(
                kind = "tv",
                titles = listOf("One Piece"),
                year = 1999,
                season = 1,
                episode = 1,
                tmdbId = 37854,
                imdbId = "tt0388629",
            ),
            "series/tt0903747:1:1" to PlayPayload(
                kind = "tv",
                titles = listOf("Breaking Bad"),
                year = 2008,
                season = 1,
                episode = 1,
                imdbId = "tt0903747",
            ),
            "movie/tt1375666" to PlayPayload(
                kind = "movie",
                titles = listOf("Inception"),
                year = 2010,
                imdbId = "tt1375666",
            ),
        )
        val videos = cases.firstNotNullOfOrNull { (path, payload) ->
            StremioClient.parseStreams(readJson("$addon/stream/$path.json"), addon, payload)
                .takeIf(List<*>::isNotEmpty)
        }.orEmpty()

        println("Snixi Stremio videos=${videos.size}")
        assumeTrue(
            "Snixi is reachable but currently returns no stream for the three probes (transient upstream outage)",
            videos.isNotEmpty(),
        )
        assertTrue("Top-level Stremio headers were lost", videos.any { it.headers?.get("Referer") != null })
        assertTrue("Placeholder stream was not filtered", videos.none { "/troll/master.m3u8" in it.videoUrl })
    }

    @Test
    fun streamHosterIsExposedWhenNuvioIsDisabled() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_STREMIO to addon,
                    FrSettings.KEY_USE_STREMIO to true,
                    FrSettings.KEY_USE_NUVIO to false,
                ),
            ),
        )
        FrRuntime.initForTests { url -> URI(url).toURL().readText() }
        val payload = PlayPayload(
            kind = "tv",
            titles = listOf("One Piece"),
            year = 1999,
            season = 1,
            episode = 1,
            imdbId = "tt0388629",
        )

        val hosters = StremioClient.hosters(payload)
        assertTrue(
            "Snixi stream hoster disappeared with Nuvio disabled",
            hosters.any { "French Streaming Providers" in it.hosterName },
        )
    }

    private fun readJson(url: String): JSONObject = JSONObject(URI(url).toURL().readText())

    private fun preferences(values: Map<String, Any?>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> values
            "contains" -> values.containsKey(args?.get(0) as String)
            "getString" -> values[args?.get(0) as String] as? String ?: args[1] as? String
            "getBoolean" -> values[args?.get(0) as String] as? Boolean ?: args[1] as Boolean
            else -> defaultValue(method.returnType)
        }
    } as SharedPreferences

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        else -> null
    }
}

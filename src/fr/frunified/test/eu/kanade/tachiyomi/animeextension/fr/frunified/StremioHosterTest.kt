package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class StremioHosterTest {
    @Test
    fun streamAddonRemainsVisibleAndPlayableWhenNuvioIsDisabled() = runBlocking {
        val addon = "https://stream-addon.test"
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_STREMIO to addon,
                    FrSettings.KEY_USE_STREMIO to true,
                    FrSettings.KEY_USE_NUVIO to false,
                    FrSettings.KEY_USE_SUBS to false,
                ),
            ),
        )
        FrRuntime.initForTests { url ->
            when {
                url == "$addon/manifest.json" ->
                    """
                        {
                          "name":"Test Streams",
                          "types":["movie","series"],
                          "idPrefixes":["tt"],
                          "resources":["stream"]
                        }
                    """.trimIndent()

                url == "$addon/stream/series/tt0388629:1:1.json" ->
                    """
                        {
                          "streams":[{
                            "name":"Test VF",
                            "title":"VF 1080p",
                            "infoHash":"0123456789abcdef0123456789abcdef01234567"
                          }]
                        }
                    """.trimIndent()

                url.contains("/subtitles/") -> "{\"subtitles\":[]}"

                url.endsWith("/manifest.json") -> "{\"name\":\"unused\",\"resources\":[]}"

                else -> "{}"
            }
        }

        val payload = PlayPayload(
            kind = "tv",
            titles = listOf("One Piece"),
            year = 1999,
            season = 1,
            episode = 1,
            imdbId = "tt0388629",
            stremioType = "series",
            stremioId = "tt0388629:1:1",
        )
        val hoster = StremioClient.hosters(payload).first { it.hosterName.contains("Test Streams") }

        assertTrue(StremioClient.isLazyHoster(hoster))
        val videos = StremioClient.streams(hoster)
        assertEquals(1, videos.size)
        assertTrue(videos.single().videoUrl.startsWith("magnet:?"))
        assertTrue(videos.single().videoTitle.contains("VF"))
        assertTrue(videos.single().videoTitle.contains("1080"))
    }

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

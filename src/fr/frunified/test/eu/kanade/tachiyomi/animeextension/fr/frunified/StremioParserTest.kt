package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioParserTest {
    @Test
    fun topLevelHeadersQualityAndPlaceholderFilteringAreSupported() {
        val root = JSONObject(
            """
                {
                  "streams": [
                    {
                      "name": "Frenchstream",
                      "title": "[VF] UQLOAD",
                      "url": "https://cdn.example.net/video/master.m3u8",
                      "quality": "1080p",
                      "headers": {
                        "Referer": "https://uqload.example/",
                        "Origin": "https://uqload.example"
                      }
                    },
                    {
                      "name": "Fake",
                      "url": "https://s1.fsvid.example/troll/master.m3u8",
                      "quality": "720p"
                    }
                  ]
                }
            """.trimIndent(),
        )
        val videos = StremioClient.parseStreams(
            root,
            "https://addon.example",
            PlayPayload(kind = "movie", titles = listOf("Test"), year = 2026),
        )

        assertEquals(1, videos.size)
        assertEquals(1080, videos.single().resolution)
        assertEquals("https://uqload.example/", videos.single().headers?.get("Referer"))
        assertTrue(videos.single().videoTitle.contains("VF"))
        assertFalse(videos.single().videoUrl.contains("/troll/master.m3u8"))
    }
}

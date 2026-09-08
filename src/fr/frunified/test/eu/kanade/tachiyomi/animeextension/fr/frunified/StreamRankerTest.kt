package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class StreamRankerTest {
    private fun video(title: String, resolution: Int? = null) = Video(
        videoUrl = "https://cdn.example/${title.hashCode()}",
        videoTitle = title,
        resolution = resolution,
    )

    @Test
    fun defaultOrderPutsFrenchAudioBeforeQualityThenQualityWithinALanguage() {
        FrSettings.init(preferences(emptyMap()))
        val sorted = listOf(
            video("(VOSTFR) 1080p · anime-sama · Nuvio", 1080),
            video("(VF) 720p · flemmix · Nuvio", 720),
            video("(VF) 1080p · frenchstream · Nuvio", 1080),
            video("Test Streams · Stremio · lecteur inconnu"),
            video("(MULTI) 4K · addon · Stremio", 2160),
        ).sortedWith(StreamRanker.videoComparator()).map(Video::videoTitle)

        assertEquals(
            listOf(
                "(VF) 1080p · frenchstream · Nuvio",
                "(VF) 720p · flemmix · Nuvio",
                "(MULTI) 4K · addon · Stremio",
                "(VOSTFR) 1080p · anime-sama · Nuvio",
                "Test Streams · Stremio · lecteur inconnu",
            ),
            sorted,
        )
        assertTrue(StreamRanker.isPreferred("(VF) 1080p · frenchstream · Nuvio", 1080))
        assertFalse(StreamRanker.isPreferred("Test Streams · Stremio · lecteur inconnu", null))
        assertFalse(
            "le nom d'un addon n'est pas une langue",
            StreamRanker.isPreferred("French Streaming Providers · Stremio · Addon Lecteur Dood", null),
        )
    }

    @Test
    fun arrowOrderIsHonouredWhenQualityIsMovedAboveLanguages() {
        FrSettings.init(preferences(mapOf(FrSettings.KEY_STREAM_ORDER to "4K\n1080p\nVF\nVOSTFR")))
        assertEquals(listOf("4K", "1080p", "VF", "VOSTFR"), FrSettings.streamOrder)

        val sorted = listOf(
            video("(VF) 720p · flemmix · Nuvio", 720),
            video("(VOSTFR) 1080p · anime-sama · Nuvio", 1080),
            video("(VF) 1080p · frenchstream · Nuvio", 1080),
        ).sortedWith(StreamRanker.videoComparator()).map(Video::videoTitle)

        assertEquals(
            listOf(
                "(VF) 1080p · frenchstream · Nuvio",
                "(VOSTFR) 1080p · anime-sama · Nuvio",
                "(VF) 720p · flemmix · Nuvio",
            ),
            sorted,
        )
    }

    @Test
    fun legacyTitlesAreStillRankedFromTheirFreeText() {
        FrSettings.init(preferences(emptyMap()))
        val order = FrSettings.streamOrder
        assertEquals(
            listOf(order.indexOf("VF"), order.indexOf("1080p")),
            StreamRanker.ranks("Frenchstream • [VF] UQLOAD • 1080p", null, order),
        )
        assertEquals(listOf(order.size), StreamRanker.ranks("Türkçe", null, order))
    }

    @Test
    fun hostersFollowEngineOrderThenTheirBestStream() {
        FrSettings.init(preferences(emptyMap()))
        val nuvioVostfr = Hoster(
            hosterUrl = "frunified://1",
            hosterName = "Nuvio · anime-sama : VOSTFR",
            videoList = listOf(video("(VOSTFR) 1080p · anime-sama · Nuvio", 1080)),
        )
        val nuvioVf = Hoster(
            hosterUrl = "frunified://2",
            hosterName = "Nuvio · flemmix : VF, VOSTFR",
            videoList = listOf(
                video("(VOSTFR) 1080p · flemmix · Nuvio", 1080),
                video("(VF) 720p · flemmix · Nuvio", 720),
            ),
        )
        val stremioLazy =
            Hoster(hosterUrl = "https://addon.example/stream/movie/tt1.json", hosterName = "Stremio · Addon")

        val nuvioFirst = listOf(stremioLazy, nuvioVostfr, nuvioVf)
            .sortedWith(StreamRanker.hosterComparator(stremioFirst = false)).map(Hoster::hosterName)
        assertEquals(
            listOf("Nuvio · flemmix : VF, VOSTFR", "Nuvio · anime-sama : VOSTFR", "Stremio · Addon"),
            nuvioFirst,
        )

        val stremioFirst = listOf(nuvioVostfr, nuvioVf, stremioLazy)
            .sortedWith(StreamRanker.hosterComparator(stremioFirst = true)).map(Hoster::hosterName)
        assertEquals(
            listOf("Stremio · Addon", "Nuvio · flemmix : VF, VOSTFR", "Nuvio · anime-sama : VOSTFR"),
            stremioFirst,
        )
    }

    @Test
    fun settingsParseAndMigrateStreamOrder() {
        assertEquals(
            listOf("VF", "1080p", "4K", "VOSTFR"),
            FrSettings.parseStreamOrder("vf, 1080P ; 4k\nvostfr, inconnu, vf"),
        )
        assertNull(FrSettings.streamOrderFromLegacyPatterns(null))
        assertNull(FrSettings.streamOrderFromLegacyPatterns(FrSettings.DEFAULT_NUVIO_PRIORITY.joinToString(",")))
        assertNull(FrSettings.streamOrderFromLegacyPatterns("uqload,vidmoly"))
        val migrated = FrSettings.streamOrderFromLegacyPatterns("VOSTFR,1080,VF")
        assertEquals(listOf("VOSTFR", "1080p", "VF"), migrated?.take(3))
        assertEquals(FrSettings.DEFAULT_STREAM_ORDER.toSet(), migrated?.toSet())

        FrSettings.init(preferences(mapOf(FrSettings.KEY_STREAM_ORDER to "")))
        assertEquals(FrSettings.DEFAULT_STREAM_ORDER, FrSettings.streamOrder)
    }

    private fun preferences(values: Map<String, Any?>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getAll" -> values
            "contains" -> values.containsKey(args?.firstOrNull())
            "toString" -> "FR Unified stream ranker preferences"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    } as SharedPreferences
}

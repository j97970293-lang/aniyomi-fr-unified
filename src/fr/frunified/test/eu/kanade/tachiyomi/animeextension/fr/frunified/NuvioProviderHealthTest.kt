package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NuvioProviderHealthTest {
    @Test
    fun recommendedProvidersReturnAtLeastOneRealStream() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")

        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_NUVIO_REPOS to
                        "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/refs/heads/main/manifest.json",
                    FrSettings.KEY_NUVIO_CONCURRENCY to "2",
                    FrSettings.KEY_NUVIO_MAX to "4",
                    FrSettings.KEY_NUVIO_ENABLED to FrSettings.RECOMMENDED_NUVIO_IDS.joinToString("\n"),
                ),
            ),
        )
        val recommended = FrSettings.RECOMMENDED_NUVIO_IDS
        val results = recommended.map { id -> async { id to NuvioClient.testProvider(id) } }.awaitAll()
        results.forEach { (id, result) -> println("$id: $result") }

        assertTrue(
            "No recommended Nuvio provider returned a real stream: ${results.joinToString()}",
            results.any { (_, result) -> result.startsWith("✓") },
        )
    }

    @Test
    fun fastModeKeepsLookingForVfAndRejectsDeadLinks() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")

        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_NUVIO_REPOS to
                        "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/refs/heads/main/manifest.json",
                    FrSettings.KEY_NUVIO_ENABLED to FrSettings.RECOMMENDED_NUVIO_IDS.joinToString("\n"),
                    FrSettings.KEY_NUVIO_MAX to "8",
                ),
            ),
        )

        val onePiece = java.util.concurrent.CopyOnWriteArrayList<eu.kanade.tachiyomi.animesource.model.Video>()
        val animeOk = NuvioClient.streams(
            PlayPayload(
                kind = "anime",
                titles = listOf("One Piece"),
                year = 1999,
                season = 1,
                episode = 1,
                tmdbId = 37854,
                malId = 21,
                anilistId = 21,
            ),
        ) { onePiece += it }
        onePiece.forEach { println("One Piece: ${it.videoTitle} | ${it.resolution}p") }
        val vfPattern = Regex("(^|[^A-Z0-9])VF([^A-Z0-9]|$)")
        assertTrue("Fast mode returned no One Piece stream", animeOk && onePiece.isNotEmpty())
        assertTrue(
            "Fast mode stopped on VOSTFR although a VF exists: ${onePiece.map { it.videoTitle }}",
            onePiece.any { vfPattern.containsMatchIn(it.videoTitle.uppercase()) },
        )
        assertTrue("Quality is missing: ${onePiece.map { it.videoTitle }}", onePiece.all { it.resolution != null })

        val runner = java.util.concurrent.CopyOnWriteArrayList<eu.kanade.tachiyomi.animesource.model.Video>()
        val movieOk = NuvioClient.streams(
            PlayPayload(
                kind = "movie",
                titles = listOf("The Runner"),
                year = 2026,
                tmdbId = 1386315,
            ),
        ) { runner += it }
        runner.forEach { println("The Runner: ${it.videoTitle} | ${it.resolution}p") }
        assertTrue("No preflight-accepted Movix stream for The Runner", movieOk && runner.isNotEmpty())
        assertTrue("The Runner quality is missing", runner.all { it.resolution != null })
    }

    @Test
    fun allFrenchProvidersProduceAHealthReport() = runBlocking {
        assumeTrue(
            "Set FR_UNIFIED_FULL_NETWORK_TEST=1 to run",
            System.getenv("FR_UNIFIED_FULL_NETWORK_TEST") == "1",
        )

        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_NUVIO_REPOS to
                        "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/refs/heads/main/manifest.json",
                    FrSettings.KEY_NUVIO_ENABLED to FrSettings.GOWARU_NUVIO_IDS.joinToString("\n"),
                    FrSettings.KEY_NUVIO_MAX to "3",
                ),
            ),
        )
        val providers = NuvioClient.scrapers(includeDisabled = true)
        println("FULL providers discovered: ${providers.size}")
        val results = providers.map { scraper ->
            async { scraper.id to NuvioClient.testProvider(scraper.id) }
        }.awaitAll()
        results.forEach { (id, result) -> println("FULL $id: $result") }

        val healthy = results.filter { (_, result) -> result.startsWith("✓") }
        assertTrue(
            "Expected several live French providers, got ${healthy.size}: ${results.joinToString()}",
            healthy.size >= 3,
        )
    }

    private fun preferences(values: Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getAll" -> values
                "contains" -> values.containsKey(args?.firstOrNull())
                "toString" -> "FR Unified provider health preferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as SharedPreferences
}

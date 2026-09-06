package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class NuvioNetworkSmokeTest {
    @Test
    fun currentAnimeSamaBundleLoadsAndRuns() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")

        val script = URI(
            "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/" +
                "refs/heads/main/providers/anime-sama.js",
        ).toURL().readText()
        val manifest = """
            {
              "scrapers": [{
                "id": "anime-sama-smoke",
                "name": "Anime-Sama smoke test",
                "description": "Runtime compatibility test",
                "filename": "anime-sama.js",
                "supportedTypes": ["movie", "tv"],
                "contentLanguage": ["fr"],
                "enabled": true
              }]
            }
        """.trimIndent()

        LocalServer(manifest, script).use { server ->
            FrSettings.init(
                preferences(
                    mapOf(
                        FrSettings.KEY_NUVIO_REPOS to "http://127.0.0.1:${server.port}/manifest.json",
                        FrSettings.KEY_NUVIO_CONCURRENCY to "1",
                        FrSettings.KEY_NUVIO_MAX to "4",
                        FrSettings.KEY_NUVIO_ENABLED to "anime-sama-smoke",
                    ),
                ),
            )
            val videos = mutableListOf<Video>()
            NuvioClient.streams(
                PlayPayload(
                    kind = "tv",
                    titles = listOf("One Piece"),
                    year = 1999,
                    season = 1,
                    episode = 1,
                    absoluteEpisode = 1,
                    tmdbId = 37854,
                    anilistId = 21,
                    malId = 21,
                ),
                videos::add,
            )

            val diagnostic = NuvioClient.diagnostics()["anime-sama-smoke"].orEmpty()
            println("Anime-Sama diagnostic: $diagnostic; videos=${videos.size}")
            assertTrue("Bundle execution failed: $diagnostic", diagnostic.startsWith("✓"))
            assertTrue("Anime-Sama returned no playable links", videos.isNotEmpty())
        }
    }

    private fun preferences(values: Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getAll" -> values
                "contains" -> values.containsKey(args?.firstOrNull())
                "toString" -> "FR Unified test preferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as SharedPreferences

    private class LocalServer(
        private val manifest: String,
        private val script: String,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        private val worker = thread(isDaemon = true, name = "nuvio-smoke-http") {
            repeat(2) {
                val client = socket.accept()
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) {
                        // Consume HTTP headers.
                    }
                    val body = if (path == "/manifest.json") manifest else script
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    it.getOutputStream().buffered().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                        output.write("Connection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                }
            }
        }

        override fun close() {
            socket.close()
            worker.join(1_000)
        }
    }
}

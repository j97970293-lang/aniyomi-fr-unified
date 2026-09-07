package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class NuvioLanguageRuntimeTest {
    @Test
    fun nonFrenchProviderIsFilteredByLanguageAndRunsThroughRhino() = runBlocking {
        LanguageServer().use { server ->
            val repository = "http://127.0.0.1:${server.port}/manifest.json"
            FrSettings.init(preferences(repository, "fr"))
            assertTrue(
                "Turkish provider leaked into a French-only runtime selection",
                NuvioClient.scrapers().none { it.id == "turkish-runtime" },
            )

            FrSettings.init(preferences(repository, "tr"))
            val videos = mutableListOf<Video>()
            val ok = NuvioClient.streams(
                PlayPayload(
                    kind = "movie",
                    titles = listOf("International runtime test"),
                    year = 2026,
                    tmdbId = 550,
                ),
                videos::add,
            )

            assertTrue("Turkish provider was not executed", ok)
            assertTrue("Turkish Rhino provider returned no video", videos.isNotEmpty())
            assertTrue("Provider language label was lost", videos.any { "Türkçe" in it.videoTitle })
        }
    }

    private fun preferences(repository: String, language: String): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, args ->
            val values = mapOf(
                FrSettings.KEY_NUVIO_REPOS to repository,
                FrSettings.KEY_NUVIO_ENABLED to "all",
                FrSettings.KEY_NUVIO_LANGUAGES to language,
                FrSettings.KEY_NUVIO_CONCURRENCY to "2",
            )
            when (method.name) {
                "getAll" -> values
                "contains" -> values.containsKey(args?.firstOrNull())
                "toString" -> "FR Unified language runtime preferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        } as SharedPreferences

    private class LanguageServer : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        private val manifest = """
            {
              "scrapers": [{
                "id": "turkish-runtime",
                "name": "Turkish runtime",
                "filename": "turkish.js",
                "supportedTypes": ["movie", "tv"],
                "contentLanguage": ["tr"],
                "enabled": true
              }]
            }
        """.trimIndent()
        private val script = """
            module.exports.getStreams = function () {
              return [{
                title: "Türkçe 1080p",
                infoHash: "0123456789abcdef0123456789abcdef01234567"
              }];
            };
        """.trimIndent()

        private val worker = thread(isDaemon = true, name = "nuvio-language-http") {
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
                        output.write("Content-Type: application/javascript\r\n".toByteArray())
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

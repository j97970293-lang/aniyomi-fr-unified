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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class NuvioConcurrencyTest {
    @Test
    fun fastModeDownloadsAndRunsSeveralProvidersSimultaneously() = runBlocking {
        ConcurrentServer().use { server ->
            FrSettings.init(
                preferences(
                    mapOf(
                        FrSettings.KEY_NUVIO_REPOS to "http://127.0.0.1:${server.port}/manifest.json",
                        FrSettings.KEY_NUVIO_CONCURRENCY to "3",
                        FrSettings.KEY_NUVIO_SEARCH_MODE to "fast",
                        FrSettings.KEY_NUVIO_ENABLED to "all",
                        FrSettings.KEY_NUVIO_LANGUAGES to "fr",
                    ),
                ),
            )
            val videos = CopyOnWriteArrayList<Video>()

            val ok = NuvioClient.streams(
                PlayPayload(
                    kind = "movie",
                    titles = listOf("Concurrent test"),
                    year = 2026,
                    tmdbId = 550,
                ),
                videos::add,
            )

            assertTrue("No provider completed", ok)
            assertTrue("Providers were still executed sequentially", server.maxConcurrentScripts.get() >= 2)
            assertTrue("Concurrent providers did not all report their streams", videos.size >= 2)
        }
    }

    private fun preferences(values: Map<String, Any>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getAll" -> values
            "contains" -> values.containsKey(args?.firstOrNull())
            "toString" -> "FR Unified concurrency preferences"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    } as SharedPreferences

    private class ConcurrentServer : AutoCloseable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val activeScripts = AtomicInteger(0)
        val maxConcurrentScripts = AtomicInteger(0)

        @Volatile private var open = true

        private val manifest = """
            {
              "scrapers": [
                {"id":"parallel-a","name":"Parallel A","filename":"a.js","supportedTypes":["movie","tv"],"contentLanguage":["fr"],"enabled":true},
                {"id":"parallel-b","name":"Parallel B","filename":"b.js","supportedTypes":["movie","tv"],"contentLanguage":["fr"],"enabled":true},
                {"id":"parallel-c","name":"Parallel C","filename":"c.js","supportedTypes":["movie","tv"],"contentLanguage":["fr"],"enabled":true}
              ]
            }
        """.trimIndent()
        private val script = """
            module.exports.getStreams = function () {
              return [{
                title: "VF 1080p",
                infoHash: "0123456789abcdef0123456789abcdef01234567"
              }];
            };
        """.trimIndent()

        private val acceptor = thread(isDaemon = true, name = "nuvio-concurrency-acceptor") {
            while (open) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "nuvio-concurrency-request") {
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) {
                            // Consume HTTP headers.
                        }
                        val body = if (path == "/manifest.json") {
                            manifest
                        } else {
                            val current = activeScripts.incrementAndGet()
                            maxConcurrentScripts.updateAndGet { previous -> maxOf(previous, current) }
                            Thread.sleep(300)
                            activeScripts.decrementAndGet()
                            script
                        }
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
        }

        override fun close() {
            open = false
            socket.close()
            acceptor.join(1_000)
        }
    }
}

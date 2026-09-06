package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSourceImporterTest {
    @Test
    fun detectsNuvioAndEveryStremioResourceFamily() = runBlocking {
        val documents = mapOf(
            "https://example.test/nuvio.json" to """{"name":"Nuvio FR","scrapers":[]}""",
            "https://example.test/stream.json" to
                """{"name":"Streams","resources":[{"name":"stream","types":["movie"]}],"types":["movie"]}""",
            "https://example.test/catalog.json" to
                """{"name":"Catalog","resources":["catalog","meta"],"catalogs":[]}""",
        )
        val fetch: suspend (String) -> String = { documents.getValue(it) }

        assertEquals(
            ExternalSourceImporter.Kind.NUVIO,
            ExternalSourceImporter.inspect("https://example.test/nuvio.json", fetch).kind,
        )
        assertEquals(
            ExternalSourceImporter.Kind.STREMIO,
            ExternalSourceImporter.inspect("https://example.test/stream.json", fetch).kind,
        )
        assertEquals(
            ExternalSourceImporter.Kind.STREMIO,
            ExternalSourceImporter.inspect("https://example.test/catalog.json", fetch).kind,
        )
    }

    @Test
    fun githubRepositoryResolvesToNuvioManifest() = runBlocking {
        val manifest = "https://raw.githubusercontent.com/example/repository/HEAD/manifest.json"
        val result = ExternalSourceImporter.inspect("https://github.com/example/repository") {
            if (it == manifest) """{"name":"Nuvio","scrapers":[]}""" else error("404")
        }

        assertEquals(ExternalSourceImporter.Kind.NUVIO, result.kind)
        assertEquals(manifest, result.url)
    }

    @Test
    fun rejectsCloudStreamInputs() = runBlocking {
        listOf("https://example.test/plugin.cs3", "https://example.test/repo.json").forEach { input ->
            val failure = runCatching {
                ExternalSourceImporter.inspect(input) { error("must not fetch") }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("CloudStream"))
        }
    }
}

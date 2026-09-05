package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSourceImporterTest {
    @Test
    fun detectsEachSupportedFamily() = runBlocking {
        val documents = mapOf(
            "https://example.test/nuvio.json" to """{"name":"Nuvio FR","scrapers":[]}""",
            "https://example.test/stremio.json" to
                """{"name":"Stremio FR","resources":[{"name":"stream","types":["movie"]}],"types":["movie"]}""",
            "https://example.test/repo.json" to
                """[{"internalName":"Movix"},{"internalName":"FrenchStream"},{"internalName":"Unknown"}]""",
        )
        val fetch: suspend (String) -> String = { documents.getValue(it) }

        assertEquals(
            ExternalSourceImporter.Kind.NUVIO,
            ExternalSourceImporter.inspect("https://example.test/nuvio.json", fetch).kind,
        )
        assertEquals(
            ExternalSourceImporter.Kind.STREMIO,
            ExternalSourceImporter.inspect("https://example.test/stremio.json", fetch).kind,
        )
        val cloudStream = ExternalSourceImporter.inspect("https://example.test/repo.json", fetch)
        assertEquals(ExternalSourceImporter.Kind.CLOUDSTREAM, cloudStream.kind)
        assertEquals(setOf("movix", "frenchstream"), cloudStream.nuvioIds)
        assertEquals(3, cloudStream.pluginNames.size)
    }

    @Test
    fun followsCloudStreamPluginListsFromGitHubRepositoryUrl() = runBlocking {
        val root = "https://raw.githubusercontent.com/example/repository/HEAD/repo.json"
        val list = "https://example.test/plugins.json"
        val documents = mapOf(
            root to """{"name":"CloudStream FR","pluginLists":["$list"]}""",
            list to """[{"name":"Wiflix"},{"name":"Coflix"}]""",
        )
        val result = ExternalSourceImporter.inspect("https://github.com/example/repository") {
            documents[it] ?: error("404")
        }

        assertEquals(ExternalSourceImporter.Kind.CLOUDSTREAM, result.kind)
        assertEquals(root, result.url)
        assertEquals(setOf("flemmix", "coflix"), result.nuvioIds)
    }

    @Test
    fun rejectsCloudStreamBinary() = runBlocking {
        val failure = runCatching {
            ExternalSourceImporter.inspect("https://example.test/plugin.cs3") { error("must not fetch") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("repo.json"))
    }
}

package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

class ExternalSourceImporterNetworkTest {
    @Test
    fun detectsNuvioStremioAndCloudStreamRepositories() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")
        val fetch: suspend (String) -> String = { URI(it).toURL().readText() }

        val nuvio = ExternalSourceImporter.inspect(
            "https://github.com/Gowaru/gowaru-nuvio-providers",
            fetch,
        )
        assertEquals(ExternalSourceImporter.Kind.NUVIO, nuvio.kind)

        val stremio = ExternalSourceImporter.inspect(
            "https://github.com/Snixi92/nuvio-french-providers",
            fetch,
        )
        assertEquals(ExternalSourceImporter.Kind.STREMIO, stremio.kind)

        val cloudStreamRepos = listOf(
            "https://github.com/mouradchaouche/cloudstream-frenchrepo",
            "https://github.com/Nikola17/cloudstream-frenchstream",
            "https://github.com/blizzx4644/Movix-cloudstream",
            "https://github.com/Kraptor123/Cs-Karma",
        )
        cloudStreamRepos.forEach { url ->
            val result = ExternalSourceImporter.inspect(url, fetch)
            println("${result.name}: plugins=${result.pluginNames.size}, bridges=${result.nuvioIds.size}")
            assertEquals(ExternalSourceImporter.Kind.CLOUDSTREAM, result.kind)
            assertFalse("No compatible bridge found for $url", result.nuvioIds.isEmpty())
        }
    }
}

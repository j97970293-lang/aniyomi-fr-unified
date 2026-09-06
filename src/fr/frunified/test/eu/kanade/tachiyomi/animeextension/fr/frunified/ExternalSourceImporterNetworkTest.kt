package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI

class ExternalSourceImporterNetworkTest {
    @Test
    fun detectsCurrentNuvioAndStremioRepositories() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")
        val fetch: suspend (String) -> String = { URI(it).toURL().readText() }

        val nuvio = ExternalSourceImporter.inspect(
            "https://github.com/Gowaru/gowaru-nuvio-providers",
            fetch,
        )
        assertEquals(ExternalSourceImporter.Kind.NUVIO, nuvio.kind)

        val streamAddon = ExternalSourceImporter.inspect(
            "https://github.com/Snixi92/nuvio-french-providers",
            fetch,
        )
        assertEquals(ExternalSourceImporter.Kind.STREMIO, streamAddon.kind)

        val catalogAddon = ExternalSourceImporter.inspect(
            "https://tmdb.elfhosted.com/fr-FR/manifest.json",
            fetch,
        )
        assertEquals(ExternalSourceImporter.Kind.STREMIO, catalogAddon.kind)
    }
}

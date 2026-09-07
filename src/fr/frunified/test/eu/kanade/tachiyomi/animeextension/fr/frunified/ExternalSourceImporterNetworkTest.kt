package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        val optionalAllInOne =
            "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json"
        val imported = ExternalSourceImporter.inspect(optionalAllInOne, fetch)
        assertEquals(ExternalSourceImporter.Kind.NUVIO, imported.kind)
        val providers = NuvioClient.parseManifest(optionalAllInOne, JSONObject(fetch(optionalAllInOne)))
        val pickerEntries = NuvioClient.selectableScrapers(providers, includeDisabled = true)
        assertTrue("All-in-One providers are not visible to the picker", pickerEntries.size >= 50)
    }
}

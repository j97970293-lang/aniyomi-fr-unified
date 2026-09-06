package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioCatalogTest {
    @Test
    fun manifestResourcesRespectTheirOwnTypesAndIdPrefixes() {
        val addon = StremioCatalog.parseManifest(
            JSONObject(
                """
                    {
                      "name": "Mixed addon",
                      "types": ["movie", "series"],
                      "idPrefixes": ["tmdb:"],
                      "resources": [
                        "catalog",
                        "meta",
                        {"name":"stream","types":["series"],"idPrefixes":["tt"]}
                      ],
                      "catalogs": [
                        {"type":"movie","id":"top","name":"Top"},
                        {"type":"series","id":"search","extra":[{"name":"search","isRequired":true}]}
                      ]
                    }
                """.trimIndent(),
            ),
            "https://addon.example/manifest.json",
        )

        assertEquals("https://addon.example", addon.base)
        assertTrue(addon.supports("meta", "movie", "tmdb:550"))
        assertFalse(addon.supports("meta", "movie", "tt0137523"))
        assertTrue(addon.supports("stream", "series", "tt0388629:1:1"))
        assertFalse(addon.supports("stream", "movie", "tt0137523"))
        assertFalse(addon.supports("stream", "series", "tmdb:37854:1:1"))
        assertEquals(20, addon.catalogs.first().pageSize)
        assertTrue(addon.catalogs.last().supportsSearch)
    }

    @Test
    fun catalogReferenceRoundTripsConfiguredAddonPaths() {
        val expected = StremioCatalog.Ref(
            addonBase = "https://tmdb.elfhosted.com/fr-FR",
            type = "series",
            id = "tmdb:37854",
        )

        assertEquals(expected, StremioCatalog.Ref.parse(expected.serialize()))
    }
}

package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.URI

class StremioCatalogNetworkTest {
    @Test
    fun localizedCatalogSearchMetadataAndEpisodesWorkEndToEnd() = runBlocking {
        assumeTrue("Set FR_UNIFIED_NETWORK_TEST=1 to run", System.getenv("FR_UNIFIED_NETWORK_TEST") == "1")
        val base = "https://tmdb.elfhosted.com/fr-FR"
        val selected = StremioCatalog.Ref(base, "series", "tmdb.top").serialize()
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_STREMIO to base,
                    FrSettings.KEY_STREMIO_CATALOG to selected,
                    FrSettings.KEY_CATALOG_LANGUAGE to "fr-FR",
                    FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE to "fr-FR",
                ),
            ),
        )
        FrRuntime.initForTests { url -> URI(url).toURL().readText() }

        val catalogs = StremioCatalog.catalogs().filter { it.addonBase == base }
        assertTrue("All catalogs[] entries are not exposed separately", catalogs.size >= 12)
        assertEquals(catalogs.size, catalogs.distinctBy { it.key }.size)
        assertEquals("series", StremioCatalog.selectedCatalog()?.type)

        val popular = StremioCatalog.browse(1)
        assertTrue("Empty localized Stremio catalog", popular.isNotEmpty())

        val byYear = catalogs.first { it.type == "series" && it.id == "tmdb.year" }
        val filtered = StremioCatalog.browse(
            page = 1,
            catalogKey = byYear.key,
            selectedExtras = mapOf("genre" to "2026"),
        )
        assertTrue("Selected catalog extra returned no 2026 series", filtered.isNotEmpty())

        val results = StremioCatalog.browse(1, "One Piece")
        assertTrue("One Piece missing from Stremio search", results.any { it.title.contains("One Piece", true) })

        val ref = StremioCatalog.Ref(base, "series", "tmdb:37854")
        val meta = StremioCatalog.meta(ref)
        assertEquals("One Piece", meta?.optString("name"))
        assertTrue("Stremio metadata still looks truncated", StremioCatalog.videos(meta!!).size > 24)
    }

    private fun preferences(values: Map<String, Any?>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> values
            "contains" -> values.containsKey(args?.get(0) as String)
            "getString" -> values[args?.get(0) as String] as? String ?: args[1] as? String
            "getBoolean" -> values[args?.get(0) as String] as? Boolean ?: args[1] as Boolean
            else -> defaultValue(method.returnType)
        }
    } as SharedPreferences

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        else -> null
    }
}

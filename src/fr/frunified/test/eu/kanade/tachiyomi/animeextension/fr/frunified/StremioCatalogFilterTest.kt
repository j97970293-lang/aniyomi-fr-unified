package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class StremioCatalogFilterTest {
    @Test
    fun exactCatalogAndItsSelectedExtraAreAppliedToTheRequest() = runBlocking {
        val addon = "https://catalog-filter.test"
        val selected = StremioCatalog.Ref(addon, "movie", "by-year").serialize()
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_STREMIO to addon,
                    FrSettings.KEY_STREMIO_CATALOG to selected,
                    FrSettings.KEY_USE_STREMIO_CATALOG to true,
                ),
            ),
        )
        val requests = CopyOnWriteArrayList<String>()
        FrRuntime.initForTests { url ->
            requests += url
            when {
                url == "$addon/manifest.json" ->
                    """
                        {
                          "name":"Catalog Filter",
                          "resources":["catalog","meta"],
                          "types":["movie"],
                          "catalogs":[{
                            "type":"movie",
                            "id":"by-year",
                            "name":"Year",
                            "extra":[{"name":"genre","isRequired":false,"options":["2025","2026"]}]
                          }]
                        }
                    """.trimIndent()

                url.contains("/catalog/movie/by-year/genre=2026.json") ->
                    """
                        {"metas":[{"id":"tt1375666","type":"movie","name":"Inception","releaseInfo":"2010"}]}
                    """.trimIndent()

                url.endsWith("/manifest.json") -> "{\"name\":\"unused\",\"resources\":[]}"

                else -> "{\"metas\":[]}"
            }
        }

        val items = StremioCatalog.browse(
            page = 1,
            catalogKey = selected,
            selectedExtras = mapOf("genre" to "2026"),
        )

        assertEquals(listOf("Inception"), items.map { it.title })
        assertTrue(requests.any { it == "$addon/catalog/movie/by-year/genre=2026.json" })
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

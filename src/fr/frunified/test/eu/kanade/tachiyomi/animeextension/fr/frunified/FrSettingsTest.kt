package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FrSettingsTest {
    @Test
    fun freshInstallEnablesEveryProviderIncludingMovix() {
        FrSettings.init(preferences(emptyMap()))

        assertTrue(FrSettings.isNuvioEnabled("frenchstream"))
        assertTrue(FrSettings.isNuvioEnabled("new-provider-from-a-manifest"))
        // Par défaut « all » : TOUS les sites partent, movix compris (16.17).
        // Les 403 de ce site sont en amont (CDN) : on ne le coupe plus dans notre code.
        assertTrue(FrSettings.isNuvioEnabled("movix"))
    }

    @Test
    fun explicitAndWildcardSelectionsRemainIndividuallyControllable() {
        FrSettings.init(
            preferences(
                mapOf(FrSettings.KEY_NUVIO_ENABLED to "all\n!movix\n!anime-sama"),
            ),
        )
        assertFalse(FrSettings.isNuvioEnabled("MOVIX"))
        assertFalse(FrSettings.isNuvioEnabled("anime-sama"))
        assertTrue(FrSettings.isNuvioEnabled("coflix"))

        FrSettings.init(preferences(mapOf(FrSettings.KEY_NUVIO_ENABLED to "frenchstream\nvostfree")))
        assertTrue(FrSettings.isNuvioEnabled("frenchstream"))
        assertFalse(FrSettings.isNuvioEnabled("coflix"))
    }

    @Test
    fun migrationPreservesCustomChoicesAndReplacesOldRestrictiveDefault() {
        assertEquals(
            FrSettings.DEFAULT_NUVIO_ENABLED,
            FrSettings.migratedNuvioEnabled(null, FrSettings.DEFAULT_NUVIO_DISABLED.joinToString("\n")),
        )

        val custom = FrSettings.migratedNuvioEnabled(
            existingEnabled = null,
            oldDisabledRaw = "anime-sama\nmovix",
        ).lines()
        assertFalse("anime-sama" in custom)
        assertFalse("movix" in custom)
        assertTrue("frenchstream" in custom)

        assertEquals(
            "only-my-provider",
            FrSettings.migratedNuvioEnabled("only-my-provider", "frenchstream"),
        )
    }

    @Test
    fun multipleCatalogLanguagesHaveAnExplicitPrimaryAndLocalizedAddons() {
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_CATALOG_LANGUAGE to "fr-FR\ntr-TR",
                    FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE to "tr-TR",
                ),
            ),
        )

        assertEquals(listOf("tr-TR", "fr-FR"), FrSettings.catalogLanguages)
        assertEquals("tr-TR", FrSettings.catalogLanguage)
        assertTrue(FrSettings.stremioUrls.contains("https://tmdb.elfhosted.com/tr-TR"))
        assertTrue(FrSettings.stremioUrls.contains("https://tmdb.elfhosted.com/fr-FR"))
    }

    @Test
    fun catalogsCanBeDisabledIndependentlyAndAnimeIsDeduced() {
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_USE_TMDB to false,
                    FrSettings.KEY_USE_ANIME to false,
                    FrSettings.KEY_USE_JIKAN to true,
                ),
            ),
        )

        assertFalse(FrSettings.useTmdbCatalog)
        assertFalse(FrSettings.useAniListCatalog)
        assertTrue(FrSettings.useJikanCatalog)
        assertTrue(FrSettings.useAnimeCatalog)
        assertFalse(FrSettings.useMainCatalogs)

        FrSettings.init(preferences(mapOf(FrSettings.KEY_USE_TMDB to false)))
        assertTrue(FrSettings.useMainCatalogs)
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
            "getInt" -> values[args?.get(0) as String] as? Int ?: args[1] as Int
            "getLong" -> values[args?.get(0) as String] as? Long ?: args[1] as Long
            "getFloat" -> values[args?.get(0) as String] as? Float ?: args[1] as Float
            "getStringSet" -> args?.get(1)
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

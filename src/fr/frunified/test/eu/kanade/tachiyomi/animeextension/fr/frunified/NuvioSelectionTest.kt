package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NuvioSelectionTest {
    @Test
    fun pluginPickerShowsProvidersFromAddedRepositoriesBeforeLanguageFiltering() {
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_NUVIO_LANGUAGES to "fr",
                    FrSettings.KEY_NUVIO_ENABLED to "all",
                ),
            ),
        )
        val englishProvider = NuvioClient.NuvioScraper(
            id = "castle",
            name = "Castle",
            filename = "providers/castle.js",
            repoBase = "https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main",
            supportedTypes = listOf("movie", "tv"),
            contentLanguage = listOf("en"),
            description = "Added repository provider",
        )
        val builtInProvider = englishProvider.copy(
            name = "Older Castle",
            repoBase = "https://default.example/repository",
        )
        val manifestDisabledProvider = englishProvider.copy(
            id = "manual-provider",
            name = "Manual provider",
            manifestEnabled = false,
        )
        val unknownTypeProvider = englishProvider.copy(
            id = "unknown-type-provider",
            name = "Unknown type provider",
            supportedTypes = listOf("channel"),
        )

        val picker = NuvioClient.selectableScrapers(
            listOf(builtInProvider, englishProvider, manifestDisabledProvider, unknownTypeProvider),
            includeDisabled = true,
        )
        assertEquals(
            setOf("castle", "manual-provider", "unknown-type-provider"),
            picker.map { it.id }.toSet(),
        )
        assertEquals(englishProvider.repoBase, picker.first { it.id == "castle" }.repoBase)

        val runtime = NuvioClient.selectableScrapers(
            listOf(englishProvider, manifestDisabledProvider),
            includeDisabled = false,
        )
        // La langue ne bloque plus l'exécution : un provider anglais reste
        // sélectionnable même lorsque les langues cochées sont « fr ».
        assertTrue("Language filter still blocks a site from running", runtime.any { it.id == "castle" })
        assertTrue(runtime.none { it.id == "manual-provider" })
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

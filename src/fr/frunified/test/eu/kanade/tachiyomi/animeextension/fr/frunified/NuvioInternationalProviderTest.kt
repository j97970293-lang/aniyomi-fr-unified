package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NuvioInternationalProviderTest {
    @Test
    fun selectedInternationalProvidersExecuteThroughKotlinAndRhino() = runBlocking {
        assumeTrue(
            "Set FR_UNIFIED_INTERNATIONAL_NUVIO_IDS to run",
            !System.getenv("FR_UNIFIED_INTERNATIONAL_NUVIO_IDS").isNullOrBlank(),
        )
        val repository = System.getenv("FR_UNIFIED_INTERNATIONAL_NUVIO_REPO")
            ?.takeIf(String::isNotBlank)
            ?: "https://raw.githubusercontent.com/fmustafayaman/turkish-nuvio/main/manifest.json"
        val ids = System.getenv("FR_UNIFIED_INTERNATIONAL_NUVIO_IDS").orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank)
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_NUVIO_REPOS to repository,
                    FrSettings.KEY_NUVIO_ENABLED to ids.joinToString("\n"),
                    FrSettings.KEY_NUVIO_MAX to "3",
                ),
            ),
        )

        ids.forEach { id ->
            val result = NuvioClient.testProvider(id)
            println("$id: $result")
            assertTrue("$id failed: $result", result.startsWith("✓"))
        }
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

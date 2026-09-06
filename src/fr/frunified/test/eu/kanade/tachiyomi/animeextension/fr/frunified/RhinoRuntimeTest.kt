package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoRuntimeTest {
    @Test
    fun patchedRhinoProvidesRegexMessagesAndGenerators() {
        val status = NuvioClient.engineStatus()
        assertTrue(status, status.startsWith("✓"))
    }

    @Test
    fun internationalForOfBundlesAreLoweredForAndroidRhino() {
        val source = """
            var text = 'for (const fake of text)';
            // for (const ignored of comment) {}
            const matcher = /["']const["']/;
            for (const value of [1, 2]) output.push(value);
            for (const [key, item] of Object.entries(data)) { output.push(key + item); }
            { const language = 'fr'; }
            { const language = 'en'; }
        """.trimIndent()
        val compatible = NuvioClient.transpileForOf(source)

        assertTrue(compatible.contains("'for (const fake of text)'"))
        assertTrue(compatible.contains("// for (const ignored of comment)"))
        assertTrue(compatible.contains("/[\"']const[\"']/"))
        assertFalse(compatible.contains("const matcher"))
        assertFalse(compatible.contains("const language"))
        assertFalse(compatible.contains("for (const value of"))
        assertFalse(compatible.contains("for (const [key, item] of"))
        assertTrue(compatible.contains("__frToArray"))
    }
}

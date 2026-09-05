package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoRuntimeTest {
    @Test
    fun patchedRhinoProvidesRegexMessagesAndGenerators() {
        val status = NuvioClient.engineStatus()
        assertTrue(status, status.startsWith("✓"))
    }
}

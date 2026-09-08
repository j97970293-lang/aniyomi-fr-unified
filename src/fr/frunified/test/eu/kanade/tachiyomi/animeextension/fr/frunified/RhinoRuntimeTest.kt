package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.junit.Assert.assertEquals
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

    @Test
    fun modernJsPolyfillsAreAvailableInRuntime() {
        val testScript = """
            var out = {};
            var td = new TextDecoder();
            out.decoded = td.decode([72, 101, 108, 108, 111]);
            var entries = [['a', 1], ['b', 2]];
            var obj = Object.fromEntries(entries);
            out.objA = obj.a;
            out.objB = obj.b;
            out.replaced = 'foo-bar-foo'.replaceAll('foo', 'baz');
            out.hasUuid = typeof crypto.randomUUID === 'function' && crypto.randomUUID().length === 36;
            out;
        """.trimIndent()

        val cx = com.frunified.rhino.Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = com.frunified.rhino.Context.VERSION_ES6
            val scope = cx.initStandardObjects(com.frunified.rhino.TopLevel())
            cx.evaluateString(scope, NuvioClient.JS_ENV, "prelude", 1, null)
            val res = cx.evaluateString(scope, testScript, "polyfill-test", 1, null)
            assertTrue(res is com.frunified.rhino.Scriptable)
            val scriptable = res as com.frunified.rhino.Scriptable
            val decoded = com.frunified.rhino.ScriptableObject.getProperty(scriptable, "decoded")?.toString()
            val objA = com.frunified.rhino.ScriptableObject.getProperty(scriptable, "objA")
            val replaced = com.frunified.rhino.ScriptableObject.getProperty(scriptable, "replaced")?.toString()
            val hasUuid = com.frunified.rhino.ScriptableObject.getProperty(scriptable, "hasUuid")
            assertEquals("Hello", decoded)
            assertEquals(1.0, (objA as Number).toDouble(), 0.001)
            assertEquals("baz-bar-baz", replaced)
            assertEquals(java.lang.Boolean.TRUE, hasUuid)
        } finally {
            com.frunified.rhino.Context.exit()
        }
    }
}

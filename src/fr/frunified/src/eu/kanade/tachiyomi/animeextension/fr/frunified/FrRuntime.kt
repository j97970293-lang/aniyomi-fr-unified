package eu.kanade.tachiyomi.animeextension.fr.frunified

import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Équivalent suspend de `runCatching` : permet d'entourer un appel suspend par un
 * [Result] sans lambda non-suspend intermédiaire (interdit par la compilation Kotlin).
 */
internal suspend fun <T> trySuspend(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * Accès réseau commun aux catalogues.
 *
 * Le client OkHttp reçoit un DNS personnalisé ([FrDns]) dès l'initialisation afin
 * que toutes les requêtes de l'extension contournent le DNS défaillant de l'appareil.
 */
object FrRuntime {

    data class RawResult(
        val status: Int,
        val body: String,
        val contentType: String?,
    )

    private const val RAW_BODY_LIMIT = 12 * 1024 * 1024

    @Volatile
    private var httpClient: OkHttpClient? = null

    @Volatile
    private var probeClient: OkHttpClient? = null

    @Volatile
    private var testTextLoader: (suspend (String) -> String)? = null

    fun init(client: OkHttpClient) {
        val dns = FrDns
        httpClient = client.newBuilder().dns(dns).build()
        probeClient = httpClient?.newBuilder()
            ?.connectTimeout(6, TimeUnit.SECONDS)
            ?.readTimeout(6, TimeUnit.SECONDS)
            ?.callTimeout(12, TimeUnit.SECONDS)
            ?.build()
        testTextLoader = null
    }

    internal fun initForTests(loader: suspend (String) -> String) {
        testTextLoader = loader
    }

    /** Vrai quand un client OkHttp (avec DNS personnalisé) est disponible. */
    internal val isHttpReady: Boolean get() = httpClient != null

    private fun client(): OkHttpClient = checkNotNull(httpClient) { "FR Unifié n'est pas initialisé" }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        testTextLoader?.let { return it(url) }
        val requestHeaders = Headers.Builder().apply {
            add("Accept", "application/json, text/plain, */*")
            add("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.7")
            add("User-Agent", FrSettings.DEFAULT_USER_AGENT)
            headers.forEach { (name, value) -> set(name, value) }
        }.build()
        val request = Request.Builder().url(url).headers(requestHeaders).get().build()
        return client().newCall(request).awaitSuccess().use { response -> response.body.string() }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(getText(url, headers))

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val requestHeaders = Headers.Builder().apply {
            add("Accept", "application/json")
            add("Content-Type", "application/json")
            add("User-Agent", FrSettings.DEFAULT_USER_AGENT)
            headers.forEach { (name, value) -> set(name, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .headers(requestHeaders)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client().newCall(request).awaitSuccess().use { response ->
            JSONObject(response.body.string())
        }
    }

    /**
     * Requête HTTP bloquante (appelée depuis les moteurs Rhino et les sondes).
     * Retourne `null` lorsque le client OkHttp n'est pas disponible (tests JVM) :
     * l'appelant peut alors retomber sur une connexion Java directe.
     */
    internal fun rawExecute(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        maxBytes: Int = RAW_BODY_LIMIT,
        probe: Boolean = false,
    ): RawResult? {
        val client = (if (probe) probeClient else httpClient) ?: return null
        return runCatching {
            val builder = Request.Builder().url(url)
            val headerBuilder = Headers.Builder().apply {
                add("Accept", "*/*")
                add("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
                add("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> runCatching { set(name, value) } }
            }
            val request = when {
                body != null && !method.equals("GET", true) && !method.equals("HEAD", true) -> {
                    if (headers.none { it.key.equals("Content-Type", true) }) {
                        headerBuilder.set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    }
                    builder.headers(headerBuilder.build())
                        .method(method.uppercase(), body.toRequestBody(null))
                        .build()
                }

                else -> builder.headers(headerBuilder.build()).method(method.uppercase(), null).build()
            }
            val response = client.newCall(request).execute()
            response.use {
                val status = it.code
                val contentType = runCatching { it.header("Content-Type") }.getOrNull()
                val bodyText = if (probe) {
                    readLimited(it.body?.byteStream(), maxBytes.coerceAtLeast(0))
                } else {
                    runCatching { it.body?.string() }.getOrNull().orEmpty()
                }
                RawResult(status, bodyText, contentType)
            }
        }.getOrNull()
    }

    /** Lit au plus [maxBytes] octets puis ferme le flux (les corps volumineux ne sont pas lus en entier). */
    private fun readLimited(stream: InputStream?, maxBytes: Int): String {
        if (stream == null) return ""
        return try {
            val buffer = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val chunk = ByteArray(4096)
            var total = 0
            while (total < maxBytes) {
                val read = stream.read(chunk, 0, minOf(chunk.size, maxBytes - total))
                if (read < 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            // ISO-8859-1 : conversion 1:1 des octets, sans risque de coupure UTF-8.
            String(buffer.toByteArray(), Charsets.ISO_8859_1)
        } catch (_: Throwable) {
            ""
        } finally {
            runCatching { stream.close() }
        }
    }
}

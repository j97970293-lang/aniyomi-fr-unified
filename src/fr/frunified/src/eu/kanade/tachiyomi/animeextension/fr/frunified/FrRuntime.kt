package eu.kanade.tachiyomi.animeextension.fr.frunified

import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Accès réseau commun aux catalogues. */
object FrRuntime {
    @Volatile
    private var httpClient: OkHttpClient? = null

    fun init(client: OkHttpClient) {
        httpClient = client
    }

    private fun client(): OkHttpClient = checkNotNull(httpClient) { "FR Unifié n'est pas initialisé" }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
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
}

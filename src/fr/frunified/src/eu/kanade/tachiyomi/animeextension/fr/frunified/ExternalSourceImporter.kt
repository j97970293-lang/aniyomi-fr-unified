package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.json.JSONArray
import org.json.JSONObject

/** Detects external manifests and bridges compatible CloudStream plugins to Nuvio engines. */
object ExternalSourceImporter {
    enum class Kind { NUVIO, STREMIO, CLOUDSTREAM }

    private val cloudStreamBridgeIds = setOf(
        "voiranime-homes",
        "animesama-co",
        "anime-sama",
        "anime-ultime",
        "animesultra",
        "animevostfr",
        "animoflix",
        "french-manga",
        "frenchstream",
        "flemmix",
        "papadustream",
        "waveanime",
        "voiranime",
        "neko-sama",
        "coflix",
        "movix",
    )

    fun isCloudStreamBridge(id: String): Boolean = id in cloudStreamBridgeIds

    data class ImportResult(
        val kind: Kind,
        val url: String,
        val name: String,
        val pluginNames: List<String> = emptyList(),
        val nuvioIds: Set<String> = emptySet(),
    ) {
        val summary: String
            get() = when (kind) {
                Kind.NUVIO -> "Dépôt Nuvio détecté : $name"

                Kind.STREMIO -> "Addon Stremio détecté : $name"

                Kind.CLOUDSTREAM -> {
                    val unsupported = (pluginNames.size - nuvioIds.size).coerceAtLeast(0)
                    "Dépôt CloudStream : ${pluginNames.size} extension(s), " +
                        "${nuvioIds.size} reliée(s) à Nuvio, $unsupported à adapter."
                }
            }
    }

    suspend fun inspect(
        input: String,
        fetch: suspend (String) -> String = { FrRuntime.getText(it) },
    ): ImportResult {
        val raw = input.trim().removePrefix("cloudstreamrepo://")
        require(raw.isNotBlank()) { "URL vide" }
        require(!raw.endsWith(".cs3", true)) {
            "Un APK .cs3 ne peut pas être chargé dans Aniyomi. Ajoutez l'URL repo.json du dépôt."
        }

        var lastFailure: Throwable? = null
        for (candidate in candidates(raw)) {
            val text = try {
                fetch(candidate)
            } catch (failure: Throwable) {
                lastFailure = failure
                continue
            }
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                val plugins = pluginNames(JSONArray(trimmed))
                return cloudstreamResult(candidate, "Dépôt CloudStream", plugins)
            }
            val json = runCatching { JSONObject(trimmed) }.getOrElse {
                lastFailure = it
                continue
            }
            if (json.optJSONArray("scrapers") != null) {
                return ImportResult(
                    Kind.NUVIO,
                    candidate,
                    json.optString("name").ifBlank { candidate.substringAfter("://").substringBefore('/') },
                )
            }
            if (json.optJSONArray("pluginLists") != null) {
                val plugins = buildList {
                    val lists = json.optJSONArray("pluginLists")
                    if (lists != null) {
                        for (index in 0 until lists.length()) {
                            val url = lists.optString(index)
                            if (url.isBlank()) continue
                            val array = runCatching { JSONArray(fetch(url)) }.getOrNull() ?: continue
                            addAll(pluginNames(array))
                        }
                    }
                }.distinct()
                return cloudstreamResult(
                    candidate,
                    json.optString("name").ifBlank { "Dépôt CloudStream" },
                    plugins,
                )
            }
            val resources = json.optJSONArray("resources")
            if (
                resources != null &&
                (0 until resources.length()).any {
                    resources.optString(it) == "stream" || resources.optJSONObject(it)?.optString("name") == "stream"
                }
            ) {
                return ImportResult(
                    Kind.STREMIO,
                    StremioClient.base(candidate),
                    json.optString("name").ifBlank { candidate.substringAfter("://").substringBefore('/') },
                )
            }
        }
        throw IllegalArgumentException(
            "Manifest Nuvio, Stremio ou CloudStream introuvable" +
                lastFailure?.message?.takeIf(String::isNotBlank)?.let { " : $it" }.orEmpty(),
        )
    }

    private fun candidates(value: String): List<String> {
        val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        val github = Regex("https?://github\\.com/([^/]+)/([^/#?]+)(?:/.*)?", RegexOption.IGNORE_CASE)
            .matchEntire(withScheme)
        if (github != null && !withScheme.contains("/raw/") && !withScheme.contains("/blob/")) {
            val owner = github.groupValues[1]
            val repo = github.groupValues[2].removeSuffix(".git")
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/HEAD/repo.json",
                "https://raw.githubusercontent.com/$owner/$repo/HEAD/manifest.json",
                withScheme,
            ).distinct()
        }
        return listOf(withScheme)
    }

    private fun cloudstreamResult(url: String, name: String, plugins: List<String>): ImportResult {
        val ids = plugins.mapNotNull(::nuvioBridge).toSet()
        return ImportResult(Kind.CLOUDSTREAM, url, name, plugins, ids)
    }

    private fun pluginNames(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { index ->
            val plugin = array.optJSONObject(index) ?: return@mapNotNull null
            plugin.optString("internalName").ifBlank { plugin.optString("name") }.takeIf(String::isNotBlank)
        }

    private fun nuvioBridge(name: String): String? {
        val key = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return when {
            "voiranimehomes" in key -> "voiranime-homes"
            "animesamafan" in key || "animesamaco" in key -> "animesama-co"
            "animesama" in key -> "anime-sama"
            "animeultime" in key -> "anime-ultime"
            "animesultra" in key -> "animesultra"
            "animevostfr" in key -> "animevostfr"
            "animoflix" in key -> "animoflix"
            "frenchmanga" in key -> "french-manga"
            "frenchstream" in key || "fsmirror" in key -> "frenchstream"
            "wiflix" in key || "flemmix" in key -> "flemmix"
            "papadustream" in key -> "papadustream"
            "waveanime" in key -> "waveanime"
            "voiranime" in key -> "voiranime"
            "neko" in key && "sama" in key -> "neko-sama"
            "coflix" in key -> "coflix"
            "movix" in key -> "movix"
            else -> null
        }
    }
}

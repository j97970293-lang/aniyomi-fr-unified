package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.json.JSONObject

/** Détecte uniquement les manifests réellement exécutables par FR Unifié. */
object ExternalSourceImporter {
    enum class Kind { NUVIO, STREMIO }

    data class ImportResult(
        val kind: Kind,
        val url: String,
        val name: String,
    ) {
        val summary: String
            get() = when (kind) {
                Kind.NUVIO -> "Dépôt Nuvio détecté : $name"
                Kind.STREMIO -> "Addon Stremio détecté : $name"
            }
    }

    suspend fun inspect(
        input: String,
        fetch: suspend (String) -> String = { FrRuntime.getText(it) },
    ): ImportResult {
        val raw = input.trim()
        require(raw.isNotBlank()) { "URL vide" }
        require(!raw.endsWith(".cs3", true) && !raw.endsWith("repo.json", true)) {
            "CloudStream a été retiré : ajoutez un manifest Nuvio ou Stremio exécutable."
        }

        var lastFailure: Throwable? = null
        for (candidate in candidates(raw)) {
            val json = try {
                JSONObject(fetch(candidate).trim())
            } catch (failure: Throwable) {
                lastFailure = failure
                continue
            }
            if (json.optJSONArray("scrapers") != null) {
                return ImportResult(
                    Kind.NUVIO,
                    candidate,
                    json.optString("name").ifBlank { candidate.substringAfter("://").substringBefore('/') },
                )
            }
            val resources = json.optJSONArray("resources")
            val hasStremioResource = resources != null &&
                (0 until resources.length()).any { index ->
                    val name = resources.optJSONObject(index)?.optString("name")?.takeIf(String::isNotBlank)
                        ?: resources.optString(index).takeIf(String::isNotBlank)
                    name in setOf("stream", "catalog", "meta", "subtitles")
                }
            if (hasStremioResource || json.optJSONArray("catalogs") != null) {
                val base = StremioClient.base(candidate)
                val addon = StremioCatalog.parseManifest(json, base)
                StremioCatalog.rememberCatalogs(base, addon.catalogs)
                return ImportResult(
                    Kind.STREMIO,
                    base,
                    addon.name,
                )
            }
        }
        throw IllegalArgumentException(
            "Manifest Nuvio ou Stremio introuvable" +
                lastFailure?.message?.takeIf(String::isNotBlank)?.let { " : $it" }.orEmpty(),
        )
    }

    private fun candidates(value: String): List<String> {
        val normalized = value.replace("stremio://", "https://")
        val withScheme = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "https://$normalized"
        }
        val github = Regex("https?://github\\.com/([^/]+)/([^/#?]+)(?:/.*)?", RegexOption.IGNORE_CASE)
            .matchEntire(withScheme)
        if (github != null && !withScheme.contains("/raw/") && !withScheme.contains("/blob/")) {
            val owner = github.groupValues[1]
            val repo = github.groupValues[2].removeSuffix(".git")
            return listOf(
                "https://raw.githubusercontent.com/$owner/$repo/HEAD/manifest.json",
                withScheme,
            ).distinct()
        }
        return listOf(
            withScheme,
            withScheme.removeSuffix("/").takeUnless { it.endsWith("manifest.json") }?.plus("/manifest.json"),
        ).filterNotNull().distinct()
    }
}

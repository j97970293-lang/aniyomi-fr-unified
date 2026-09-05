package eu.kanade.tachiyomi.animeextension.fr.frunified

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import org.json.JSONObject
import java.net.URLEncoder

object StremioClient {
    private val trackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
    )

    fun base(raw: String): String = raw.trim()
        .replace("stremio://", "https://")
        .removeSuffix("/")
        .removeSuffix("/manifest.json")
        .removeSuffix("/")

    private suspend fun stremioId(payload: PlayPayload): Pair<String, String>? {
        val imdb = TmdbCatalog.imdbId(payload) ?: return null
        return if (payload.isSeries) {
            "series" to "$imdb:${payload.season ?: 1}:${payload.episode ?: 1}"
        } else {
            "movie" to imdb
        }
    }

    suspend fun streams(payload: PlayPayload): List<Video> = coroutineScope {
        if (!FrSettings.useStremio || FrSettings.stremioUrls.isEmpty()) return@coroutineScope emptyList()
        val (type, id) = stremioId(payload) ?: return@coroutineScope emptyList()
        FrSettings.stremioUrls.distinct()
            .filter(FrSettings::isStremioEnabled)
            .map { addon ->
                async { runCatching { streamsFrom(addon, type, id, payload) }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
    }

    private suspend fun streamsFrom(
        addon: String,
        type: String,
        id: String,
        payload: PlayPayload,
    ): List<Video> = parseStreams(
        FrRuntime.getJson("${base(addon)}/stream/$type/$id.json"),
        addon,
        payload,
    )

    internal fun parseStreams(root: JSONObject, addon: String, payload: PlayPayload): List<Video> {
        val array = root.optJSONArray("streams") ?: return emptyList()
        val addonName = base(addon).substringAfter("://").substringBefore('/').take(36)
        return (0 until array.length()).mapNotNull { index ->
            val stream = array.optJSONObject(index) ?: return@mapNotNull null
            val label = listOfNotNull(
                stream.optString("name").takeIf { it.isNotBlank() && it != "null" },
                stream.optString("title").takeIf { it.isNotBlank() && it != "null" },
                stream.optString("description").takeIf { it.isNotBlank() && it != "null" },
            ).joinToString(" ").replace('\n', ' ').trim().take(160).ifBlank { "Flux" }
            val quality = stream.optString("quality").takeIf { it.isNotBlank() && it != "null" }
            val url = stream.optString("url").takeIf { it.startsWith("http") && !isPlaceholder(it) }
            val infoHash = stream.optString("infoHash").takeIf { it.matches(Regex("[A-Fa-f0-9]{40}")) }
            val topLevelHeaders = stream.optJSONObject("headers")
            val proxyHeaders = stream.optJSONObject("behaviorHints")
                ?.optJSONObject("proxyHeaders")
                ?.optJSONObject("request")
            val headers = mergeHeaders(topLevelHeaders, proxyHeaders)
            val finalUrl = when {
                url != null -> url

                infoHash != null -> buildString {
                    append("magnet:?xt=urn:btih:")
                    append(infoHash)
                    append("&dn=")
                    append(URLEncoder.encode(payload.primaryTitle, "UTF-8"))
                    stream.optInt("fileIdx", -1).takeIf { it >= 0 }?.let { append("&index=$it") }
                    val sources = stream.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            sources.optString(i).takeIf(String::isNotBlank)?.let {
                                append("&tr=")
                                append(URLEncoder.encode(it, "UTF-8"))
                            }
                        }
                    } else {
                        trackers.forEach {
                            append("&tr=")
                            append(URLEncoder.encode(it, "UTF-8"))
                        }
                    }
                }

                else -> return@mapNotNull null
            }
            val displayLabel = listOfNotNull(label, quality?.takeUnless { label.contains(it, true) })
                .joinToString(" • ")
            Video(
                videoUrl = finalUrl,
                videoTitle = "Stremio · $addonName • $displayLabel",
                resolution = qualityOf("$label $quality"),
                headers = headers,
                preferred = isPreferred("$label $quality"),
            )
        }
    }

    suspend fun subtitles(payload: PlayPayload): List<Track> = coroutineScope {
        if (!FrSettings.useSubtitles) return@coroutineScope emptyList()
        val (type, id) = stremioId(payload) ?: return@coroutineScope emptyList()
        val addons = (FrSettings.stremioUrls + FrSettings.DEFAULT_SUBTITLE_ADDON)
            .distinct()
            .filter { it == FrSettings.DEFAULT_SUBTITLE_ADDON || FrSettings.isStremioEnabled(it) }
        addons.map { addon ->
            async { runCatching { subtitlesFrom(addon, type, id) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten().distinctBy { "${it.lang}|${it.url}" }
    }

    private suspend fun subtitlesFrom(addon: String, type: String, id: String): List<Track> {
        val root = FrRuntime.getJson("${base(addon)}/subtitles/$type/$id.json")
        val array = root.optJSONArray("subtitles") ?: return emptyList()
        val wanted = FrSettings.subtitleLangs
        return (0 until array.length()).mapNotNull { index ->
            val sub = array.optJSONObject(index) ?: return@mapNotNull null
            val url = sub.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val lang = sub.optString("lang", "und").lowercase()
            if (wanted.isNotEmpty() && wanted.none { lang.startsWith(it) || it.startsWith(lang) }) {
                return@mapNotNull null
            }
            val label = when {
                lang.startsWith("fr") -> "Français"
                lang.startsWith("en") -> "English"
                else -> lang.uppercase()
            }
            Track(url = url, lang = label)
        }
    }

    private fun mergeHeaders(vararg roots: JSONObject?): Headers? {
        val builder = Headers.Builder()
        var count = 0
        roots.filterNotNull().forEach { root ->
            root.keys().forEach { key ->
                root.optString(key).takeIf(String::isNotBlank)?.let { value ->
                    runCatching { builder.set(key, value) }.onSuccess { count++ }
                }
            }
        }
        return if (count > 0) builder.build() else null
    }

    private fun isPlaceholder(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("test-videos.co.uk") ||
            lower.contains("big_buck_bunny") ||
            lower.contains("sample-videos.com") ||
            lower.contains("example.com") ||
            lower.contains("localhost") ||
            lower.contains("/troll/master.m3u8")
    }

    fun qualityOf(text: String): Int? = when {
        text.contains("2160", true) || text.contains("4k", true) -> 2160
        text.contains("1440", true) -> 1440
        text.contains("1080", true) -> 1080
        text.contains("720", true) -> 720
        text.contains("480", true) -> 480
        text.contains("360", true) -> 360
        else -> null
    }

    fun isPreferred(text: String): Boolean {
        val upper = text.uppercase()
        return FrSettings.nuvioPriorityPatterns.take(3).any(upper::contains)
    }
}

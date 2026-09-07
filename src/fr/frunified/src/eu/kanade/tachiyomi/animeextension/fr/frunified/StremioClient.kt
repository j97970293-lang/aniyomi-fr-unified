package eu.kanade.tachiyomi.animeextension.fr.frunified

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

object StremioClient {
    /** Durée maximale d'une requête de flux : un addon lent ne doit jamais bloquer la liste. */
    private const val STREAM_TIMEOUT_MS = 15_000L

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

    private data class HosterPayload(
        val addon: String,
        val addonName: String,
        val payload: PlayPayload,
        val targets: List<StremioCatalog.StreamTarget>,
    )

    private suspend fun streamTargets(payload: PlayPayload): List<StremioCatalog.StreamTarget> {
        val directType = payload.stremioType?.takeIf(String::isNotBlank)
        val directId = payload.stremioId?.takeIf(String::isNotBlank)
        val season = payload.season ?: 1
        val episode = payload.episode ?: 1
        val directImdb = directId?.let { Regex("^(tt[0-9]+)").find(it)?.groupValues?.get(1) }
        val imdb = payload.imdbId ?: directImdb ?: runCatching { TmdbCatalog.imdbId(payload) }.getOrNull()
        val directTmdb = directId?.let {
            Regex("^tmdb:([0-9]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val tmdb = payload.tmdbId ?: directTmdb

        val ids = linkedSetOf<String>()
        directId?.let(ids::add)
        if (payload.isSeries) {
            imdb?.let { ids += "$it:$season:$episode" }
            tmdb?.let { ids += "tmdb:$it:$season:$episode" }
        } else {
            imdb?.let(ids::add)
            tmdb?.let { ids += "tmdb:$it" }
        }
        if (ids.isEmpty()) return emptyList()

        val types = linkedSetOf<String>()
        directType?.let(types::add)
        if (payload.isSeries) {
            types += "series"
            types += "tv"
        } else {
            types += "movie"
        }

        return buildList {
            if (directType != null && directId != null) {
                add(StremioCatalog.StreamTarget(directType, directId))
            }
            types.forEach { type ->
                ids.forEach { id -> add(StremioCatalog.StreamTarget(type, id)) }
            }
        }.distinctBy { "${it.type.lowercase()}|${it.id}" }
    }

    /** Les serveurs sont exposés dès le manifest, puis leurs flux sont chargés au clic comme dans Stremio. */
    suspend fun hosters(payload: PlayPayload): List<Hoster> {
        if (!FrSettings.useStremio || FrSettings.stremioUrls.isEmpty()) return emptyList()
        val addons = StremioCatalog.streamAddons(streamTargets(payload))
        return addons.mapNotNull { addon ->
            val first = addon.targets.firstOrNull() ?: return@mapNotNull null
            Hoster(
                hosterUrl = "${base(addon.base)}/stream/${first.type}/${first.id}.json",
                hosterName = "Stremio · ${addon.name}",
                internalData = encodeHosterPayload(addon, payload),
            )
        }
    }

    suspend fun streams(hoster: Hoster): List<Video> = coroutineScope {
        val data = decodeHosterPayload(hoster.internalData) ?: return@coroutineScope emptyList()
        val subtitles = async {
            withTimeoutOrNull(3_000L) {
                runCatching { subtitles(data.payload) }.getOrDefault(emptyList())
            }.orEmpty()
        }
        val videos = data.targets.map { target ->
            async {
                runCatching {
                    withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                        streamsFrom(data.addon, target.type, target.id, data.payload, data.addonName)
                    }.orEmpty()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().limitedDistinct()
        val tracks = subtitles.await()
        if (tracks.isEmpty()) {
            videos
        } else {
            videos.map { video ->
                video.copy(subtitleTracks = (video.subtitleTracks + tracks).distinctBy { "${it.lang}|${it.url}" })
            }
        }
    }

    suspend fun streams(payload: PlayPayload): List<Video> = coroutineScope {
        if (!FrSettings.useStremio || FrSettings.stremioUrls.isEmpty()) return@coroutineScope emptyList()
        val targets = streamTargets(payload)
        val addons = StremioCatalog.streamAddons(targets)
        val jobs = addons.flatMap { addon ->
            addon.targets.map { target ->
                async {
                    runCatching {
                        withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                            streamsFrom(addon.base, target.type, target.id, payload, addon.name)
                        }.orEmpty()
                    }.getOrDefault(emptyList())
                }
            }
        } +
            listOfNotNull(
                payload.stremioAddon?.takeIf { payload.stremioId == payload.stremioMetaId }?.let { addon ->
                    val target = targets.firstOrNull()
                    target?.let {
                        async {
                            runCatching {
                                withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                                    inlineMetaStreams(addon, it.type, payload.stremioMetaId ?: it.id, payload)
                                }.orEmpty()
                            }.getOrDefault(emptyList())
                        }
                    }
                },
            )
        jobs.awaitAll().flatten().limitedDistinct()
    }

    private fun List<Video>.limitedDistinct(): List<Video> =
        distinctBy { "${it.videoUrl}|${it.videoTitle}" }.let { videos ->
            val limit = FrSettings.stremioMaxStreams
            if (limit > 0) videos.take(limit) else videos
        }

    private fun encodeHosterPayload(addon: StremioCatalog.StreamAddon, payload: PlayPayload): String {
        val root = JSONObject().apply {
            put("addon", addon.base)
            put("name", addon.name)
            put("payload", payload.serialize())
            put(
                "targets",
                JSONArray().apply {
                    addon.targets.forEach { target ->
                        put(JSONObject().put("type", target.type).put("id", target.id))
                    }
                },
            )
        }
        return "stremio-hoster/" +
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(root.toString().toByteArray(Charsets.UTF_8))
    }

    private fun decodeHosterPayload(value: String?): HosterPayload? = runCatching {
        if (value.isNullOrBlank() || !value.startsWith("stremio-hoster/")) return@runCatching null
        val text = String(Base64.getUrlDecoder().decode(value.substringAfter('/')), Charsets.UTF_8)
        val root = JSONObject(text)
        val payload = PlayPayload.parse(root.getString("payload")) ?: return@runCatching null
        val targets = root.optJSONArray("targets")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val type = item.optString("type").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                StremioCatalog.StreamTarget(type, id)
            }
        }.orEmpty()
        HosterPayload(
            addon = root.getString("addon"),
            addonName = root.optString("name").ifBlank {
                root.getString("addon").substringAfter("://").substringBefore('/')
            },
            payload = payload,
            targets = targets,
        )
    }.getOrNull()

    fun isLazyHoster(hoster: Hoster): Boolean = hoster.internalData?.startsWith("stremio-hoster/") == true

    private suspend fun inlineMetaStreams(
        addon: String,
        type: String,
        id: String,
        payload: PlayPayload,
    ): List<Video> {
        val meta = FrRuntime.getJson("${base(addon)}/meta/$type/$id.json").optJSONObject("meta")
            ?: return emptyList()
        return parseStreams(meta, addon, payload).filter(NuvioClient::acceptsStream)
    }

    private suspend fun streamsFrom(
        addon: String,
        type: String,
        id: String,
        payload: PlayPayload,
        addonLabel: String? = null,
    ): List<Video> = parseStreams(
        FrRuntime.getJson("${base(addon)}/stream/$type/$id.json"),
        addon,
        payload,
        addonLabel,
    ).filter(NuvioClient::acceptsStream)

    internal fun parseStreams(
        root: JSONObject,
        addon: String,
        payload: PlayPayload,
        addonLabel: String? = null,
    ): List<Video> {
        val array = root.optJSONArray("streams") ?: return emptyList()
        val addonName = addonLabel?.take(36)
            ?: base(addon).substringAfter("://").substringBefore('/').take(36)
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
        val targets = streamTargets(payload)
        val target = targets.firstOrNull {
            it.id.startsWith("tt") && it.type.lowercase() in setOf("movie", "series")
        } ?: targets.firstOrNull() ?: return@coroutineScope emptyList()
        val type = target.type
        val id = target.id
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

    fun priorityRank(text: String): Int {
        val upper = text.uppercase()
        return FrSettings.nuvioPriorityPatterns.indexOfFirst(upper::contains)
            .let { if (it < 0) Int.MAX_VALUE else it }
    }

    fun isPreferred(text: String): Boolean = priorityRank(text) != Int.MAX_VALUE
}

package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences

/** Préférences partagées par le catalogue, Stremio et le moteur Nuvio. */
object FrSettings {
    const val KEY_SETTINGS_VERSION = "fr_unified_settings_version"
    const val SETTINGS_VERSION = 2
    const val KEY_STREMIO = "stremio_urls"
    const val KEY_STREMIO_DISABLED = "stremio_disabled"
    const val KEY_USE_STREMIO = "use_stremio"
    const val KEY_CLOUDSTREAM_REPOS = "cloudstream_repos"
    const val KEY_USE_SUBS = "use_subtitles"
    const val KEY_SUB_LANGS = "subtitle_langs"
    const val KEY_USE_NUVIO = "use_nuvio"
    const val KEY_NUVIO_REPOS = "nuvio_repos"
    const val KEY_NUVIO_DISABLED = "nuvio_disabled"
    const val KEY_NUVIO_ALL = "nuvio_all_langs"
    const val KEY_NUVIO_REPOS_DISABLED = "nuvio_repos_disabled"
    const val KEY_NUVIO_MAX = "nuvio_max_per_scraper"
    const val KEY_NUVIO_PRIORITY = "nuvio_priority_patterns"
    const val KEY_NUVIO_ORDER = "nuvio_order"
    const val KEY_NUVIO_CONCURRENCY = "nuvio_concurrency"
    const val KEY_TMDB = "tmdb_api_key"
    const val KEY_TOKENS = "api_tokens"
    const val KEY_UA = "nuvio_ua"
    const val KEY_REFERER = "nuvio_referer"
    const val KEY_COOKIES = "nuvio_cookies"
    const val KEY_USE_TMDB = "use_tmdb_catalog"
    const val KEY_USE_ANIME = "use_anime_catalog"
    const val KEY_POPULAR = "popular_catalog"

    const val DEFAULT_SUBTITLE_ADDON = "https://opensubtitles-v3.strem.io"
    val DEFAULT_STREMIO_ADDONS = listOf(
        "https://nuvio-french-providers.onrender.com",
    )
    val DEFAULT_CLOUDSTREAM_REPOS = listOf(
        "https://raw.githubusercontent.com/mouradchaouche/cloudstream-frenchrepo/HEAD/repo.json",
        "https://raw.githubusercontent.com/Nikola17/cloudstream-frenchstream/HEAD/repo.json",
        "https://raw.githubusercontent.com/blizzx4644/Movix-cloudstream/HEAD/repo.json",
        "https://raw.githubusercontent.com/Kraptor123/Cs-Karma/HEAD/repo.json",
    )
    const val DEFAULT_TMDB_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    val DEFAULT_NUVIO_REPOS = listOf(
        "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/z7kx/z7kx-nuvio-provider/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/refs/heads/main/manifest.json",
    )
    val DEFAULT_NUVIO_PRIORITY = listOf("VF", "FRENCH", "VOSTFR", "1080", "HD")

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(sharedPreferences: SharedPreferences) {
        prefs = sharedPreferences
    }

    private fun any(key: String): Any? = prefs?.all?.get(key)

    private fun string(key: String, default: String): String = when (val value = any(key)) {
        is String -> value
        is Boolean -> if (value) "1" else "0"
        is Number -> value.toString()
        else -> default
    }

    private fun bool(key: String, default: Boolean): Boolean = when (val value = any(key)) {
        is Boolean -> value
        is String -> value == "1" || value.equals("true", true)
        else -> default
    }

    val stremioUrls: List<String>
        get() = string(KEY_STREMIO, DEFAULT_STREMIO_ADDONS.joinToString("\n"))
            .lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val stremioDisabled: Set<String>
        get() = string(KEY_STREMIO_DISABLED, "")
            .lineSequence().map { it.trim().removeSuffix("/") }.filter(String::isNotBlank).toSet()
    val cloudstreamRepos: List<String>
        get() = string(KEY_CLOUDSTREAM_REPOS, DEFAULT_CLOUDSTREAM_REPOS.joinToString("\n"))
            .lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val useStremio: Boolean get() = bool(KEY_USE_STREMIO, true)
    val useSubtitles: Boolean get() = bool(KEY_USE_SUBS, true)
    val subtitleLangs: List<String>
        get() = string(KEY_SUB_LANGS, "fre,fra,fr,eng,en")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }

    val useNuvio: Boolean get() = bool(KEY_USE_NUVIO, true)
    val nuvioRepos: List<String>
        get() = string(KEY_NUVIO_REPOS, DEFAULT_NUVIO_REPOS.joinToString("\n"))
            .lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val nuvioReposDisabled: Set<String>
        get() = string(KEY_NUVIO_REPOS_DISABLED, "")
            .lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
    val nuvioDisabled: Set<String>
        get() = string(KEY_NUVIO_DISABLED, "")
            .lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
    val nuvioAllLangs: Boolean get() = bool(KEY_NUVIO_ALL, false)
    val nuvioMaxPerScraper: Int
        get() = string(KEY_NUVIO_MAX, "12").toIntOrNull()?.coerceIn(0, 200) ?: 12
    val nuvioPriorityPatterns: List<String>
        get() = string(KEY_NUVIO_PRIORITY, DEFAULT_NUVIO_PRIORITY.joinToString(","))
            .split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }
    val nuvioOrder: List<String>
        get() = string(KEY_NUVIO_ORDER, "").lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    val nuvioConcurrency: Int
        get() = string(KEY_NUVIO_CONCURRENCY, "6").toIntOrNull()?.coerceIn(1, 12) ?: 6
    val nuvioUserAgent: String get() = string(KEY_UA, DEFAULT_USER_AGENT).trim()
    val nuvioReferer: String get() = string(KEY_REFERER, "https://www.google.com/").trim()
    val nuvioCookies: String get() = string(KEY_COOKIES, "").trim()

    val tmdbApiKey: String get() = string(KEY_TMDB, DEFAULT_TMDB_KEY).trim().ifBlank { DEFAULT_TMDB_KEY }
    val useTmdbCatalog: Boolean get() = bool(KEY_USE_TMDB, true)
    val useAnimeCatalog: Boolean get() = bool(KEY_USE_ANIME, true)
    val popularCatalog: String get() = string(KEY_POPULAR, "mixed")

    val apiTokens: Map<String, String>
        get() = string(KEY_TOKENS, "").lineSequence()
            .map(String::trim)
            .filter { it.contains('=') }
            .associate { line ->
                line.substringBefore('=').trim().uppercase() to line.substringAfter('=').trim()
            }

    fun isNuvioEnabled(id: String): Boolean = id !in nuvioDisabled
    fun isNuvioRepoEnabled(repo: String): Boolean = repo.trim() !in nuvioReposDisabled
    fun isStremioEnabled(url: String): Boolean = url.trim().removeSuffix("/") !in stremioDisabled
}

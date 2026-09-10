package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import org.json.JSONObject

/** Préférences partagées par les catalogues, Stremio et le moteur Nuvio. */
object FrSettings {
    const val KEY_SETTINGS_VERSION = "fr_unified_settings_version"
    const val SETTINGS_VERSION = 12

    /** Langue de l'interface de l'extension (français par défaut, anglais en option). */
    const val KEY_UI_LANGUAGE = "ui_language"

    /** Serveurs DNS personnalisés (IP, IP:port ou URL DoH, un par ligne). */
    const val KEY_DNS_HOSTS = "dns_hosts"

    /** Organisation des saisons dans les fiches : classique, fusionnées ou séparées. */
    const val KEY_SERIES_LAYOUT = "series_layout"

    /** Vérification du contenu des liens (anti-popups / anti-pages HTML) avant lecture. */
    const val KEY_VERIFY_STREAM_CONTENT = "verify_stream_content"

    /** Tolère les liens 403 (CDN stricts : Movix, FSVid) : conservés au lieu d'être écartés. */
    const val KEY_NUVIO_TOLERATE_403 = "nuvio_tolerate_403"

    const val KEY_STREMIO = "stremio_urls"
    const val KEY_STREMIO_DISABLED = "stremio_disabled"
    const val KEY_USE_STREMIO = "use_stremio"
    const val KEY_USE_STREMIO_CATALOG = "use_stremio_catalog"
    const val KEY_STREMIO_CATALOG = "stremio_catalog"
    const val KEY_STREMIO_CATALOG_CACHE = "stremio_catalog_cache_v5"

    /** Valeur spéciale du catalogue Stremio : interroge tous les catalogues actifs à la fois. */
    const val STREMIO_CATALOG_ALL = "all"
    const val KEY_ENGINE_ORDER = "resolver_engine_order"
    const val KEY_STREMIO_MAX = "stremio_max_streams"
    const val KEY_STREMIO_AUTO_UPDATE = "stremio_auto_update"
    const val KEY_STREMIO_LAST_UPDATE = "stremio_last_update"

    /** Rang des addons Stremio (celui du haut est interrogé d'abord), flèches ↑↓ du sélecteur. */
    const val KEY_STREMIO_ORDER = "stremio_order"

    const val KEY_USE_SUBS = "use_subtitles"
    const val KEY_SUB_LANGS = "subtitle_langs"

    const val KEY_USE_NUVIO = "use_nuvio"
    const val KEY_NUVIO_REPOS = "nuvio_repos"
    const val KEY_NUVIO_ENABLED = "nuvio_enabled_v4"
    const val KEY_NUVIO_DISABLED = "nuvio_disabled" // migration des versions 16.4/16.5
    const val KEY_NUVIO_ALL = "nuvio_all_langs" // migration
    const val KEY_NUVIO_LANGUAGES = "nuvio_languages"
    const val KEY_NUVIO_REPOS_DISABLED = "nuvio_repos_disabled"
    const val KEY_NUVIO_MAX = "nuvio_max_per_scraper"
    const val KEY_NUVIO_PRIORITY = "nuvio_priority_patterns" // migration vers KEY_STREAM_ORDER (16.9)
    const val KEY_NUVIO_ORDER = "nuvio_order"
    const val KEY_NUVIO_CONCURRENCY = "nuvio_concurrency"
    const val KEY_NUVIO_SEARCH_MODE = "nuvio_search_mode"
    const val KEY_NUVIO_AUTO_UPDATE = "nuvio_auto_update"
    const val KEY_NUVIO_LAST_UPDATE = "nuvio_last_update"

    /**
     * Sources bloquées globalement après suppression d'un dépôt (16.17) : les ids
     * des providers d'un dépôt supprimé y restent inscrits, de sorte que la même
     * source ne ressuscite pas via un autre dépôt qui la fournit aussi.
     */
    const val KEY_NUVIO_BLOCKED = "nuvio_blocked"

    /** Qualités exclues du résultat (chips façon NuviO : 1080p, 4K, HDR, CAM, TS…). */
    const val KEY_NUVIO_QUALITY_EXCLUDES = "nuvio_quality_excludes"

    const val KEY_STREAM_ORDER = "stream_order"
    const val KEY_CUSTOM_QUALITIES = "custom_qualities"

    /** Langues ajoutées au classement des flux (EN, TR, …) — tokens de 2 à 3 lettres. */
    const val KEY_STREAM_LANGUAGES = "stream_languages"

    /** Configuration par source Nuvio : JSON `{ "idSource": { "CLÉ": "valeur" } }`. */
    const val KEY_NUVIO_SOURCE_ENV = "nuvio_source_env"
    const val KEY_QUICK_SEARCH = "quick_search"
    const val KEY_BACKUP_AUTO_RESTORE = "backup_auto_restore"
    const val KEY_BACKUP_URL = "backup_url"
    const val KEY_BACKUP_LAST_RESTORE = "backup_last_restore"
    const val KEY_TOKENS = "api_tokens"
    const val KEY_UA = "nuvio_ua"
    const val KEY_REFERER = "nuvio_referer"
    const val KEY_COOKIES = "nuvio_cookies"

    const val KEY_TMDB = "tmdb_api_key"
    const val KEY_USE_MAIN_CATALOGS = "use_main_catalogs" // remplacé par la déduction TMDB + AniList (16.13)
    const val KEY_USE_TMDB = "use_tmdb_catalog"
    const val KEY_USE_ANIME = "use_anime_catalog" // clé historique, désormais dédiée à AniList
    const val KEY_USE_JIKAN = "use_jikan_catalog"
    const val KEY_POPULAR = "popular_catalog"
    const val KEY_CATALOG_LANGUAGE = "catalog_language"
    const val KEY_CATALOG_PRIMARY_LANGUAGE = "catalog_primary_language"

    const val DEFAULT_SUBTITLE_ADDON = "https://opensubtitles-v3.strem.io"
    private const val DEFAULT_STREAM_ADDON = "https://nuvio-french-providers.onrender.com"
    private const val DEFAULT_STREMIO_CATALOG_HOST = "https://tmdb.elfhosted.com"

    val CATALOG_LANGUAGE_LABELS = linkedMapOf(
        "fr-FR" to "Français",
        "en-US" to "English",
        "es-ES" to "Español",
        "de-DE" to "Deutsch",
        "it-IT" to "Italiano",
        "pt-BR" to "Português",
        "ja-JP" to "日本語",
        "hi-IN" to "हिन्दी",
        "tr-TR" to "Türkçe",
        "id-ID" to "Bahasa Indonesia",
        "pl-PL" to "Polski",
        "ar-SA" to "العربية",
    )

    /** Drapeau emoji d'une langue (affiché à la place du texte de pays dans les listes). */
    val LANGUAGE_FLAGS = mapOf(
        "fr" to "🇫🇷",
        "en" to "🇬🇧",
        "es" to "🇪🇸",
        "de" to "🇩🇪",
        "it" to "🇮🇹",
        "pt" to "🇵🇹",
        "ja" to "🇯🇵",
        "hi" to "🇮🇳",
        "tr" to "🇹🇷",
        "id" to "🇮🇩",
        "pl" to "🇵🇱",
        "ar" to "🇸🇦",
        "ta" to "🇮🇳",
        "te" to "🇮🇳",
        "ml" to "🇮🇳",
        "kn" to "🇮🇳",
        "el" to "🇬🇷",
        "nl" to "🇳🇱",
        "ko" to "🇰🇷",
        "zh" to "🇨🇳",
        "ru" to "🇷🇺",
        "th" to "🇹🇭",
        "vi" to "🇻🇳",
    )

    /** Drapeau représentant une liste de langues ; « 🌐 » si plusieurs langues différentes. */
    fun flagForLanguages(languages: List<String>): String {
        val normalized = languages
            .map { it.lowercase().substringBefore('-').trim() }
            .filter(String::isNotBlank)
            .distinct()
        val flag = normalized.singleOrNull()?.let { LANGUAGE_FLAGS[it] }
        return flag ?: "🌐"
    }

    /** Libellés des langues avec drapeau emoji, pour les sélecteurs visuels. */
    fun flagLabel(language: String, label: String): String =
        "${LANGUAGE_FLAGS[language.lowercase().substringBefore('-')] ?: "🌐"} $label"

    val DEFAULT_STREMIO_ADDONS: List<String>
        get() = listOf(DEFAULT_STREAM_ADDON) +
            catalogLanguages.map { "$DEFAULT_STREMIO_CATALOG_HOST/$it" }

    const val DEFAULT_TMDB_KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    val LEGACY_DEFAULT_NUVIO_REPOS = listOf(
        "https://raw.githubusercontent.com/Gowaru/gowaru-nuvio-providers/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/z7kx/z7kx-nuvio-provider/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/phisher98/phisher-nuvio-providers/refs/heads/main/manifest.json",
    )
    val DEFAULT_NUVIO_REPOS = listOf(
        LEGACY_DEFAULT_NUVIO_REPOS[0],
        "https://raw.githubusercontent.com/D3adlyRocket/Anime-Nuvio/refs/heads/main/manifest.json",
        "https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json",
        LEGACY_DEFAULT_NUVIO_REPOS[2],
        "https://raw.githubusercontent.com/fmustafayaman/turkish-nuvio/main/manifest.json",
    )

    /** Sources qui ont réellement rendu au moins un flux via Kotlin/Rhino le 6 septembre 2026. */
    val RECOMMENDED_NUVIO_IDS = listOf(
        "frenchstream",
        "anime-sama",
        "animesama-co",
        "french-manga",
        "mugiwarastream",
        "vostfree",
    )

    /**
     * Une installation neuve active tous les providers des dépôts par défaut, français ou
     * non : aucune langue ne bloque l'exécution d'un site, comme dans l'application NuviO.
     * Les entrées préfixées par `!` sont des exclusions ; Movix reste actif par défaut
     * (ses liens 403 sont conservés, réglable dans « Options de recherche des sources »).
     */
    const val DEFAULT_NUVIO_ENABLED = "all"

    val GOWARU_NUVIO_IDS = setOf(
        "anime-ultime",
        "anime-sama",
        "animesama-co",
        "animesultra",
        "animevostfr",
        "animoflix",
        "coflix",
        "dulourd",
        "flemmix",
        "french-manga",
        "voiranime-homes",
        "frenchstream",
        "movix",
        "mugiwarastream",
        "sekai",
        "voiranime",
        "voiranime-rip",
        "vostfree",
        "nakios",
        "papadustream",
        "wookafr",
        "streamzo",
        "waveanime",
        "neko-sama",
        "fullanime",
        "animevost-fr",
    )

    /** Conservé pour les tests et la migration des préférences v3. */
    val DEFAULT_NUVIO_DISABLED: Set<String> = GOWARU_NUVIO_IDS - RECOMMENDED_NUVIO_IDS.toSet()
    val DEFAULT_NUVIO_PRIORITY = listOf("VF", "VFF", "VFQ", "MULTI", "VOSTFR", "1080", "HD")

    /**
     * Langues que l'utilisateur peut ajouter au classement des flux, au-delà de
     * VF/VFF/VFQ/MULTI/VOSTFR/VO : codes ISO-639-1 avec drapeau. Un site non français
     * (EN, TR, ES…) devient alors un critère classable comme les autres.
     */
    val ADDABLE_STREAM_LANGUAGES = listOf(
        "en", "es", "de", "it", "pt", "tr", "nl", "pl", "ru", "ar",
        "hi", "id", "ko", "ja", "vi", "th", "el", "cs", "sv", "da",
    )

    /** Langues personnalisées du classement des flux (tokens majuscules, ex. « EN », « TR »). */
    val customLanguages: List<String>
        get() = string(KEY_STREAM_LANGUAGES, "")
            .split(Regex("[,\\n; ]+"))
            .map { it.trim().uppercase() }
            .filter { it.length in 2..3 && it.all(Char::isLetter) }
            .filterNot { it in StreamLabel.LANGUAGE_ORDER }
            .distinct()

    /** Tous les critères de flux connus du classement à flèches (langues puis qualités). */
    val STREAM_CRITERIA: List<String>
        get() = StreamLabel.LANGUAGE_ORDER +
            customLanguages +
            (customQualities + StreamLabel.QUALITY_VALUES).distinct().sortedDescending().map(StreamLabel::qualityText)

    /**
     * Ordre par défaut des critères de flux classés avec les flèches : d'abord les langues
     * (VF avant VOSTFR), puis les qualités (1080p avant 4K, plus léger sur mobile, puis 720p).
     */
    val DEFAULT_STREAM_ORDER = listOf(
        "VF", "VFF", "VFQ", "MULTI", "VOSTFR", "VO",
        "1080p", "4K", "1440p", "720p", "480p", "360p",
    )

    /** Intervalle de rafraîchissement automatique des dépôts et scripts Nuvio (24 h). */
    const val NUVIO_AUTO_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    const val DAILY_INTERVAL_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(sharedPreferences: SharedPreferences) {
        prefs = sharedPreferences
    }

    private fun any(key: String): Any? = prefs?.all?.get(key)

    fun has(key: String): Boolean = prefs?.all?.containsKey(key) == true

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

    val catalogLanguages: List<String>
        get() {
            val selected = string(KEY_CATALOG_LANGUAGE, "fr-FR")
                .split(Regex("[,\\n]"))
                .map(String::trim)
                .filter(CATALOG_LANGUAGE_LABELS::containsKey)
                .distinct()
                .ifEmpty { listOf("fr-FR") }
            val primary = string(KEY_CATALOG_PRIMARY_LANGUAGE, selected.first())
                .takeIf(CATALOG_LANGUAGE_LABELS::containsKey) ?: selected.first()
            return listOf(primary) + selected.filterNot { it == primary }
        }
    val catalogLanguage: String get() = catalogLanguages.first()
    val catalogRegion: String get() = catalogLanguage.substringAfter('-').uppercase()

    private fun cleanStremioUrl(value: String): String = value.trim()
        .removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")

    private fun isDefaultTmdbAddon(value: String): Boolean = cleanStremioUrl(value).matches(
        Regex("(?i)^https://tmdb\\.elfhosted\\.com/[a-z]{2}-[a-z]{2}$"),
    )

    val stremioUrls: List<String>
        get() {
            // Une liste explicitement vidée (suppression d'addons) n'est pas
            // remplacée par les valeurs par défaut ; celles-ci restent énumérées
            // mais passent par la liste de désactivation si elles ont été supprimées.
            val raw = if (has(KEY_STREMIO)) {
                string(KEY_STREMIO, "")
            } else {
                DEFAULT_STREMIO_ADDONS.joinToString("\n")
            }
            val configured = raw.lineSequence().map(::cleanStremioUrl).filter(String::isNotBlank)
                .filterNot(::isDefaultTmdbAddon)
            return (configured + DEFAULT_STREMIO_ADDONS).distinct().toList()
        }
    val stremioDisabled: Set<String>
        get() = string(KEY_STREMIO_DISABLED, "")
            .lineSequence().map { it.trim().removeSuffix("/") }.filter(String::isNotBlank).toSet()
    val useStremio: Boolean get() = bool(KEY_USE_STREMIO, true)
    val useStremioCatalog: Boolean get() = bool(KEY_USE_STREMIO_CATALOG, true)
    val stremioCatalogKey: String get() = string(KEY_STREMIO_CATALOG, "")
    val stremioCatalogCache: String get() = string(KEY_STREMIO_CATALOG_CACHE, "")

    /**
     * Rang des addons Stremio (16.17) : l'addon du haut est interrogé d'abord.
     * Sert de repli l'ordre des URLs configurées ; les nouveaux addons s'ajoutent
     * à la fin tant qu'ils n'ont pas été classés avec les flèches.
     */
    val stremioOrder: List<String>
        get() {
            val saved = string(KEY_STREMIO_ORDER, "")
                .lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            if (saved.isEmpty()) return stremioUrls.distinct()
            return saved +
                stremioUrls.distinct().filterNot { base ->
                    saved.any { it.equals(base, true) }
                }
        }

    internal fun saveStremioOrder(values: List<String>) {
        runCatching {
            prefs?.edit()?.putString(KEY_STREMIO_ORDER, values.distinct().joinToString("\n"))?.apply()
        }
    }

    internal fun saveStremioCatalogCache(value: String) {
        runCatching { prefs?.edit()?.putString(KEY_STREMIO_CATALOG_CACHE, value)?.apply() }
    }

    val engineOrder: String
        get() = string(KEY_ENGINE_ORDER, "nuvio_first")
            .takeIf { it == "nuvio_first" || it == "stremio_first" } ?: "nuvio_first"
    val stremioMaxStreams: Int
        get() = string(KEY_STREMIO_MAX, "8").toIntOrNull()?.coerceIn(0, 100) ?: 8
    val stremioAutoUpdate: Boolean get() = bool(KEY_STREMIO_AUTO_UPDATE, true)
    val stremioLastUpdate: Long get() = long(KEY_STREMIO_LAST_UPDATE)
    internal fun saveStremioLastUpdate(timestamp: Long) = saveLong(KEY_STREMIO_LAST_UPDATE, timestamp)

    val useSubtitles: Boolean get() = bool(KEY_USE_SUBS, true)
    val subtitleLangs: List<String>
        get() = string(KEY_SUB_LANGS, "fre,fra,fr,eng,en")
            .split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }

    val useNuvio: Boolean get() = bool(KEY_USE_NUVIO, true)
    val nuvioRepos: List<String>
        get() {
            // Une liste explicitement vidée (tous les dépôts supprimés) n'est pas
            // remplacée par les dépôts par défaut.
            val raw = if (has(KEY_NUVIO_REPOS)) {
                string(KEY_NUVIO_REPOS, "")
            } else {
                DEFAULT_NUVIO_REPOS.joinToString("\n")
            }
            return raw.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
        }
    val nuvioReposDisabled: Set<String>
        get() = string(KEY_NUVIO_REPOS_DISABLED, "")
            .lineSequence().map(String::trim).filter(String::isNotBlank).toSet()

    /**
     * Ids de providers bloqués après suppression d'un dépôt (16.17) : un dépôt
     * supprimé « vraiment » ne fournit plus aucune source, même si un autre dépôt
     * déclare un provider portant le même id. Réajouter l'URL du dépôt lève le
     * blocage de ses providers ; « Restaurer » dans le sélecteur lève tout blocage.
     */
    val nuvioBlocked: Set<String>
        get() = string(KEY_NUVIO_BLOCKED, "")
            .lineSequence().map { it.trim().lowercase() }.filter(String::isNotBlank).toSet()

    internal fun saveNuvioBlocked(values: Set<String>) {
        runCatching {
            prefs?.edit()?.putString(
                KEY_NUVIO_BLOCKED,
                values.map {
                    it.lowercase()
                }.distinct().joinToString("\n"),
            )?.apply()
        }
    }
    val nuvioEnabled: Set<String>
        get() = string(KEY_NUVIO_ENABLED, DEFAULT_NUVIO_ENABLED)
            .lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
    val nuvioDisabled: Set<String>
        get() = if (nuvioEnabled.any { it.equals("all", true) }) {
            nuvioEnabled.filter { it.startsWith('!') }.map { it.removePrefix("!") }.toSet()
        } else {
            GOWARU_NUVIO_IDS.filterNot(::isNuvioEnabled).toSet()
        }

    /**
     * Nombre maximal de flux par site (0 = illimité, valeur par défaut depuis la 16.13 :
     * les sites fournissent souvent 8 à 20 liens par épisode, comme dans NuviO).
     */
    val nuvioMaxPerScraper: Int
        get() = string(KEY_NUVIO_MAX, "0").toIntOrNull()?.coerceIn(0, 200) ?: 0

    /**
     * Qualités/marques exclues du résultat (chips façon NuviO, 16.17). Chaque jeton
     * est soit une qualité (`4K`, `1080P`, `720P`…), soit une marque (`HDR`, `DV`,
     * `REMUX`, `CAM`, `TS`). Vide = « Auto » (rien n'est exclu).
     */
    val nuvioQualityExcludes: Set<String>
        get() = string(KEY_NUVIO_QUALITY_EXCLUDES, "")
            .lineSequence().map { it.trim().uppercase() }.filter(String::isNotBlank).toSet()

    internal fun saveNuvioQualityExcludes(values: Set<String>) {
        runCatching {
            prefs?.edit()?.putString(
                KEY_NUVIO_QUALITY_EXCLUDES,
                values.map {
                    it.uppercase()
                }.distinct().joinToString("\n"),
            )?.apply()
        }
    }

    /** Vrai si le titre de flux correspond à l'une des qualités/marques exclues. */
    fun isQualityExcluded(title: String): Boolean {
        val excludes = nuvioQualityExcludes
        if (excludes.isEmpty()) return false
        val upper = title.uppercase()
        return excludes.any { token ->
            StreamLabel.qualityValue(token)?.let { value ->
                StreamLabel.qualityOf(title) == value
            } == true ||
                when (token) {
                    "HDR" -> Regex("\\bHDR\\b").containsMatchIn(upper)
                    "DV" -> Regex("\\bDV\\b").containsMatchIn(upper) || upper.contains("DOLBY VISION")
                    "REMUX" -> Regex("\\bREMUX\\b").containsMatchIn(upper)
                    "CAM" -> Regex("\\bCAM\\b").containsMatchIn(upper)
                    "TS" -> Regex("\\bTS\\b").containsMatchIn(upper)
                    else -> false
                }
        }
    }
    val nuvioOrder: List<String>
        get() = string(KEY_NUVIO_ORDER, RECOMMENDED_NUVIO_IDS.joinToString("\n"))
            .lineSequence().map(String::trim).filter(String::isNotBlank).toList()

    /**
     * Sites interrogés en même temps (16.17 : 3 → 6 par défaut) : plus de
     * parallélisme = les résultats des sites lents arrivent plus tôt, et
     * l'écran de serveurs se remplit plus vite.
     */
    val nuvioConcurrency: Int
        get() = string(KEY_NUVIO_CONCURRENCY, "6").toIntOrNull()?.coerceIn(2, 8) ?: 6

    /** Valeurs d'environnement saisies par l'utilisateur pour une source Nuvio. */
    fun sourceEnvValues(scraperId: String): Map<String, String> {
        val root = runCatching { JSONObject(string(KEY_NUVIO_SOURCE_ENV, "{}")) }.getOrNull() ?: return emptyMap()
        val entry = root.optJSONObject(scraperId) ?: return emptyMap()
        return entry.keys().asSequence()
            .mapNotNull { key -> key.takeIf { entry.optString(key).isNotBlank() } }
            .associateWith { entry.optString(it) }
    }

    /** Enregistre (ou efface) une valeur d'environnement pour une source Nuvio. */
    fun putSourceEnv(scraperId: String, key: String, value: String) {
        val root = runCatching { JSONObject(string(KEY_NUVIO_SOURCE_ENV, "{}")) }.getOrNull() ?: JSONObject()
        val entry = root.optJSONObject(scraperId)?.let { JSONObject(it.toString()) } ?: JSONObject()
        if (value.isBlank()) {
            entry.remove(key)
        } else {
            entry.put(key, value)
        }
        if (entry.length() == 0) {
            root.remove(scraperId)
        } else {
            root.put(scraperId, entry)
        }
        prefs?.edit()?.putString(KEY_NUVIO_SOURCE_ENV, root.toString())?.apply()
    }

    /** Mise à jour automatique (quotidienne) des manifests et scripts Nuvio. */
    val nuvioAutoUpdate: Boolean get() = bool(KEY_NUVIO_AUTO_UPDATE, true)
    val nuvioLastUpdate: Long
        get() = when (val value = any(KEY_NUVIO_LAST_UPDATE)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

    internal fun saveNuvioLastUpdate(timestamp: Long) = saveLong(KEY_NUVIO_LAST_UPDATE, timestamp)

    private fun long(key: String): Long = when (val value = any(key)) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun saveLong(key: String, timestamp: Long) {
        runCatching { prefs?.edit()?.putLong(key, timestamp)?.apply() }
    }

    /**
     * Recherche rapide : n'interroge que les catalogues rapides (TMDB et AniList) et
     * borne chaque appel, au lieu d'attendre Jikan et l'ensemble des addons Stremio.
     */
    val quickSearch: Boolean get() = bool(KEY_QUICK_SEARCH, false)

    val customQualities: List<Int>
        get() = string(KEY_CUSTOM_QUALITIES, "").split(Regex("[,\\n; ]+"))
            .mapNotNull(StreamLabel::qualityValue).filter { it in 144..8640 }.distinct()
    val backupAutoRestore: Boolean get() = bool(KEY_BACKUP_AUTO_RESTORE, false)
    val backupUrl: String get() = string(KEY_BACKUP_URL, "").trim()
    val backupLastRestore: Long get() = long(KEY_BACKUP_LAST_RESTORE)
    internal fun saveBackupLastRestore(timestamp: Long) = saveLong(KEY_BACKUP_LAST_RESTORE, timestamp)

    /**
     * Critères de flux classés avec les flèches (langues et qualités mélangées, du plus
     * au moins souhaité). Les entrées inconnues sont ignorées ; une liste vide ou absente
     * retombe sur [DEFAULT_STREAM_ORDER].
     */
    val streamOrder: List<String>
        get() = parseStreamOrder(string(KEY_STREAM_ORDER, "")).ifEmpty { DEFAULT_STREAM_ORDER }

    /** Normalise une liste de critères saisie ou enregistrée (`vf, 1080P, 4k, en` → `VF, 1080p, 4K, EN`). */
    internal fun parseStreamOrder(raw: String): List<String> = raw
        .split(Regex("[,\\n;]"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .mapNotNull { token ->
            StreamLabel.qualityValue(token)?.let(StreamLabel::qualityText)
                ?: token.uppercase().takeIf { it in StreamLabel.LANGUAGE_ORDER || it in customLanguages }
        }
        .distinct()

    /**
     * Convertit les anciens « motifs de priorité » (16.8 et antérieures) en ordre de critères.
     * Retourne `null` lorsque l'utilisateur n'avait rien personnalisé : le nouvel ordre par
     * défaut s'applique alors.
     */
    internal fun streamOrderFromLegacyPatterns(raw: String?): List<String>? {
        val patterns = raw?.split(',')?.map { it.trim().uppercase() }?.filter(String::isNotBlank) ?: return null
        if (patterns.isEmpty() || patterns == DEFAULT_NUVIO_PRIORITY) return null
        val mapped = parseStreamOrder(patterns.joinToString(","))
        if (mapped.isEmpty()) return null
        return (mapped + DEFAULT_STREAM_ORDER).distinct()
    }
    val nuvioUserAgent: String get() = string(KEY_UA, DEFAULT_USER_AGENT).trim()
    val nuvioReferer: String get() = string(KEY_REFERER, "https://www.google.com/").trim()
    val nuvioCookies: String get() = string(KEY_COOKIES, "").trim()

    /** Langue de l'interface (français par défaut, anglais en option). */
    val uiLanguage: String
        get() = string(KEY_UI_LANGUAGE, L10n.FR).lowercase()
            .takeIf { it in L10n.UI_LANGUAGE_LABELS } ?: L10n.FR

    val tmdbApiKey: String get() = string(KEY_TMDB, DEFAULT_TMDB_KEY).trim().ifBlank { DEFAULT_TMDB_KEY }

    /** Déduit : des catalogues principaux existent si TMDB ou AniList est actif. */
    val useMainCatalogs: Boolean get() = useTmdbCatalog || useAniListCatalog
    val useTmdbCatalog: Boolean get() = bool(KEY_USE_TMDB, true)
    val useAniListCatalog: Boolean get() = bool(KEY_USE_ANIME, true)
    val useJikanCatalog: Boolean get() = bool(KEY_USE_JIKAN, true)
    val useAnimeCatalog: Boolean get() = useAniListCatalog || useJikanCatalog
    val popularCatalog: String get() = string(KEY_POPULAR, "mixed")

    /**
     * Serveurs DNS personnalisés (un par ligne). Vide = DNS du système.
     * Accepte une IP (`1.1.1.1`), `IP:port`, ou une URL DoH (`https://1.1.1.1/dns-query`).
     */
    val dnsHosts: List<String>
        get() = string(KEY_DNS_HOSTS, "")
            .lineSequence()
            .map { line ->
                val value = line.trim()
                if (value.startsWith("https://", ignoreCase = true) ||
                    value.startsWith("http://", ignoreCase = true)
                ) {
                    value.trimEnd('/')
                } else {
                    value
                }
            }
            .filter(String::isNotBlank)
            .distinct()
            .take(4)
            .toList()

    val useCustomDns: Boolean get() = dnsHosts.isNotEmpty()

    /**
     * Organisation des séries à plusieurs saisons :
     *  - `classic` : une fiche puis une liste de saisons (comportement historique) ;
     *  - `merged`  : toutes les saisons fusionnées dans une seule fiche (épisodes S1E1…S2E1…) ;
     *  - `split`   : chaque saison devient une fiche distincte dès le catalogue.
     */
    val seriesLayout: String
        get() = string(KEY_SERIES_LAYOUT, "classic")
            .takeIf { it in setOf("classic", "merged", "split") } ?: "classic"

    val verifyStreamContent: Boolean get() = bool(KEY_VERIFY_STREAM_CONTENT, true)
    val tolerate403: Boolean get() = bool(KEY_NUVIO_TOLERATE_403, true)

    val apiTokens: Map<String, String>
        get() = string(KEY_TOKENS, "").lineSequence()
            .map(String::trim)
            .filter { it.contains('=') }
            .associate { line ->
                line.substringBefore('=').trim().uppercase() to line.substringAfter('=').trim()
            }

    internal fun migratedNuvioEnabled(existingEnabled: String?, oldDisabledRaw: String?): String {
        existingEnabled?.takeIf(String::isNotBlank)?.let { return it }
        val oldDisabled = oldDisabledRaw.orEmpty()
            .lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
        val usedLegacyDefault = oldDisabledRaw == null ||
            oldDisabled.isEmpty() ||
            oldDisabled == DEFAULT_NUVIO_DISABLED
        if (usedLegacyDefault) return DEFAULT_NUVIO_ENABLED
        return GOWARU_NUVIO_IDS
            .filterNot { id -> oldDisabled.any { it.equals(id, true) } || id.equals("movix", true) }
            .joinToString("\n")
    }

    fun isNuvioEnabled(id: String): Boolean {
        val values = nuvioEnabled
        val excluded = values.any { it.equals("!$id", true) }
        return !excluded &&
            (
                values.any { it.equals("all", true) } ||
                    values.any { it.equals(id, true) }
                )
    }

    fun isNuvioRepoEnabled(repo: String): Boolean = repo.trim() !in nuvioReposDisabled
    fun isStremioEnabled(url: String): Boolean = url.trim().removeSuffix("/") !in stremioDisabled
}

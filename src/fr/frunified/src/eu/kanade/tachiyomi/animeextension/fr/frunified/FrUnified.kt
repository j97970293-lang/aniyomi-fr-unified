package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Port Aniyomi de FR Unifié.
 *
 * Aniyomi isole les APK d'extensions : une extension ne peut pas appeler de façon
 * fiable les autres extensions installées. Cette version interroge donc directement
 * les catalogues TMDB/AniList/Jikan/Stremio et les moteurs Nuvio/Stremio configurés.
 */
class FrUnified : Source() {
    override val name = "FR Unifié"
    override val lang = "fr"
    override val baseUrl = "https://www.themoviedb.org"
    override val supportsLatest = true

    override val migration: SharedPreferences.() -> Unit = {
        val version = (all[FrSettings.KEY_SETTINGS_VERSION] as? Number)?.toInt() ?: 0
        if (version < FrSettings.SETTINGS_VERSION) {
            val editor = edit()
            if (version < 2 && (all[FrSettings.KEY_STREMIO] as? String).isNullOrBlank()) {
                editor.putString(FrSettings.KEY_STREMIO, FrSettings.DEFAULT_STREMIO_ADDONS.joinToString("\n"))
            }
            if (version < 3) {
                val repositories = (all[FrSettings.KEY_NUVIO_REPOS] as? String)
                    ?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.toSet()
                if (
                    repositories == null ||
                    repositories == FrSettings.LEGACY_DEFAULT_NUVIO_REPOS.toSet() ||
                    repositories == FrSettings.LEGACY_DEFAULT_NUVIO_REPOS.take(2).toSet()
                ) {
                    editor.putString(FrSettings.KEY_NUVIO_REPOS, FrSettings.DEFAULT_NUVIO_REPOS.joinToString("\n"))
                }
                if ((all[FrSettings.KEY_NUVIO_DISABLED] as? String).isNullOrBlank()) {
                    editor.putString(
                        FrSettings.KEY_NUVIO_DISABLED,
                        FrSettings.DEFAULT_NUVIO_DISABLED.joinToString("\n"),
                    )
                }
                editor.putString(FrSettings.KEY_NUVIO_SEARCH_MODE, "fast")
            }
            if (version < 4) {
                val migratedEnabled = FrSettings.migratedNuvioEnabled(
                    existingEnabled = all[FrSettings.KEY_NUVIO_ENABLED] as? String,
                    oldDisabledRaw = all[FrSettings.KEY_NUVIO_DISABLED] as? String,
                )
                editor.putString(FrSettings.KEY_NUVIO_ENABLED, migratedEnabled)

                if (!all.containsKey(FrSettings.KEY_NUVIO_ORDER)) {
                    val explicit = migratedEnabled.lineSequence()
                        .filter { it.isNotBlank() && it != "all" && !it.startsWith('!') }
                        .toList()
                    editor.putString(
                        FrSettings.KEY_NUVIO_ORDER,
                        (FrSettings.RECOMMENDED_NUVIO_IDS + explicit).distinct().joinToString("\n"),
                    )
                }
                val existingRepos = (all[FrSettings.KEY_NUVIO_REPOS] as? String)
                    .orEmpty().lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                val repos = if (existingRepos.isEmpty()) {
                    FrSettings.DEFAULT_NUVIO_REPOS
                } else {
                    (existingRepos + FrSettings.DEFAULT_NUVIO_REPOS).distinct()
                }
                editor.putString(FrSettings.KEY_NUVIO_REPOS, repos.joinToString("\n"))
                if (!all.containsKey(FrSettings.KEY_NUVIO_LANGUAGES)) {
                    val allLanguages = all[FrSettings.KEY_NUVIO_ALL] == true
                    editor.putString(FrSettings.KEY_NUVIO_LANGUAGES, if (allLanguages) "all" else "fr")
                }
                if (!all.containsKey(FrSettings.KEY_NUVIO_CONCURRENCY)) {
                    editor.putString(FrSettings.KEY_NUVIO_CONCURRENCY, "3")
                }

                val existingStremio = (all[FrSettings.KEY_STREMIO] as? String)
                    .orEmpty().lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                editor.putString(
                    FrSettings.KEY_STREMIO,
                    (existingStremio + FrSettings.DEFAULT_STREMIO_ADDONS).distinct().joinToString("\n"),
                )
                if (!all.containsKey(FrSettings.KEY_USE_STREMIO_CATALOG)) {
                    editor.putBoolean(FrSettings.KEY_USE_STREMIO_CATALOG, true)
                }
                if (!all.containsKey(FrSettings.KEY_USE_MAIN_CATALOGS)) {
                    editor.putBoolean(FrSettings.KEY_USE_MAIN_CATALOGS, true)
                }
                if (!all.containsKey(FrSettings.KEY_USE_JIKAN)) {
                    editor.putBoolean(
                        FrSettings.KEY_USE_JIKAN,
                        (all[FrSettings.KEY_USE_ANIME] as? Boolean) ?: true,
                    )
                }
                if (!all.containsKey(FrSettings.KEY_ENGINE_ORDER)) {
                    editor.putString(FrSettings.KEY_ENGINE_ORDER, "nuvio_first")
                }
                if (!all.containsKey(FrSettings.KEY_CATALOG_LANGUAGE)) {
                    editor.putString(FrSettings.KEY_CATALOG_LANGUAGE, "fr-FR")
                }
                if (!all.containsKey(FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE)) {
                    val primaryLanguage = (all[FrSettings.KEY_CATALOG_LANGUAGE] as? String)
                        ?.lineSequence()?.firstOrNull(String::isNotBlank) ?: "fr-FR"
                    editor.putString(FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE, primaryLanguage)
                }
                editor.remove("cloudstream_repos")
                editor.remove(FrSettings.KEY_NUVIO_DISABLED)
                editor.remove(FrSettings.KEY_NUVIO_ALL)
            }
            editor.putInt(FrSettings.KEY_SETTINGS_VERSION, FrSettings.SETTINGS_VERSION)
            editor.apply()
        }
    }

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        FrSettings.init(preferences)
        FrRuntime.init(client)
        NuvioClient.init(context)
        settingsScope.launch {
            // Précharge et met en cache chaque entrée catalogs[] des manifests actifs.
            runCatching { StremioCatalog.catalogs() }
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .set(
            "Accept-Language",
            FrSettings.catalogLanguages.mapIndexed { index, language ->
                if (index ==
                    0
                ) {
                    language
                } else {
                    "$language;q=${"%.1f".format(Locale.US, (0.9 - index * 0.1).coerceAtLeast(0.2))}"
                }
            }.joinToString(","),
        )

    // ----------------------------------------------- accueil et recherche

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val items = catalogItems(catalogFilterValue(), page, latest = false)
        return AnimesPage(items.map(CatalogItem::toSAnime), items.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val items = catalogItems(catalogFilterValue(), page, latest = true)
        return AnimesPage(items.map(CatalogItem::toSAnime), items.isNotEmpty())
    }

    private suspend fun catalogItems(
        type: String,
        page: Int,
        latest: Boolean,
        stremioCatalogKey: String = FrSettings.stremioCatalogKey,
        stremioExtras: Map<String, String> = emptyMap(),
    ): List<CatalogItem> {
        if (
            (!FrSettings.useMainCatalogs || (!FrSettings.useTmdbCatalog && !FrSettings.useAnimeCatalog)) &&
            type != "stremio"
        ) {
            return if (FrSettings.useStremioCatalog) {
                StremioCatalog.browse(
                    page,
                    catalogKey = stremioCatalogKey,
                    selectedExtras = stremioExtras,
                )
            } else {
                emptyList()
            }
        }
        return when (type) {
            "stremio" -> if (FrSettings.useStremioCatalog) {
                StremioCatalog.browse(
                    page,
                    catalogKey = stremioCatalogKey,
                    selectedExtras = stremioExtras,
                )
            } else {
                emptyList()
            }

            "movie" -> if (FrSettings.useTmdbCatalog) {
                TmdbCatalog.row(if (latest) "movie/now_playing" else "movie/popular", page, kind = "movie")
            } else {
                emptyList()
            }

            "tv" -> if (FrSettings.useTmdbCatalog) {
                TmdbCatalog.row(if (latest) "tv/on_the_air" else "tv/popular", page, kind = "tv")
            } else {
                emptyList()
            }

            "anime" -> if (FrSettings.useAnimeCatalog) {
                AnimeCatalog.row(if (latest) "trending" else "popular", page)
            } else {
                emptyList()
            }

            else -> coroutineScope {
                buildList {
                    if (FrSettings.useTmdbCatalog) {
                        add(
                            async {
                                if (latest) {
                                    TmdbCatalog.row("movie/now_playing", page, kind = "movie") +
                                        TmdbCatalog.row("tv/on_the_air", page, kind = "tv")
                                } else {
                                    TmdbCatalog.row("trending/all/week", page)
                                }
                            },
                        )
                    }
                    if (FrSettings.useAnimeCatalog) {
                        add(async { AnimeCatalog.row("trending", page) })
                    }
                }.awaitAll().flatten().deduplicate()
            }
        }
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = coroutineScope {
        val type = filters.filterIsInstance<ContentTypeFilter>().firstOrNull()?.value ?: catalogFilterValue()
        val stremioCatalogKey = filters.filterIsInstance<StremioCatalogFilter>()
            .firstOrNull()?.value ?: FrSettings.stremioCatalogKey
        val stremioExtras = filters.filterIsInstance<StremioExtraFilter>()
            .filter { it.catalogKey == stremioCatalogKey }
            .associate { it.extraName to it.value }
        persistCatalogFilter(type, stremioCatalogKey)
        if (query.isBlank()) {
            val items = catalogItems(type, page, latest = false, stremioCatalogKey, stremioExtras)
            return@coroutineScope AnimesPage(items.map(CatalogItem::toSAnime), items.isNotEmpty())
        }
        val useStremioOnly = type == "stremio" ||
            !FrSettings.useMainCatalogs ||
            (!FrSettings.useTmdbCatalog && !FrSettings.useAnimeCatalog)
        val jobs = buildList {
            if (FrSettings.useStremioCatalog && (useStremioOnly || type == "all")) {
                add(async { StremioCatalog.browse(page, query, stremioCatalogKey, stremioExtras) })
            }
            if (!useStremioOnly && FrSettings.useTmdbCatalog && type != "anime") {
                add(
                    async {
                        TmdbCatalog.search(query, page).filter {
                            type == "all" || it.id.kind == type
                        }
                    },
                )
            }
            if (!useStremioOnly && FrSettings.useAnimeCatalog && type in setOf("all", "anime")) {
                add(async { AnimeCatalog.search(query, page) })
            }
        }
        val items = jobs.awaitAll().flatten().deduplicate()
        AnimesPage(items.map(CatalogItem::toSAnime), items.isNotEmpty())
    }

    private fun catalogFilterValue(): String {
        if (
            (!FrSettings.useMainCatalogs || (!FrSettings.useTmdbCatalog && !FrSettings.useAnimeCatalog)) &&
            FrSettings.useStremioCatalog
        ) {
            return "stremio"
        }
        return when (FrSettings.popularCatalog) {
            "movies" -> "movie"
            "series" -> "tv"
            "anime" -> "anime"
            "stremio" -> "stremio"
            else -> "all"
        }
    }

    private fun persistCatalogFilter(type: String, stremioCatalogKey: String) {
        val preference = when (type) {
            "movie" -> "movies"
            "tv" -> "series"
            "anime" -> "anime"
            "stremio" -> "stremio"
            else -> "mixed"
        }
        if (
            FrSettings.popularCatalog != preference ||
            (stremioCatalogKey.isNotBlank() && FrSettings.stremioCatalogKey != stremioCatalogKey)
        ) {
            preferences.edit()
                .putString(FrSettings.KEY_POPULAR, preference)
                .apply {
                    if (stremioCatalogKey.isNotBlank()) {
                        putString(FrSettings.KEY_STREMIO_CATALOG, stremioCatalogKey)
                    }
                }
                .apply()
        }
    }

    private fun List<CatalogItem>.deduplicate(): List<CatalogItem> {
        val seen = hashSetOf<String>()
        return filter { seen.add("${TitleMatch.normalize(it.title)}:${it.year}") }
    }

    class ContentTypeFilter(initialState: Int) : AnimeFilter.Select<String>(
        "Catalogue affiché",
        arrayOf("Mixte", "Films", "Séries", "Animés", "Stremio"),
    ) {
        init {
            state = initialState.coerceIn(0, 4)
        }

        val value: String
            get() = arrayOf("all", "movie", "tv", "anime", "stremio")[state]
    }

    class StremioCatalogFilter(
        private val catalogs: List<StremioCatalog.Catalog>,
        selectedKey: String,
    ) : AnimeFilter.Select<String>(
        "Catalogue Stremio (chaque entrée du manifest)",
        catalogs.map(StremioCatalog.Catalog::label).toTypedArray(),
    ) {
        init {
            state = catalogs.indexOfFirst { it.key == selectedKey }
                .takeIf { it >= 0 } ?: 0
        }

        val value: String get() = catalogs.getOrNull(state)?.key.orEmpty()
    }

    class StremioExtraFilter(
        val catalogKey: String,
        val extraName: String,
        extra: StremioCatalog.Extra,
    ) : AnimeFilter.Select<String>(
        "Option Stremio · ${extraName.replaceFirstChar(Char::titlecase)}",
        (if (extra.required) extra.options else listOf("Tous") + extra.options).toTypedArray(),
    ) {
        private val extraValues = if (extra.required) extra.options else listOf("") + extra.options
        val value: String get() = extraValues.getOrNull(state).orEmpty()
    }

    override fun getFilterList(): AnimeFilterList {
        val initialState = when {
            (
                !FrSettings.useMainCatalogs ||
                    (!FrSettings.useTmdbCatalog && !FrSettings.useAnimeCatalog)
                ) &&
                FrSettings.useStremioCatalog -> 4

            FrSettings.popularCatalog == "movies" -> 1

            FrSettings.popularCatalog == "series" -> 2

            FrSettings.popularCatalog == "anime" -> 3

            FrSettings.popularCatalog == "stremio" -> 4

            else -> 0
        }
        val contentFilter = ContentTypeFilter(initialState)
        val catalogs = StremioCatalog.cachedCatalogs()
        if (catalogs.isEmpty()) return AnimeFilterList(contentFilter)

        val selected = catalogs.firstOrNull { it.key == FrSettings.stremioCatalogKey } ?: catalogs.first()
        val filters = buildList<AnimeFilter<*>> {
            add(contentFilter)
            add(
                AnimeFilter.Header(
                    "Chaque catalogs[] est détecté automatiquement ; réinitialiser les filtres après un ajout.",
                ),
            )
            add(StremioCatalogFilter(catalogs, selected.key))
            val extras = selected.extras.filter {
                it.options.isNotEmpty() &&
                    !it.name.equals("search", true) &&
                    !it.name.equals("skip", true)
            }
            if (extras.isNotEmpty()) {
                add(AnimeFilter.Header("Après un changement de catalogue : Filtrer, puis Réinitialiser les filtres."))
                extras.forEach { extra ->
                    add(StremioExtraFilter(selected.key, extra.name, extra))
                }
            }
        }
        return AnimeFilterList(filters)
    }

    // --------------------------------------------------------------- fiche

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = CatalogId.parse(anime.url) ?: return anime
        return when (id.catalog) {
            "tmdb" -> tmdbDetails(id, anime)
            "anilist" -> aniListDetails(id, anime)
            "mal" -> malDetails(id, anime)
            "stremio" -> stremioDetails(id, anime)
            else -> anime
        }
    }

    private suspend fun tmdbDetails(id: CatalogId, fallback: SAnime): SAnime {
        val details = TmdbCatalog.details(id) ?: return fallback
        val item = TmdbCatalog.toItem(details, id.kind) ?: return fallback
        return item.toSAnime().apply {
            url = id.serialize()
            genre = jsonNames(details, "genres")
            author = crewNames(details, setOf("Director", "Creator", "Executive Producer"))
            artist = castNames(details)
            status = tmdbStatus(details.optString("status"))
            fetch_type = if (id.kind == "tv") FetchType.Seasons else FetchType.Episodes
            description = detailsDescription(item, sourceSummary())
        }
    }

    private suspend fun aniListDetails(id: CatalogId, fallback: SAnime): SAnime {
        val media = AniListCatalog.details(id.id) ?: return fallback
        val item = AniListCatalog.item(media) ?: return fallback
        return item.toSAnime().apply {
            url = id.serialize()
            genre = jsonStrings(media, "genres")
            author = media.optJSONObject("studios")?.optJSONArray("nodes")?.let { nodes ->
                (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it)?.optString("name") }
                    .filter(String::isNotBlank).joinToString().ifBlank { null }
            }
            status = aniListStatus(media.optString("status"))
            fetch_type = FetchType.Episodes
            description = detailsDescription(item, sourceSummary())
        }
    }

    private suspend fun malDetails(id: CatalogId, fallback: SAnime): SAnime {
        val data = JikanCatalog.details(id.id) ?: return fallback
        val item = JikanCatalog.item(data) ?: return fallback
        return item.toSAnime().apply {
            url = id.serialize()
            genre = buildList {
                addAll(jsonObjectNames(data, "genres"))
                addAll(jsonObjectNames(data, "themes"))
                addAll(jsonObjectNames(data, "demographics"))
            }.distinct().joinToString().ifBlank { null }
            author = jsonObjectNames(data, "studios").joinToString().ifBlank { null }
            status = when {
                data.optBoolean("airing") -> SAnime.ONGOING
                data.optString("status").contains("Finished", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            fetch_type = FetchType.Episodes
            description = detailsDescription(item, sourceSummary())
        }
    }

    private suspend fun stremioDetails(id: CatalogId, fallback: SAnime): SAnime {
        val ref = StremioCatalog.Ref.parse(id.id) ?: return fallback
        val meta = StremioCatalog.meta(ref) ?: return fallback
        val item = StremioCatalog.itemFromMeta(meta, ref) ?: return fallback
        return item.toSAnime().apply {
            url = id.serialize()
            genre = jsonStrings(meta, "genres")
            author = jsonStringOrArray(meta, "director")
            artist = jsonStringOrArray(meta, "cast")
            status = if (meta.optString("releaseInfo").lastOrNull()?.isDigit() == true) {
                SAnime.COMPLETED
            } else {
                SAnime.ONGOING
            }
            fetch_type = if (ref.type.equals("movie", true) || StremioCatalog.videos(meta).isEmpty()) {
                FetchType.Episodes
            } else {
                FetchType.Seasons
            }
            description = detailsDescription(item, sourceSummary())
        }
    }

    private fun jsonStringOrArray(root: JSONObject, key: String): String? {
        val array = root.optJSONArray(key)
        if (array != null) {
            return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                .joinToString().ifBlank { null }
        }
        return root.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun detailsDescription(item: CatalogItem, suffix: String): String = buildString {
        item.overview?.takeIf(String::isNotBlank)?.let { append(it.trim()) }
        if (isNotEmpty()) append("\n\n")
        append(suffix)
    }

    private fun sourceSummary(): String = buildString {
        append("Ordre de lecture : ")
        if (FrSettings.engineOrder == "stremio_first") {
            append("Stremio puis Nuvio")
        } else {
            append("Nuvio puis Stremio")
        }
        append(". Les moteurs sont indépendants et le second sert de repli.")
    }

    // ------------------------------------------------------------- saisons

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val id = CatalogId.parse(anime.url) ?: return emptyList()
        if (id.catalog == "stremio") return stremioSeasons(id, anime)
        if (id.catalog != "tmdb" || id.kind != "tv") return emptyList()
        val details = TmdbCatalog.details(id) ?: return emptyList()
        val title = details.optString("name").ifBlank { anime.title }
        val seasons = details.optJSONArray("seasons") ?: return emptyList()
        return (0 until seasons.length()).mapNotNull { index ->
            val season = seasons.optJSONObject(index) ?: return@mapNotNull null
            val number = season.optInt("season_number", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            if (season.optInt("episode_count", 0) <= 0) return@mapNotNull null
            SAnime.create().apply {
                url = id.copy(season = number).serialize()
                this.title = if (number == 0) "$title — Épisodes spéciaux" else "$title — Saison $number"
                thumbnail_url = TmdbCatalog.image(season.optString("poster_path")) ?: anime.thumbnail_url
                background_url = anime.background_url
                description = season.optString("overview").takeIf(String::isNotBlank) ?: anime.description
                season_number = number.toDouble()
                status = anime.status
                genre = anime.genre
                author = anime.author
                artist = anime.artist
                fetch_type = FetchType.Episodes
            }
        }.sortedByDescending { it.season_number }
    }

    private suspend fun stremioSeasons(id: CatalogId, anime: SAnime): List<SAnime> {
        val ref = StremioCatalog.Ref.parse(id.id) ?: return emptyList()
        if (ref.type.equals("movie", true)) return emptyList()
        val meta = StremioCatalog.meta(ref) ?: return emptyList()
        val seasons = StremioCatalog.videos(meta)
            .mapNotNull { video -> video.optInt("season", -1).takeIf { it >= 0 } }
            .distinct()
        return seasons.map { number ->
            SAnime.create().apply {
                url = id.copy(season = number).serialize()
                title = if (number == 0) {
                    "${anime.title} — Épisodes spéciaux"
                } else {
                    "${anime.title} — Saison $number"
                }
                thumbnail_url = anime.thumbnail_url
                background_url = anime.background_url
                description = anime.description
                season_number = number.toDouble()
                status = anime.status
                genre = anime.genre
                author = anime.author
                artist = anime.artist
                fetch_type = FetchType.Episodes
            }
        }.sortedByDescending { it.season_number }
    }

    // ------------------------------------------------------------- épisodes

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = CatalogId.parse(anime.url) ?: return emptyList()
        return when (id.catalog) {
            "tmdb" -> tmdbEpisodes(id)
            "anilist" -> aniListEpisodes(id)
            "mal" -> malEpisodes(id)
            "stremio" -> stremioEpisodes(id)
            else -> emptyList()
        }
    }

    private suspend fun tmdbEpisodes(id: CatalogId): List<SEpisode> {
        val details = TmdbCatalog.details(id) ?: return emptyList()
        val item = TmdbCatalog.toItem(details, id.kind) ?: return emptyList()
        val titles = (item.titles + TmdbCatalog.alternativeTitles(id)).distinctBy(TitleMatch::normalize)
        val imdb = details.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
        if (id.kind == "movie") {
            val payload = PlayPayload(
                kind = "movie",
                titles = titles,
                year = item.year,
                tmdbId = id.id.toIntOrNull(),
                imdbId = imdb,
            )
            return listOf(
                SEpisode.create().apply {
                    url = payload.serialize()
                    name = "Film"
                    episode_number = 1F
                    scanlator = "FR Unifié"
                    summary = item.overview
                    preview_url = item.backdropUrl ?: item.posterUrl
                },
            )
        }

        val seasonNumber = id.season ?: 1
        val season = TmdbCatalog.season(id.id, seasonNumber) ?: return emptyList()
        val episodes = season.optJSONArray("episodes") ?: return emptyList()
        return (0 until episodes.length()).mapNotNull { index ->
            val episode = episodes.optJSONObject(index) ?: return@mapNotNull null
            val number = episode.optInt("episode_number", index + 1)
            val payload = PlayPayload(
                kind = "tv",
                titles = titles,
                year = item.year,
                season = seasonNumber,
                episode = number,
                tmdbId = id.id.toIntOrNull(),
                imdbId = imdb,
            )
            SEpisode.create().apply {
                url = payload.serialize()
                name = episode.optString("name").takeIf(String::isNotBlank)?.let { "Épisode $number — $it" }
                    ?: "Épisode $number"
                episode_number = number.toFloat()
                scanlator = if (seasonNumber == 0) "Spécial" else "Saison $seasonNumber"
                summary = episode.optString("overview").takeIf(String::isNotBlank)
                preview_url = TmdbCatalog.image(episode.optString("still_path"), "w500")
                date_upload = parseDate(episode.optString("air_date"))
            }
        }.sortedByDescending { it.episode_number }
    }

    private suspend fun aniListEpisodes(id: CatalogId): List<SEpisode> {
        val media = AniListCatalog.details(id.id) ?: return emptyList()
        val item = AniListCatalog.item(media) ?: return emptyList()
        val titles = AniListCatalog.allTitles(media).ifEmpty { item.titles }
        val mal = media.optInt("idMal").takeIf { it > 0 }
        val isMovie = media.optString("format") == "MOVIE"
        val count = if (isMovie) {
            1
        } else {
            media.optInt("episodes").takeIf { it > 0 }
                ?: media.optJSONObject("nextAiringEpisode")?.optInt("episode")
                    ?.minus(1)?.takeIf { it > 0 }
                ?: mal?.let { JikanCatalog.episodeCount(it.toString()) }
                ?: return emptyList()
        }
        return genericAnimeEpisodes(
            titles = titles,
            year = item.year,
            count = count,
            isMovie = isMovie,
            anilistId = id.id.toIntOrNull(),
            malId = mal,
        )
    }

    private suspend fun malEpisodes(id: CatalogId): List<SEpisode> {
        val data = JikanCatalog.details(id.id) ?: return emptyList()
        val item = JikanCatalog.item(data) ?: return emptyList()
        val isMovie = data.optString("type").equals("Movie", true)
        val count = if (isMovie) {
            1
        } else {
            data.optInt("episodes").takeIf { it > 0 }
                ?: JikanCatalog.episodeCount(id.id)
                ?: return emptyList()
        }
        return genericAnimeEpisodes(
            titles = JikanCatalog.allTitles(data).ifEmpty { item.titles },
            year = item.year,
            count = count,
            isMovie = isMovie,
            anilistId = null,
            malId = id.id.toIntOrNull(),
        )
    }

    private suspend fun stremioEpisodes(id: CatalogId): List<SEpisode> {
        val ref = StremioCatalog.Ref.parse(id.id) ?: return emptyList()
        val meta = StremioCatalog.meta(ref) ?: return emptyList()
        val item = StremioCatalog.itemFromMeta(meta, ref)
        val titles = listOfNotNull(meta.optString("name").takeIf(String::isNotBlank), item?.title).distinct()
        val isAnime = jsonStrings(meta, "genres").orEmpty().contains("anim", true)
        if (ref.type.equals("movie", true)) {
            val payload = stremioPayload(ref, ref.id, titles, item?.year, null, null, null, isAnime)
            return listOf(
                SEpisode.create().apply {
                    url = payload.serialize()
                    name = "Film"
                    episode_number = 1F
                    scanlator = "Stremio / Nuvio"
                    summary = item?.overview
                    preview_url = item?.backdropUrl ?: item?.posterUrl
                },
            )
        }

        val videos = StremioCatalog.videos(meta)
        if (videos.isEmpty()) {
            val payload = stremioPayload(ref, ref.id, titles, item?.year, id.season ?: 1, 1, 1, isAnime)
            return listOf(
                SEpisode.create().apply {
                    url = payload.serialize()
                    name = "Flux TV"
                    episode_number = 1F
                    scanlator = "Stremio / Nuvio"
                },
            )
        }
        val selectedSeason = id.season
        val absoluteNumbers = if (isAnime) {
            videos.filter { it.optInt("season", 0) > 0 }
                .sortedWith(compareBy<JSONObject> { it.optInt("season", 1) }.thenBy { it.optInt("episode", 1) })
                .mapIndexedNotNull { index, video ->
                    video.optString("id").takeIf(String::isNotBlank)?.let { it to index + 1 }
                }.toMap()
        } else {
            emptyMap()
        }
        return videos.filter { video ->
            selectedSeason == null || video.optInt("season", 0) == selectedSeason
        }.mapNotNull { video ->
            val streamId = video.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val season = video.optInt("season", selectedSeason ?: 1)
            val episode = video.optInt("episode", 1).coerceAtLeast(1)
            val payload = stremioPayload(
                ref,
                streamId,
                titles,
                item?.year,
                season,
                episode,
                absoluteNumbers[streamId] ?: episode,
                isAnime,
            )
            SEpisode.create().apply {
                url = payload.serialize()
                val episodeTitle = video.optString("title").ifBlank { video.optString("name") }
                name = if (episodeTitle.isBlank()) {
                    "Épisode $episode"
                } else {
                    "Épisode $episode — $episodeTitle"
                }
                episode_number = episode.toFloat()
                scanlator = if (season == 0) "Spécial" else "Saison $season"
                summary = video.optString("overview").ifBlank { video.optString("description") }
                    .takeIf(String::isNotBlank)
                preview_url = video.optString("thumbnail").takeIf { it.startsWith("http") }
                date_upload = parseDate(video.optString("released").take(10))
            }
        }.sortedByDescending { it.episode_number }
    }

    private fun stremioPayload(
        ref: StremioCatalog.Ref,
        streamId: String,
        titles: List<String>,
        year: Int?,
        season: Int?,
        episode: Int?,
        absoluteEpisode: Int?,
        isAnime: Boolean,
    ): PlayPayload {
        val baseId = streamId.substringBefore(':')
        return PlayPayload(
            kind = when {
                ref.type.equals("movie", true) -> "movie"
                isAnime -> "anime"
                else -> "tv"
            },
            titles = titles,
            year = year,
            season = season,
            episode = episode,
            absoluteEpisode = absoluteEpisode,
            tmdbId = ref.id.removePrefix("tmdb:").toIntOrNull(),
            imdbId = baseId.takeIf { it.startsWith("tt") },
            stremioType = ref.type,
            stremioId = streamId,
            stremioAddon = ref.addonBase,
            stremioMetaId = ref.id,
        )
    }

    private fun genericAnimeEpisodes(
        titles: List<String>,
        year: Int?,
        count: Int,
        isMovie: Boolean,
        anilistId: Int?,
        malId: Int?,
    ): List<SEpisode> = (1..count).map { number ->
        val payload = PlayPayload(
            kind = if (isMovie) "movie" else "anime",
            titles = titles,
            year = year,
            season = if (isMovie) null else 1,
            episode = if (isMovie) null else number,
            absoluteEpisode = if (isMovie) null else number,
            anilistId = anilistId,
            malId = malId,
        )
        SEpisode.create().apply {
            url = payload.serialize()
            name = if (isMovie) "Film" else "Épisode $number"
            episode_number = number.toFloat()
            scanlator = if (isMovie) "Film animé" else "VF / VOSTFR"
        }
    }.sortedByDescending { it.episode_number }

    // --------------------------------------------------------------- liens

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = coroutineScope {
        val payload = PlayPayload.parse(episode.url) ?: return@coroutineScope emptyList()

        // Nuvio et les manifests Stremio sont découverts en parallèle. Les hosters
        // Stremio sont volontairement paresseux : le flux n'est demandé qu'au clic,
        // ce qui les rend visibles même lorsque Nuvio est complètement désactivé.
        val nuvioJob = async {
            if (!FrSettings.useNuvio) {
                emptyList()
            } else {
                val found = java.util.concurrent.CopyOnWriteArrayList<Video>()
                runCatching { NuvioClient.streams(payload) { found += it } }
                found.toList()
            }
        }
        val stremioJob = async {
            runCatching { StremioClient.hosters(payload) }.getOrDefault(emptyList())
        }
        val nuvioVideos = nuvioJob.await()
        val stremioHosters = stremioJob.await()
        val tracks = if (nuvioVideos.isEmpty()) {
            emptyList()
        } else {
            // Les sous-titres ne doivent jamais faire expirer les URL signées.
            withTimeoutOrNull(3_000L) {
                runCatching { StremioClient.subtitles(payload) }.getOrDefault(emptyList())
            }.orEmpty()
        }
        val nuvioHosters = videosToHosters(nuvioVideos, tracks, "Nuvio")

        if (FrSettings.engineOrder == "stremio_first") {
            stremioHosters + nuvioHosters
        } else {
            nuvioHosters + stremioHosters
        }
    }

    private fun videosToHosters(
        videos: List<Video>,
        tracks: List<Track> = emptyList(),
        engineName: String,
    ): List<Hoster> {
        val prepared = videos
            .distinctBy { "${it.videoUrl}|${it.videoTitle}" }
            .map { video ->
                if (tracks.isEmpty()) {
                    video
                } else {
                    video.copy(
                        subtitleTracks = (video.subtitleTracks + tracks).distinctBy { "${it.lang}|${it.url}" },
                    )
                }
            }
            .sortedWith(videoComparator())

        return prepared.groupBy { video ->
            video.videoTitle.substringBefore(" • ").substringBefore(" · ").ifBlank { "FR Unifié" }
        }.map { (provider, providerVideos) ->
            Hoster(
                hosterUrl = "frunified://${provider.hashCode()}",
                hosterName = "$engineName · $provider",
                videoList = providerVideos,
            )
        }.sortedWith(hosterComparator())
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = if (StremioClient.isLazyHoster(hoster)) {
        runCatching { StremioClient.streams(hoster) }.getOrDefault(emptyList()).sortedWith(videoComparator())
    } else {
        hoster.videoList.orEmpty().sortedWith(videoComparator())
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedWith(hosterComparator())

    override fun List<Video>.sortVideos(): List<Video> = sortedWith(videoComparator())

    private fun videoComparator(): Comparator<Video> =
        compareBy<Video> { StremioClient.priorityRank(it.videoTitle) }
            .thenByDescending { it.preferred }
            .thenByDescending { it.resolution ?: 0 }

    private fun hosterComparator(): Comparator<Hoster> =
        compareBy<Hoster> { hoster ->
            val stremio = hoster.hosterName.startsWith("Stremio ·")
            if (FrSettings.engineOrder == "stremio_first") {
                if (stremio) 0 else 1
            } else {
                if (stremio) 1 else 0
            }
        }.thenBy { hoster ->
            minOf(
                StremioClient.priorityRank(hoster.hosterName),
                hoster.videoList.orEmpty().minOfOrNull { StremioClient.priorityRank(it.videoTitle) }
                    ?: Int.MAX_VALUE,
            )
        }.thenBy { it.hosterName.lowercase() }

    override fun getAnimeUrl(anime: SAnime): String {
        val id = CatalogId.parse(anime.url) ?: return baseUrl
        return when (id.catalog) {
            "tmdb" -> "https://www.themoviedb.org/${if (id.kind == "tv") "tv" else "movie"}/${id.id}"
            "anilist" -> "https://anilist.co/anime/${id.id}"
            "mal" -> "https://myanimelist.net/anime/${id.id}"
            else -> baseUrl
        }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl

    // ---------------------------------------------------------- préférences

    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        switch(
            FrSettings.KEY_USE_MAIN_CATALOGS,
            true,
            "1 · CATALOGUES PRINCIPAUX — activer",
            "Désactiver pour utiliser uniquement le catalogue Stremio choisi",
        )
        switch(FrSettings.KEY_USE_TMDB, true, "1 · TMDB — films et séries", "Catalogue principal localisé")
        switch(
            FrSettings.KEY_USE_ANIME,
            true,
            "1 · ANILIST — animés",
            "Peut être désactivé indépendamment des autres catalogues",
        )
        switch(
            FrSettings.KEY_USE_JIKAN,
            true,
            "1 · JIKAN / MYANIMELIST — animés",
            "Repli catalogue et comptage paginé des séries toujours en cours",
        )
        switch(
            FrSettings.KEY_USE_STREMIO_CATALOG,
            true,
            "1 · CATALOGUE STREMIO — activer",
            "Catalogues, fiches et épisodes indépendants fournis par les addons",
        )
        action(
            "action_stremio_catalog",
            "1 · CATALOGUE STREMIO — choisir",
            "Charge les manifests actifs et sélectionne une rangée Stremio.",
        ) { showStremioCatalogPicker(context) }
        list(
            FrSettings.KEY_POPULAR,
            "mixed",
            "1 · Catalogue affiché (Populaires + Derniers)",
            arrayOf("Mixte", "Films", "Séries", "Animés", "Stremio"),
            arrayOf("mixed", "movies", "series", "anime", "stremio"),
        )
        action(
            "action_catalog_languages",
            "1 · Langues des catalogues et fiches",
            "Choix multiple pour TMDB et les catalogues Stremio localisés.",
        ) { showCatalogLanguagePicker(context) }
        list(
            FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE,
            "fr-FR",
            "1 · Langue principale",
            FrSettings.CATALOG_LANGUAGE_LABELS.values.toTypedArray(),
            FrSettings.CATALOG_LANGUAGE_LABELS.keys.toTypedArray(),
        )
        edit(
            FrSettings.KEY_TMDB,
            FrSettings.DEFAULT_TMDB_KEY,
            "1 · Clé API TMDB",
            "Laisser la valeur proposée ou saisir votre clé v3.",
            multiline = false,
        )

        list(
            FrSettings.KEY_ENGINE_ORDER,
            "nuvio_first",
            "2 · Ordre des moteurs de lecture",
            arrayOf("Nuvio puis Stremio", "Stremio puis Nuvio"),
            arrayOf("nuvio_first", "stremio_first"),
        )

        switch(
            FrSettings.KEY_USE_NUVIO,
            true,
            "3 · NUVIO — activer",
            "Scrapeurs intégrés ; chaque source reste sélectionnable",
        )
        action(
            "action_nuvio_sources",
            "3 · NUVIO — choisir les sources",
            "Sources françaises et internationales, activables individuellement.",
        ) { showNuvioPicker(context) }
        action(
            "action_nuvio_languages",
            "3 · NUVIO — langues des serveurs",
            "Filtre les sources exécutées ; le sélecteur affiche tout le contenu des dépôts.",
        ) { showNuvioLanguagePicker(context) }
        action(
            "action_nuvio_diagnostic",
            "3 · NUVIO — diagnostic réel",
            "Teste Rhino et plusieurs sources sur un vrai titre.",
        ) { showNuvioDiagnostic(context) }
        action(
            "action_nuvio_add",
            "3 · NUVIO — ajouter un dépôt",
            "Seul endroit où coller une URL Nuvio (manifest avec scrapers[]).",
        ) { showExternalSourceDialog(context, ExternalSourceImporter.Kind.NUVIO) }
        edit(
            FrSettings.KEY_NUVIO_ORDER,
            FrSettings.RECOMMENDED_NUVIO_IDS.joinToString("\n"),
            "3 · Ordre de priorité des serveurs Nuvio",
            "Un identifiant par ligne. Les sources absentes sont ajoutées ensuite.",
        )
        edit(
            FrSettings.KEY_NUVIO_PRIORITY,
            FrSettings.DEFAULT_NUVIO_PRIORITY.joinToString(","),
            "3 · Priorités des flux",
            "Motifs ordonnés (serveur, VF, VOSTFR, qualité), séparés par des virgules.",
            multiline = false,
        )
        list(
            FrSettings.KEY_NUVIO_CONCURRENCY,
            "3",
            "3 · Scrapeurs simultanés",
            arrayOf("2 — prudent", "3 — recommandé", "4 — rapide"),
            arrayOf("2", "3", "4"),
        )
        list(
            FrSettings.KEY_NUVIO_SEARCH_MODE,
            "fast",
            "3 · Nuvio — mode de recherche",
            arrayOf(
                "Rapide — premier serveur VF valide",
                "Équilibré — deux sources et VF prioritaire",
                "Complet — toutes les sources actives (lent)",
            ),
            arrayOf("fast", "balanced", "complete"),
        )
        list(
            FrSettings.KEY_NUVIO_MAX,
            "4",
            "3 · Nuvio — flux maximum par source",
            arrayOf("2", "4", "8", "12", "Illimité"),
            arrayOf("2", "4", "8", "12", "0"),
        )

        switch(
            FrSettings.KEY_USE_STREMIO,
            true,
            "4 · STREMIO — lecture activée",
            "Serveurs affichés même sans Nuvio, puis classés selon l’ordre choisi",
        )
        action(
            "action_stremio_sources",
            "4 · STREMIO — choisir les addons",
            "Addons de catalogue, métadonnées, flux et sous-titres.",
        ) { showStremioPicker(context) }
        action(
            "action_stremio_add",
            "4 · STREMIO — ajouter un addon",
            "Accepte aussi les manifests catalogue/meta sans ressource stream.",
        ) { showExternalSourceDialog(context, ExternalSourceImporter.Kind.STREMIO) }
        list(
            FrSettings.KEY_STREMIO_MAX,
            "8",
            "4 · Stremio — flux maximum",
            arrayOf("4", "8", "12", "20", "Illimité"),
            arrayOf("4", "8", "12", "20", "0"),
        )
        switch(FrSettings.KEY_USE_SUBS, true, "4 · Sous-titres externes", "Inclut OpenSubtitles v3")
        edit(
            FrSettings.KEY_SUB_LANGS,
            "fre,fra,fr,eng,en",
            "4 · Langues des sous-titres",
            "Codes séparés par des virgules.",
            multiline = false,
        )

        edit(
            FrSettings.KEY_TOKENS,
            "",
            "5 · Avancé — clés API Nuvio",
            "Une ligne NOM=valeur ; injectée uniquement dans process.env du provider.",
        )
        edit(
            FrSettings.KEY_UA,
            FrSettings.DEFAULT_USER_AGENT,
            "5 · Avancé — User-Agent Nuvio",
            "User-Agent des requêtes JS et des contrôles de flux.",
            multiline = false,
        )
        edit(
            FrSettings.KEY_REFERER,
            "https://www.google.com/",
            "5 · Avancé — Referer Nuvio",
            "Referer HTTP par défaut utilisé par les providers.",
            multiline = false,
        )
        edit(
            FrSettings.KEY_COOKIES,
            "",
            "5 · Avancé — cookies Nuvio",
            "Cookies facultatifs pour les domaines protégés.",
        )
    }

    private data class SourceChoice(
        val label: String,
        val value: String,
        val enabled: Boolean,
    )

    private fun showCatalogLanguagePicker(dialogContext: Context) {
        val values = FrSettings.CATALOG_LANGUAGE_LABELS.keys.toList()
        val labels = FrSettings.CATALOG_LANGUAGE_LABELS.values.toTypedArray()
        val checked = BooleanArray(values.size) { values[it] in FrSettings.catalogLanguages }
        AlertDialog.Builder(dialogContext)
            .setTitle("Langues des catalogues")
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val selected = values.indices.filter { checked[it] }.map { values[it] }
                    .ifEmpty { listOf(FrSettings.catalogLanguage) }
                val primary = FrSettings.catalogLanguage.takeIf { it in selected } ?: selected.first()
                preferences.edit()
                    .putString(FrSettings.KEY_CATALOG_LANGUAGE, selected.joinToString("\n"))
                    .putString(FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE, primary)
                    .apply()
                displayToast("Catalogues : ${selected.size} langue(s)")
            }
            .show()
    }

    private fun showNuvioPicker(dialogContext: Context) {
        displayToast("Chargement des sources Nuvio…")
        settingsScope.launch {
            val nuvioResult = runCatching { NuvioClient.scrapers(includeDisabled = true) }
            val diagnostics = NuvioClient.diagnostics()
            val choices = nuvioResult.getOrDefault(emptyList()).map { scraper ->
                val origin = scraper.repoBase.substringAfter("githubusercontent.com/")
                    .split('/').take(2).joinToString("/").takeIf(String::isNotBlank)
                    ?: scraper.repoBase.substringAfter("://").substringBefore('/')
                val recommendation = if (scraper.id in FrSettings.RECOMMENDED_NUVIO_IDS) " ★ conseillée" else ""
                val status = diagnostics[scraper.id]?.let { " · ${it.take(45)}" }.orEmpty()
                SourceChoice(
                    label = "${scraper.name}$recommendation · $origin$status",
                    value = scraper.id,
                    enabled = FrSettings.isNuvioEnabled(scraper.id),
                )
            }
            handler.post {
                if (choices.isEmpty()) {
                    displayToast(
                        nuvioResult.exceptionOrNull()?.message?.let { "Nuvio indisponible : ${it.take(100)}" }
                            ?: "Aucun scrapeur Nuvio configuré",
                        Toast.LENGTH_LONG,
                    )
                    return@post
                }
                val checked = BooleanArray(choices.size) { choices[it].enabled }
                var wildcardMode = FrSettings.nuvioEnabled.any { it.equals("all", true) }
                val dialog = AlertDialog.Builder(dialogContext)
                    .setTitle("Nuvio (${checked.count { it }}/${choices.size} actives)")
                    .setMultiChoiceItems(
                        choices.map(SourceChoice::label).toTypedArray(),
                        checked,
                    ) { _, index, value -> checked[index] = value }
                    .setNegativeButton("Annuler", null)
                    .setNeutralButton("Conseillées", null)
                    .setPositiveButton("Enregistrer") { _, _ ->
                        val visible = choices.map { it.value.lowercase() }.toSet()
                        val enabled = if (wildcardMode) {
                            val oldHiddenExclusions = FrSettings.nuvioEnabled
                                .filter { it.startsWith('!') && it.removePrefix("!").lowercase() !in visible }
                            listOf("all") +
                                oldHiddenExclusions +
                                choices.indices.filter { !checked[it] }.map { "!${choices[it].value}" }
                        } else {
                            val oldHiddenExplicit = FrSettings.nuvioEnabled.filter { value ->
                                value != "all" && !value.startsWith('!') && value.lowercase() !in visible
                            }
                            oldHiddenExplicit + choices.indices.filter { checked[it] }.map { choices[it].value }
                        }
                        val checkedIds = choices.indices.filter { checked[it] }.map { choices[it].value }
                        val selectedOrder = FrSettings.nuvioOrder.mapNotNull { ordered ->
                            checkedIds.firstOrNull { it.equals(ordered, true) }
                        } +
                            checkedIds
                        preferences.edit()
                            .putString(
                                FrSettings.KEY_NUVIO_ENABLED,
                                enabled.distinctBy(String::lowercase).joinToString("\n"),
                            )
                            .putString(
                                FrSettings.KEY_NUVIO_ORDER,
                                (selectedOrder + FrSettings.nuvioOrder)
                                    .distinctBy(String::lowercase)
                                    .joinToString("\n"),
                            )
                            .apply()
                        displayToast("Nuvio : ${checked.count { it }} source(s) active(s)")
                    }
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        wildcardMode = false
                        choices.indices.forEach { index ->
                            checked[index] = FrSettings.RECOMMENDED_NUVIO_IDS.any {
                                it.equals(choices[index].value, true)
                            }
                            dialog.listView.setItemChecked(index, checked[index])
                        }
                    }
                }
                runCatching { dialog.show() }
                    .onFailure { displayToast("Impossible d'ouvrir le sélecteur Nuvio", Toast.LENGTH_LONG) }
            }
        }
    }

    private fun showNuvioLanguagePicker(dialogContext: Context) {
        val values = arrayOf(
            "fr", "en", "es", "de", "it", "pt", "ja", "hi", "tr", "id", "pl", "ar", "ta", "te", "ml", "kn", "all",
        )
        val labels = arrayOf(
            "Français",
            "English",
            "Español",
            "Deutsch",
            "Italiano",
            "Português",
            "日本語",
            "हिन्दी",
            "Türkçe",
            "Bahasa Indonesia",
            "Polski",
            "العربية",
            "தமிழ்",
            "తెలుగు",
            "മലയാളം",
            "ಕನ್ನಡ",
            "Toutes les langues",
        )
        val checked = BooleanArray(values.size) { values[it] in FrSettings.nuvioLanguages }
        AlertDialog.Builder(dialogContext)
            .setTitle("Langues des sources Nuvio")
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val selected = values.indices.filter { checked[it] }.map { values[it] }
                    .ifEmpty { listOf("fr") }
                preferences.edit()
                    .putString(FrSettings.KEY_NUVIO_LANGUAGES, selected.joinToString("\n"))
                    .apply()
                displayToast("Nuvio : langues ${selected.joinToString()}")
            }
            .show()
    }

    private fun showNuvioDiagnostic(dialogContext: Context) {
        displayToast("Diagnostic Nuvio réel en cours (jusqu’à cinq sources)…", Toast.LENGTH_LONG)
        settingsScope.launch {
            val report = runCatching {
                val engine = NuvioClient.engineStatus()
                val all = NuvioClient.scrapers(includeDisabled = true)
                val active = all.filter { FrSettings.isNuvioEnabled(it.id) }
                    .sortedWith(
                        compareBy<NuvioClient.NuvioScraper> {
                            FrSettings.RECOMMENDED_NUVIO_IDS.indexOf(it.id)
                                .let { index -> if (index < 0) Int.MAX_VALUE else index }
                        }.thenBy { it.name.lowercase() },
                    )
                val tested = active.take(5)
                val checks = tested.map { scraper ->
                    scraper to runCatching { NuvioClient.testProvider(scraper.id) }
                }
                buildString {
                    appendLine(engine)
                    appendLine()
                    appendLine("Dépôts : ${FrSettings.nuvioRepos.size}")
                    appendLine("Scrapeurs détectés : ${all.size}")
                    appendLine("Scrapeurs actifs : ${active.size}")
                    appendLine("Mode : ${FrSettings.nuvioSearchMode}")
                    if (checks.isEmpty()) {
                        appendLine()
                        append("Aucune source active à tester.")
                    } else {
                        appendLine()
                        appendLine("Tests réseau Android/Rhino :")
                        checks.forEach { (scraper, outcome) ->
                            val result = outcome.getOrElse { failure ->
                                "✗ ${failure.message?.take(160) ?: failure::class.simpleName.orEmpty()}"
                            }
                            appendLine("• ${scraper.name} : $result")
                        }
                    }
                }.trim()
            }
            handler.post {
                AlertDialog.Builder(dialogContext)
                    .setTitle(if (report.isSuccess) "Diagnostic Nuvio" else "Diagnostic Nuvio impossible")
                    .setMessage(
                        report.getOrElse { failure ->
                            failure.message?.take(700) ?: "Erreur inconnue"
                        },
                    )
                    .setNegativeButton("Fermer", null)
                    .setPositiveButton("Choisir les sources") { _, _ -> showNuvioPicker(dialogContext) }
                    .show()
            }
        }
    }

    private fun showStremioCatalogPicker(dialogContext: Context) {
        displayToast("Chargement des catalogues Stremio…")
        settingsScope.launch {
            val result = runCatching { StremioCatalog.catalogs() }
            val catalogs = result.getOrDefault(emptyList())
            val selected = runCatching { StremioCatalog.selectedCatalog() }.getOrNull()
            handler.post {
                if (catalogs.isEmpty()) {
                    displayToast(
                        result.exceptionOrNull()?.message?.let { "Catalogues Stremio indisponibles : ${it.take(100)}" }
                            ?: "Aucun addon actif n'expose de catalogue",
                        Toast.LENGTH_LONG,
                    )
                    return@post
                }
                val selectedIndex = catalogs.indexOfFirst { it.key == selected?.key }.coerceAtLeast(0)
                AlertDialog.Builder(dialogContext)
                    .setTitle("Catalogue Stremio (${catalogs.size})")
                    .setSingleChoiceItems(
                        catalogs.map(StremioCatalog.Catalog::label).toTypedArray(),
                        selectedIndex,
                    ) { dialog, index ->
                        preferences.edit()
                            .putString(FrSettings.KEY_STREMIO_CATALOG, catalogs[index].key)
                            .apply()
                        displayToast("Catalogue : ${catalogs[index].label}")
                        dialog.dismiss()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        }
    }

    private fun showStremioPicker(dialogContext: Context) {
        val choices = FrSettings.stremioUrls.distinct().map { addon ->
            val clean = StremioClient.base(addon)
            val display = clean.substringAfter("://").removeSuffix("/manifest.json").take(90)
            SourceChoice(display, clean, FrSettings.isStremioEnabled(clean))
        }
        if (choices.isEmpty()) {
            displayToast("Aucun addon Stremio configuré", Toast.LENGTH_LONG)
            return
        }
        val checked = BooleanArray(choices.size) { choices[it].enabled }
        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("Stremio (${checked.count { it }}/${choices.size} actifs)")
            .setMultiChoiceItems(
                choices.map(SourceChoice::label).toTypedArray(),
                checked,
            ) { _, index, value -> checked[index] = value }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Tout activer", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                val visible = choices.map(SourceChoice::value).toSet()
                val disabled = FrSettings.stremioDisabled.filterNot(visible::contains) +
                    choices.indices.filter { !checked[it] }.map { choices[it].value }
                preferences.edit()
                    .putString(FrSettings.KEY_STREMIO_DISABLED, disabled.distinct().joinToString("\n"))
                    .apply()
                displayToast("Stremio : ${checked.count { it }} addon(s) actif(s)")
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                checked.indices.forEach { index ->
                    checked[index] = true
                    dialog.listView.setItemChecked(index, true)
                }
            }
        }
        runCatching { dialog.show() }
            .onFailure { displayToast("Impossible d'ouvrir le sélecteur Stremio", Toast.LENGTH_LONG) }
    }

    private fun showExternalSourceDialog(
        dialogContext: Context,
        expectedKind: ExternalSourceImporter.Kind,
    ) {
        val kindName = externalKindName(expectedKind)
        val input = EditText(dialogContext).apply {
            hint = when (expectedKind) {
                ExternalSourceImporter.Kind.NUVIO -> "https://…/manifest.json"
                ExternalSourceImporter.Kind.STREMIO -> "https://…/manifest.json"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
        }
        AlertDialog.Builder(dialogContext)
            .setTitle("Ajouter — $kindName")
            .setMessage("Cette entrée accepte uniquement le format $kindName.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Analyser") { _, _ ->
                importExternalSource(dialogContext, input.text.toString(), expectedKind)
            }
            .show()
    }

    private fun importExternalSource(
        dialogContext: Context,
        input: String,
        expectedKind: ExternalSourceImporter.Kind,
    ) {
        if (input.isBlank()) {
            displayToast("Saisissez une URL", Toast.LENGTH_LONG)
            return
        }
        displayToast("Analyse ${externalKindName(expectedKind)}…")
        settingsScope.launch {
            val outcome = runCatching {
                val result = ExternalSourceImporter.inspect(input)
                require(result.kind == expectedKind) {
                    "Format ${externalKindName(result.kind)} détecté. " +
                        "Ajoutez cette URL depuis la section ${externalKindName(result.kind)}."
                }
                result
            }
            outcome.onSuccess(::persistExternalSource)
            handler.post {
                outcome.onSuccess { result ->
                    AlertDialog.Builder(dialogContext)
                        .setTitle("${externalKindName(result.kind)} ajouté")
                        .setMessage(result.summary)
                        .setNegativeButton("Fermer", null)
                        .setPositiveButton("Ouvrir la section") { _, _ ->
                            when (result.kind) {
                                ExternalSourceImporter.Kind.NUVIO -> showNuvioPicker(dialogContext)
                                ExternalSourceImporter.Kind.STREMIO -> showStremioPicker(dialogContext)
                            }
                        }
                        .show()
                }.onFailure { failure ->
                    AlertDialog.Builder(dialogContext)
                        .setTitle("Import impossible")
                        .setMessage(failure.message?.take(500) ?: "Format non reconnu")
                        .setPositiveButton("Fermer", null)
                        .show()
                }
            }
        }
    }

    private fun externalKindName(kind: ExternalSourceImporter.Kind): String = when (kind) {
        ExternalSourceImporter.Kind.NUVIO -> "Nuvio"
        ExternalSourceImporter.Kind.STREMIO -> "Stremio"
    }

    private fun persistExternalSource(result: ExternalSourceImporter.ImportResult) {
        val editor = preferences.edit()
        when (result.kind) {
            ExternalSourceImporter.Kind.NUVIO -> {
                val values = (FrSettings.nuvioRepos + result.url).distinct()
                editor.putString(FrSettings.KEY_NUVIO_REPOS, values.joinToString("\n"))
            }

            ExternalSourceImporter.Kind.STREMIO -> {
                val values = (FrSettings.stremioUrls + StremioClient.base(result.url)).distinct()
                editor.putString(FrSettings.KEY_STREMIO, values.joinToString("\n"))
            }
        }
        editor.apply()
        if (result.kind == ExternalSourceImporter.Kind.NUVIO) {
            NuvioClient.invalidateRepository(result.url)
        }
    }

    private fun PreferenceScreen.action(
        key: String,
        title: String,
        summary: String,
        action: () -> Unit,
    ) {
        addPreference(
            ActionPreference(context, action).apply {
                this.key = key
                this.title = title
                this.summary = summary
            },
        )
    }

    private fun PreferenceScreen.switch(key: String, default: Boolean, title: String, summary: String) {
        addPreference(
            SwitchPreferenceCompat(context).apply {
                this.key = key
                this.title = title
                this.summary = summary
                setDefaultValue(default)
            },
        )
    }

    private fun PreferenceScreen.edit(
        key: String,
        default: String,
        title: String,
        summary: String,
        multiline: Boolean = true,
    ) {
        addPreference(
            EditTextPreference(context).apply {
                this.key = key
                this.title = title
                this.summary = summary
                dialogTitle = title
                setDefaultValue(default)
                setOnBindEditTextListener { input ->
                    input.inputType = InputType.TYPE_CLASS_TEXT or if (multiline) {
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    } else {
                        0
                    }
                    if (multiline) input.minLines = 4
                }
            },
        )
    }

    private fun PreferenceScreen.list(
        key: String,
        default: String,
        title: String,
        entries: Array<String>,
        values: Array<String>,
    ) {
        addPreference(
            ListPreference(context).apply {
                this.key = key
                this.title = title
                this.entries = entries
                entryValues = values
                summary = "Choisir une valeur"
                setDefaultValue(default)
            },
        )
    }

    // --------------------------------------------------------------- util

    private fun jsonNames(root: JSONObject, key: String): String? =
        jsonObjectNames(root, key).joinToString().ifBlank { null }

    private fun jsonObjectNames(root: JSONObject, key: String): List<String> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
            .filter(String::isNotBlank)
    }

    private fun jsonStrings(root: JSONObject, key: String): String? {
        val array = root.optJSONArray(key) ?: return null
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            .joinToString().ifBlank { null }
    }

    private fun crewNames(root: JSONObject, jobs: Set<String>): String? {
        val crew = root.optJSONObject("credits")?.optJSONArray("crew") ?: return null
        return (0 until crew.length()).mapNotNull { index ->
            val person = crew.optJSONObject(index) ?: return@mapNotNull null
            person.optString("name").takeIf { person.optString("job") in jobs && it.isNotBlank() }
        }.distinct().take(8).joinToString().ifBlank { null }
    }

    private fun castNames(root: JSONObject): String? {
        val cast = root.optJSONObject("credits")?.optJSONArray("cast") ?: return null
        return (0 until minOf(cast.length(), 12)).mapNotNull { cast.optJSONObject(it)?.optString("name") }
            .filter(String::isNotBlank).joinToString().ifBlank { null }
    }

    private fun tmdbStatus(value: String): Int = when (value.lowercase()) {
        "ended", "released", "canceled" -> SAnime.COMPLETED
        "returning series", "in production", "post production" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun aniListStatus(value: String): Int = when (value.uppercase()) {
        "FINISHED" -> SAnime.COMPLETED
        "RELEASING" -> SAnime.ONGOING
        "CANCELLED" -> SAnime.CANCELLED
        "HIATUS" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }
}

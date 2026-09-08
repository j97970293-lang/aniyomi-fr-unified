package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
            if (version < 6) {
                if (!all.containsKey(FrSettings.KEY_SERIES_LAYOUT)) {
                    editor.putString(FrSettings.KEY_SERIES_LAYOUT, "classic")
                }
                if (!all.containsKey(FrSettings.KEY_VERIFY_STREAM_CONTENT)) {
                    editor.putBoolean(FrSettings.KEY_VERIFY_STREAM_CONTENT, true)
                }
                if (!all.containsKey(FrSettings.KEY_DNS_HOSTS)) {
                    editor.putString(FrSettings.KEY_DNS_HOSTS, "")
                }
            }
            if (version < 7) {
                // Les anciens « motifs de priorité » deviennent l'ordre des critères à flèches ;
                // un utilisateur qui les avait personnalisés retrouve ses choix en tête.
                if (!all.containsKey(FrSettings.KEY_STREAM_ORDER)) {
                    val legacy = all[FrSettings.KEY_NUVIO_PRIORITY] as? String
                    val migrated = FrSettings.streamOrderFromLegacyPatterns(legacy) ?: FrSettings.DEFAULT_STREAM_ORDER
                    editor.putString(FrSettings.KEY_STREAM_ORDER, migrated.joinToString("\n"))
                }
                editor.remove(FrSettings.KEY_NUVIO_PRIORITY)
                if (!all.containsKey(FrSettings.KEY_NUVIO_AUTO_UPDATE)) {
                    editor.putBoolean(FrSettings.KEY_NUVIO_AUTO_UPDATE, true)
                }
                if (!all.containsKey(FrSettings.KEY_QUICK_SEARCH)) {
                    editor.putBoolean(FrSettings.KEY_QUICK_SEARCH, false)
                }
            }
            if (version < 8) {
                if (!all.containsKey(FrSettings.KEY_STREMIO_AUTO_UPDATE)) {
                    editor.putBoolean(FrSettings.KEY_STREMIO_AUTO_UPDATE, true)
                }
                if (!all.containsKey(FrSettings.KEY_BACKUP_AUTO_RESTORE)) {
                    editor.putBoolean(FrSettings.KEY_BACKUP_AUTO_RESTORE, false)
                }
            }
            editor.putInt(FrSettings.KEY_SETTINGS_VERSION, FrSettings.SETTINGS_VERSION)
            editor.apply()
        }
    }

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        /** Durée maximale de chaque catalogue en « recherche rapide ». */
        const val QUICK_SEARCH_TIMEOUT_MS = 6_000L
    }

    init {
        FrSettings.init(preferences)
        FrRuntime.init(client)
        NuvioClient.init(context)
        settingsScope.launch {
            // Mise à jour quotidienne des manifests et catalogues Stremio.
            runCatching { StremioCatalog.autoUpdateIfDue() }
        }
        settingsScope.launch {
            // Restauration distante optionnelle, HTTPS et au plus quotidienne.
            runCatching { SettingsBackup.autoRestoreIfDue(preferences) }
        }
        settingsScope.launch {
            // Mise à jour automatique (au plus quotidienne) des dépôts et scripts Nuvio.
            runCatching { NuvioClient.autoUpdateIfDue() }
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
        val items = catalogItems(catalogFilterValue(), page, latest = false).splitMultiSeasonSeries()
        return AnimesPage(items.map { it.toSAnimeForLayout() }, items.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val items = catalogItems(catalogFilterValue(), page, latest = true).splitMultiSeasonSeries()
        return AnimesPage(items.map { it.toSAnimeForLayout() }, items.isNotEmpty())
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
                .splitMultiSeasonSeries()
            return@coroutineScope AnimesPage(items.map { it.toSAnimeForLayout() }, items.isNotEmpty())
        }
        val useStremioOnly = type == "stremio" ||
            !FrSettings.useMainCatalogs ||
            (!FrSettings.useTmdbCatalog && !FrSettings.useAnimeCatalog)
        // Recherche rapide : TMDB et AniList seulement (pas de repli Jikan ni d'addons Stremio
        // en mode mixte), chaque appel borné à QUICK_SEARCH_TIMEOUT_MS.
        val quick = FrSettings.quickSearch
        suspend fun <T> bounded(block: suspend () -> List<T>): List<T> =
            if (quick) withTimeoutOrNull(QUICK_SEARCH_TIMEOUT_MS) { block() }.orEmpty() else block()
        val jobs = buildList {
            if (FrSettings.useStremioCatalog && (useStremioOnly || (type == "all" && !quick))) {
                add(async { bounded { StremioCatalog.browse(page, query, stremioCatalogKey, stremioExtras) } })
            }
            if (!useStremioOnly && FrSettings.useTmdbCatalog && type != "anime") {
                add(
                    async {
                        bounded { TmdbCatalog.search(query, page) }.filter {
                            type == "all" || it.id.kind == type
                        }
                    },
                )
            }
            if (!useStremioOnly && FrSettings.useAnimeCatalog && type in setOf("all", "anime")) {
                add(async { bounded { AnimeCatalog.search(query, page, quick = quick) } })
            }
        }
        val items = jobs.awaitAll().flatten().deduplicate().splitMultiSeasonSeries()
        AnimesPage(items.map { it.toSAnimeForLayout() }, items.isNotEmpty())
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

    /**
     * Réglage « Séparer les saisons » : chaque série à plusieurs saisons devient
     * plusieurs fiches (« Titre — Saison N ») directement dans le catalogue.
     * Les saisons spéciales (0) sont ignorées ; un titre qui ne peut pas être
     * découpé (métadonnées indisponibles) reste tel quel.
     */
    private suspend fun List<CatalogItem>.splitMultiSeasonSeries(): List<CatalogItem> {
        if (FrSettings.seriesLayout != "split") return this
        if (isEmpty()) return this
        return coroutineScope {
            chunked(6).flatMap { batch ->
                batch.map { item ->
                    async {
                        runCatching { splitSeasonEntries(item) }.getOrDefault(listOf(item))
                    }
                }.awaitAll().flatten()
            }
        }
    }

    private suspend fun splitSeasonEntries(item: CatalogItem): List<CatalogItem> {
        val seasons = when {
            item.id.catalog == "tmdb" && item.id.kind == "tv" -> tmdbSeasonNumbers(item.id)

            item.id.catalog == "stremio" && !item.id.kind.equals("movie", true) ->
                stremioSeasonNumbers(item.id)

            else -> return listOf(item)
        }
        if (seasons.size < 2) return listOf(item)
        return seasons.map { number ->
            item.copy(
                id = item.id.copy(season = number),
                title = if (number == 0) {
                    "${item.title} — Épisodes spéciaux"
                } else {
                    "${item.title} — Saison $number"
                },
            )
        }
    }

    private suspend fun tmdbSeasonNumbers(id: CatalogId): List<Int> {
        val details = TmdbCatalog.details(id) ?: return emptyList()
        val seasons = details.optJSONArray("seasons") ?: return emptyList()
        return (0 until seasons.length()).mapNotNull { index ->
            val season = seasons.optJSONObject(index) ?: return@mapNotNull null
            val number = season.optInt("season_number", -1)
            number.takeIf { it >= 1 && season.optInt("episode_count", 0) > 0 }
        }.sorted()
    }

    private suspend fun stremioSeasonNumbers(id: CatalogId): List<Int> {
        val ref = StremioCatalog.Ref.parse(id.id) ?: return emptyList()
        if (ref.type.equals("movie", true)) return emptyList()
        val meta = StremioCatalog.meta(ref) ?: return emptyList()
        return StremioCatalog.videos(meta)
            .mapNotNull { video -> video.optInt("season", -1).takeIf { it >= 1 } }
            .distinct()
            .sorted()
    }

    /** [CatalogItem.toSAnime] adapté à l'organisation des saisons choisie dans les réglages. */
    private fun CatalogItem.toSAnimeForLayout(): SAnime {
        val anime = toSAnime()
        val parsed = CatalogId.parse(anime.url) ?: return anime
        val multiSeasonKind = when (parsed.catalog) {
            "tmdb" -> parsed.kind == "tv"
            "stremio" -> parsed.kind != "movie"
            else -> false
        }
        when (FrSettings.seriesLayout) {
            "merged" -> if (multiSeasonKind) anime.fetch_type = FetchType.Episodes
            "split" -> if (parsed.season != null) anime.fetch_type = FetchType.Episodes
        }
        return anime
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
            fetch_type = when {
                id.kind != "tv" -> FetchType.Episodes
                id.season != null || FrSettings.seriesLayout == "merged" -> FetchType.Episodes
                else -> FetchType.Seasons
            }
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
                when {
                    id.season != null || FrSettings.seriesLayout == "merged" -> FetchType.Episodes
                    else -> FetchType.Seasons
                }
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
        val multiSeasonKind = when (id.catalog) {
            "tmdb" -> id.kind == "tv"
            "stremio" -> id.kind != "movie"
            else -> false
        }
        // Fiches enregistrées avant le réglage « Fusionner » : un seul palier
        // « Toutes les saisons » dont les épisodes arrivent tous via getEpisodeList.
        if (FrSettings.seriesLayout == "merged" && multiSeasonKind && id.season == null) {
            return listOf(
                SAnime.create().apply {
                    url = id.serialize()
                    title = anime.title.takeIf(String::isNotBlank)?.let { "$it — Toutes les saisons" }
                        ?: "Toutes les saisons"
                    thumbnail_url = anime.thumbnail_url
                    background_url = anime.background_url
                    description = "Toutes les saisons réunies dans une seule liste d'épisodes " +
                        "(réglage « Fusionner les saisons »)."
                    season_number = 1.0
                    status = anime.status
                    genre = anime.genre
                    author = anime.author
                    artist = anime.artist
                    fetch_type = FetchType.Episodes
                },
            )
        }
        // Une fiche saison (mode « Séparer ») n'a pas de sous-saisons.
        if (id.season != null) return emptyList()
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

        if (id.season == null && FrSettings.seriesLayout == "merged") {
            return mergedTmdbEpisodes(id, titles, item, imdb)
        }
        val seasonNumber = id.season ?: 1
        return tmdbSeasonRows(id, titles, item, imdb, seasonNumber, mergedLabels = false)
            .sortedByDescending { it.episode_number }
    }

    /** Tous les épisodes de toutes les saisons (réglage « Fusionner les saisons »). */
    private suspend fun mergedTmdbEpisodes(
        id: CatalogId,
        titles: List<String>,
        item: CatalogItem,
        imdb: String?,
    ): List<SEpisode> = coroutineScope {
        val details = TmdbCatalog.details(id) ?: return@coroutineScope emptyList()
        val seasons = details.optJSONArray("seasons") ?: return@coroutineScope emptyList()
        val numbers = (0 until seasons.length()).mapNotNull { index ->
            val season = seasons.optJSONObject(index) ?: return@mapNotNull null
            val number = season.optInt("season_number", -1)
            number.takeIf { it >= 1 && season.optInt("episode_count", 0) > 0 }
        }.sorted()
        if (numbers.isEmpty()) return@coroutineScope emptyList()
        val rows = numbers.chunked(6).flatMap { batch ->
            batch.map { number ->
                async {
                    runCatching { tmdbSeasonRows(id, titles, item, imdb, number, mergedLabels = true) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        rows.forEachIndexed { index, episode ->
            episode.episode_number = (index + 1).toFloat()
        }
        rows.sortedByDescending { it.episode_number }
    }

    /** Épisodes d'une saison TMDB, dans l'ordre (1 → N). */
    private suspend fun tmdbSeasonRows(
        id: CatalogId,
        titles: List<String>,
        item: CatalogItem,
        imdb: String?,
        seasonNumber: Int,
        mergedLabels: Boolean,
    ): List<SEpisode> {
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
                name = if (mergedLabels) {
                    val episodeName = episode.optString("name").takeIf(String::isNotBlank)
                    if (episodeName == null) "S$seasonNumber E$number" else "S$seasonNumber E$number — $episodeName"
                } else {
                    episode.optString("name").takeIf(String::isNotBlank)?.let { "Épisode $number — $it" }
                        ?: "Épisode $number"
                }
                episode_number = number.toFloat()
                scanlator = if (seasonNumber == 0) "Spécial" else "Saison $seasonNumber"
                summary = episode.optString("overview").takeIf(String::isNotBlank)
                preview_url = TmdbCatalog.image(episode.optString("still_path"), "w500")
                date_upload = parseDate(episode.optString("air_date"))
            }
        }
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
        if (FrSettings.seriesLayout == "merged" && id.season == null) {
            return mergedStremioEpisodes(ref, meta, item, titles, isAnime)
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

    /** Tous les épisodes de toutes les saisons Stremio (réglage « Fusionner les saisons »). */
    private fun mergedStremioEpisodes(
        ref: StremioCatalog.Ref,
        meta: JSONObject,
        item: CatalogItem?,
        titles: List<String>,
        isAnime: Boolean,
    ): List<SEpisode> {
        val videos = StremioCatalog.videos(meta)
            .filter { it.optInt("season", 0) >= 1 }
            .sortedWith(
                compareBy<JSONObject> { it.optInt("season", 1) }.thenBy { it.optInt("episode", 1) },
            )
        if (videos.isEmpty()) return emptyList()
        val absoluteNumbers = if (isAnime) {
            videos.mapIndexedNotNull { index, video ->
                video.optString("id").takeIf(String::isNotBlank)?.let { it to index + 1 }
            }.toMap()
        } else {
            emptyMap()
        }
        val rows = videos.mapNotNull { video ->
            val streamId = video.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val season = video.optInt("season", 1).coerceAtLeast(1)
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
                    "S$season E$episode"
                } else {
                    "S$season E$episode — $episodeTitle"
                }
                scanlator = "Saison $season"
                summary = video.optString("overview").ifBlank { video.optString("description") }
                    .takeIf(String::isNotBlank)
                preview_url = video.optString("thumbnail").takeIf { it.startsWith("http") }
                date_upload = parseDate(video.optString("released").take(10))
            }
        }
        rows.forEachIndexed { index, row ->
            row.episode_number = (index + 1).toFloat()
        }
        return rows.sortedByDescending { it.episode_number }
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
        val nuvioHosters = videosToHosters(nuvioVideos, tracks, StreamLabel.ENGINE_NUVIO)

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
            .let(StreamRanker::sorted)

        // Un serveur par source, nommé « Nuvio · flemmix : VF, VOSTFR » (langues réellement trouvées).
        return prepared.groupBy { video ->
            StreamLabel.parse(video.videoTitle)?.source?.ifBlank { null } ?: "FR Unifié"
        }.map { (provider, providerVideos) ->
            Hoster(
                hosterUrl = "frunified://${provider.hashCode()}",
                hosterName = StreamLabel.hosterName(engineName, provider, providerVideos.map(Video::videoTitle)),
                videoList = providerVideos,
            )
        }.sortedWith(hosterComparator())
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val videos = if (StremioClient.isLazyHoster(hoster)) {
            // Stremio sonde déjà chaque flux au chargement du serveur.
            runCatching { StremioClient.streams(hoster) }.getOrDefault(emptyList())
        } else {
            // Nuvio : les URL signées et les popups peuvent avoir changé depuis le listage.
            NuvioClient.reverify(hoster.videoList.orEmpty())
        }
        return StreamRanker.sorted(videos)
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedWith(hosterComparator())

    override fun List<Video>.sortVideos(): List<Video> = StreamRanker.sorted(this)

    /** Ordre des flux : critères classés avec les flèches (langue puis qualité), voir [StreamRanker]. */
    private fun videoComparator(): Comparator<Video> = StreamRanker.videoComparator()

    private fun hosterComparator(): Comparator<Hoster> =
        StreamRanker.hosterComparator(stremioFirst = FrSettings.engineOrder == "stremio_first")

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
        header("🎬 1 · CATALOGUES (films, séries, animés)")
        switch(
            FrSettings.KEY_USE_MAIN_CATALOGS,
            true,
            "Activer les catalogues principaux",
            "TMDB + AniList + Jikan. Désactiver pour ne garder que le catalogue Stremio.",
        )
        switch(
            FrSettings.KEY_USE_TMDB,
            true,
            "TMDB — films et séries",
            "Catalogue général localisé (affiché par défaut).",
        )
        switch(
            FrSettings.KEY_USE_ANIME,
            true,
            "AniList — animés",
            "Catalogue d'animés avec titres français, romaji et anglais.",
        )
        switch(
            FrSettings.KEY_USE_JIKAN,
            true,
            "Jikan / MyAnimeList — animés",
            "Repli du catalogue animé et comptage des séries toujours en cours.",
        )
        switch(
            FrSettings.KEY_USE_STREMIO_CATALOG,
            true,
            "Catalogue Stremio",
            "Fiches, saisons et épisodes fournis par les addons Stremio.",
        )
        action(
            "action_stremio_catalog",
            "Choisir la rangée du catalogue Stremio",
            "Charge les manifests actifs puis mémorise la rangée sélectionnée.",
        ) { showStremioCatalogPicker(context) }
        list(
            FrSettings.KEY_POPULAR,
            "mixed",
            "Onglet d'accueil (Populaires / Derniers)",
            arrayOf("Mixte", "Films", "Séries", "Animés", "Stremio"),
            arrayOf("mixed", "movies", "series", "anime", "stremio"),
        )
        switch(
            FrSettings.KEY_QUICK_SEARCH,
            false,
            "Recherche rapide",
            "N'interroge que TMDB et AniList (bornés à 6 s) : pas de repli Jikan ni d'addons Stremio en mode mixte.",
        )
        action(
            "action_catalog_languages",
            "Langues des catalogues",
            "Choix multiple : TMDB et les catalogues Stremio localisés (🇫🇷 🇬🇧 🇪🇸…).",
        ) { showCatalogLanguagePicker(context) }
        list(
            FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE,
            "fr-FR",
            "Langue principale",
            FrSettings.CATALOG_LANGUAGE_LABELS.map { (code, label) ->
                FrSettings.flagLabel(code, label)
            }.toTypedArray(),
            FrSettings.CATALOG_LANGUAGE_LABELS.keys.toTypedArray(),
        )
        list(
            FrSettings.KEY_SERIES_LAYOUT,
            "classic",
            "Organisation des saisons d'une série",
            arrayOf(
                "Classique — la fiche, puis la liste des saisons",
                "Fusionnées — toutes les saisons dans une seule fiche",
                "Séparées — une fiche par saison dès le catalogue",
            ),
            arrayOf("classic", "merged", "split"),
        )
        edit(
            FrSettings.KEY_TMDB,
            FrSettings.DEFAULT_TMDB_KEY,
            "Clé API TMDB",
            "Laisser la clé proposée ou coller votre propre clé v3.",
            multiline = false,
        )

        header("🧭 2 · LECTURE (ordre des moteurs et des flux)")
        list(
            FrSettings.KEY_ENGINE_ORDER,
            "nuvio_first",
            "Ordre des moteurs de lecture",
            arrayOf("Nuvio d'abord, Stremio en secours", "Stremio d'abord, Nuvio en secours"),
            arrayOf("nuvio_first", "stremio_first"),
        )
        action(
            "action_stream_order",
            "Classer langues et qualités avec les flèches",
            "VF avant VOSTFR, 1080p avant 4K… Le premier critère satisfait décide de l'ordre des flux.",
        ) { showStreamOrderDialog(context) }
        edit(
            FrSettings.KEY_STREAM_ORDER,
            FrSettings.DEFAULT_STREAM_ORDER.joinToString("\n"),
            "Ordre enregistré des critères (texte)",
            "Généré par le classement à flèches : VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K, 720p…",
        )

        header("📺 3 · SOURCES NUVIO (sites de streaming)")
        switch(
            FrSettings.KEY_USE_NUVIO,
            true,
            "Activer les sources Nuvio",
            "Scrapeurs intégrés ; chaque site reste sélectionnable ci-dessous.",
        )
        action(
            "action_nuvio_sources",
            "Choisir les sources (avec drapeaux)",
            "Active ou désactive chaque site. Le drapeau indique sa langue 🇫🇷 🇹🇷 🇯🇵…",
        ) { showNuvioPicker(context) }
        action(
            "action_nuvio_order",
            "Classer les sources avec les flèches",
            "Met une source en haut ou en bas (⬆️ ⬇️) au lieu d'écrire la liste des noms.",
        ) { showNuvioOrderDialog(context) }
        action(
            "action_nuvio_languages",
            "Langues des serveurs",
            "Filtre les sites exécutés ; le sélecteur affiche tout le contenu des dépôts.",
        ) { showNuvioLanguagePicker(context) }
        switch(
            FrSettings.KEY_VERIFY_STREAM_CONTENT,
            true,
            "Vérifier les liens (anti-popups)",
            "Sonde à la découverte, puis revérifie chaque lien Nuvio au clic avant lecture.",
        )
        action(
            "action_nuvio_diagnostic",
            "Diagnostic des sources",
            "Teste Rhino et jusqu'à cinq sources actives sur un vrai titre.",
        ) { showNuvioDiagnostic(context) }
        action(
            "action_nuvio_add",
            "Ajouter un dépôt Nuvio",
            "Collez ici l'URL d'un manifest (scrapers[]) Nuvio.",
        ) { showExternalSourceDialog(context, ExternalSourceImporter.Kind.NUVIO) }
        list(
            FrSettings.KEY_NUVIO_CONCURRENCY,
            "3",
            "Sites interrogés en même temps",
            arrayOf("2 — prudent", "3 — recommandé", "4 — rapide", "6 — parallèle"),
            arrayOf("2", "3", "4", "6"),
        )
        list(
            FrSettings.KEY_NUVIO_SEARCH_MODE,
            "fast",
            "Mode de recherche",
            arrayOf(
                "Rapide — parallèle, s'arrête à la première VF",
                "Équilibré — parallèle, deux sites et la VF",
                "Complet — tous les sites actifs en parallèle",
            ),
            arrayOf("fast", "balanced", "complete"),
        )
        list(
            FrSettings.KEY_NUVIO_MAX,
            "4",
            "Flux maximum par site",
            arrayOf("2", "4", "8", "12", "Illimité"),
            arrayOf("2", "4", "8", "12", "0"),
        )
        edit(
            FrSettings.KEY_NUVIO_ORDER,
            FrSettings.RECOMMENDED_NUVIO_IDS.joinToString("\n"),
            "Ordre enregistré des sources (texte)",
            "Généré par le classement à flèches. Modifiable ici dans les cas avancés.",
        )
        switch(
            FrSettings.KEY_NUVIO_AUTO_UPDATE,
            true,
            "Mise à jour automatique des sources",
            "Relit les dépôts et retélécharge les scripts modifiés une fois par jour, au lancement.",
        )
        action(
            "action_nuvio_update",
            "Mettre à jour les sources maintenant",
            "Force la relecture des dépôts Nuvio et le rafraîchissement de tous les scripts.",
        ) { runNuvioUpdate(context) }

        header("🧩 4 · STREMIO (addons)")
        switch(
            FrSettings.KEY_USE_STREMIO,
            true,
            "Lecture Stremio activée",
            "Serveurs affichés même sans Nuvio, classés selon l'ordre choisi en section 2.",
        )
        action(
            "action_stremio_sources",
            "Choisir les addons",
            "Addons de catalogue, de métadonnées, de flux et de sous-titres.",
        ) { showStremioPicker(context) }
        action(
            "action_stremio_add",
            "Ajouter un addon Stremio",
            "Accepte un manifest d'addon (flux, catalogue, meta ou sous-titres).",
        ) { showExternalSourceDialog(context, ExternalSourceImporter.Kind.STREMIO) }
        switch(
            FrSettings.KEY_STREMIO_AUTO_UPDATE,
            true,
            "Mise à jour automatique quotidienne",
            "Recharge les manifests et leurs catalogues une fois par jour.",
        )
        action(
            "action_stremio_update",
            "Mettre à jour les addons maintenant",
            "Force le rechargement des manifests et affiche un bilan.",
        ) { runStremioUpdate(context) }
        list(
            FrSettings.KEY_STREMIO_MAX,
            "8",
            "Flux maximum Stremio",
            arrayOf("4", "8", "12", "20", "Illimité"),
            arrayOf("4", "8", "12", "20", "0"),
        )
        switch(FrSettings.KEY_USE_SUBS, true, "Sous-titres externes", "Inclut l'addon OpenSubtitles v3")
        edit(
            FrSettings.KEY_SUB_LANGS,
            "fre,fra,fr,eng,en",
            "Langues des sous-titres (texte)",
            "Codes séparés par des virgules : fre, fra, fr, eng, en…",
            multiline = false,
        )

        header("🌐 5 · RÉSEAU — DNS personnalisé")
        action(
            "action_dns_presets",
            "Préréglages DNS en un clic",
            "Cloudflare, Google, Quad9, AdGuard, téléphone ou personnalisé.",
        ) { showDnsPresetDialog(context) }
        edit(
            FrSettings.KEY_DNS_HOSTS,
            "",
            "DNS personnalisé de l'extension",
            "Une adresse par ligne : 1.1.1.1, 8.8.8.8, 9.9.9.9 ou URL DoH " +
                "(https://1.1.1.1/dns-query). Vide = DNS de l'appareil. DoH d'abord, puis UDP 53.",
        )
        action(
            "action_dns_test",
            "Tester la résolution DNS",
            "Vérifie les domaines (catalogues, dépôts, sources) en DoH puis UDP.",
        ) { showDnsTestDialog(context) }
        header("🛠️ 6 · AVANCÉ (en-têtes et clés)")
        edit(
            FrSettings.KEY_TOKENS,
            "",
            "Clés API Nuvio (texte)",
            "Une ligne NOM=valeur, injectée dans process.env des providers.",
        )
        edit(
            FrSettings.KEY_UA,
            FrSettings.DEFAULT_USER_AGENT,
            "User-Agent (texte)",
            "User-Agent des requêtes des sources et des sondes.",
            multiline = false,
        )
        edit(
            FrSettings.KEY_REFERER,
            "https://www.google.com/",
            "Referer par défaut (texte)",
            "Referer HTTP utilisé par les sources.",
            multiline = false,
        )
        edit(
            FrSettings.KEY_COOKIES,
            "",
            "Cookies optionnels (texte)",
            "Cookies pour les domaines protégés.",
        )

        header("💾 7 · SAUVEGARDE ET RESTAURATION")
        action(
            "action_backup_export",
            "Créer une sauvegarde",
            "Copie tous les réglages dans le presse-papiers au format JSON.",
        ) { exportBackup(context) }
        action(
            "action_backup_restore",
            "Restaurer une sauvegarde",
            "Collez le JSON créé par FR Unifié ; les caches ne sont pas importés.",
        ) { showBackupRestoreDialog(context) }
        edit(
            FrSettings.KEY_BACKUP_URL,
            "",
            "Lien HTTPS du backup",
            "Lien direct vers un JSON de sauvegarde (GitHub Gist brut, serveur personnel…).",
            multiline = false,
        )
        switch(
            FrSettings.KEY_BACKUP_AUTO_RESTORE,
            false,
            "Restaurer automatiquement depuis le lien",
            "Vérifie le lien au lancement, au plus une fois par jour. Désactivé par défaut.",
        )

        header("ℹ️ 8 · AIDE")
        action(
            "action_guide",
            "Guide des réglages",
            "Ouvrir un résumé lisible de toutes les options et de leurs effets.",
        ) { showGuideDialog(context) }
    }

    /** Séparateur de section : simple, grisé, avec emoji pour une lecture rapide. */
    private fun PreferenceScreen.header(title: String) {
        addPreference(
            EditTextPreference(context).apply {
                setEnabled(false)
                this.title = title
                summary = ""
            },
        )
    }

    private fun showGuideDialog(dialogContext: Context) {
        val guide = buildString {
            appendLine("🎬 1 · CATALOGUES")
            appendLine(
                "Sélectionnez ce que vous voyez à l'accueil : TMDB (films/séries), AniList/Jikan (animés), Stremio. " +
                    "La langue principale pilote les fiches TMDB. " +
                    "« Recherche rapide » ne consulte que TMDB et AniList pour répondre en quelques secondes.",
            )
            appendLine()
            appendLine("🧭 2 · LECTURE")
            appendLine(
                "« Nuvio d'abord » essaie d'abord les sites de streaming (souvent la VF), puis Stremio en secours " +
                    "— ou l'inverse. Le classement à flèches des langues et qualités (VF avant VOSTFR, " +
                    "1080p avant 4K…) ordonne les flux : chaque flux s'affiche « (VF) 1080p · source · moteur » " +
                    "et chaque serveur « Nuvio · source : VF, VOSTFR ».",
            )
            appendLine()
            appendLine("📺 3 · SOURCES NUVIO")
            appendLine(
                "Choisissez les sites activés (drapeau = langue), puis classez-les avec les flèches : la première " +
                    "source est essayée en premier. Tous les sites partent en parallèle (2 à 6 à la fois). " +
                    "« Vérifier les liens » sonde à la découverte puis revérifie au clic avant lecture. " +
                    "La mise à jour automatique relit les dépôts une fois par jour.",
            )
            appendLine()
            appendLine("🧩 4 · STREMIO")
            appendLine(
                "Les addons fournissent catalogues et serveurs. Le chargement se fait au clic ; " +
                    "un addon lent ne bloque plus les autres.",
            )
            appendLine()
            appendLine("🌐 5 · RÉSEAU")
            appendLine(
                "Le DNS personnalisé utilise d'abord le DoH (HTTPS, ex. 1.1.1.1) puis UDP 53, avec repli " +
                    "sur le DNS du téléphone. Il s'applique aux catalogues, manifests, sources et sondes. " +
                    "La lecture finale reste gérée par Aniyomi.",
            )
        }
        AlertDialog.Builder(dialogContext)
            .setTitle("Guide des réglages")
            .setMessage(guide)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun showDnsPresetDialog(dialogContext: Context) {
        val labels = arrayOf(
            "☁️ Cloudflare — 1.1.1.1",
            "🔎 Google — 8.8.8.8",
            "🛡️ Quad9 — 9.9.9.9",
            "🚫 AdGuard — 94.140.14.14",
            "📱 Téléphone — DNS du système",
            "✏️ Personnalisé",
        )
        val values = arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "94.140.14.14", "", null)
        AlertDialog.Builder(dialogContext)
            .setTitle("Choisir le DNS")
            .setItems(labels) { _, index ->
                val value = values[index]
                if (value == null) {
                    editDnsFromContext(dialogContext)
                } else {
                    preferences.edit().putString(FrSettings.KEY_DNS_HOSTS, value).commit()
                    FrDns.clearCache()
                    displayToast(if (value.isBlank()) "DNS du téléphone activé" else "DNS activé : $value")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showDnsTestDialog(dialogContext: Context) {
        displayToast("Test DNS en cours…", Toast.LENGTH_LONG)
        settingsScope.launch {
            val hosts = buildList {
                add("api.themoviedb.org")
                add("raw.githubusercontent.com")
                FrSettings.nuvioRepos.mapNotNull { repo ->
                    runCatching { java.net.URI(repo).host }.getOrNull()
                }.filterNotNull().distinct().forEach(::add)
                FrSettings.stremioUrls.mapNotNull { url ->
                    runCatching { java.net.URI(url).host }.getOrNull()
                }.filterNotNull().distinct().take(6).forEach(::add)
            }.distinct()
            val configured = FrSettings.dnsHosts.joinToString(", ").ifBlank { "aucun (DNS du système)" }
            val report = buildString {
                appendLine("DNS configuré : $configured")
                appendLine("Ordre réel : DoH (HTTPS) → UDP 53 → DNS du système.")
                if (FrSettings.dnsHosts.isNotEmpty()) {
                    appendLine()
                    appendLine("Détail des chemins vers api.themoviedb.org :")
                    FrSettings.dnsHosts.forEach { server ->
                        val path = FrDns.testPath(server, "api.themoviedb.org")
                        val doh = path.dohAddresses.joinToString().ifBlank { "échec" }
                        val udp = if (server.startsWith(
                                "http",
                            )
                        ) {
                            "non applicable"
                        } else {
                            path.udpAddresses.joinToString().ifBlank {
                                "échec"
                            }
                        }
                        appendLine("• $server")
                        appendLine("  HTTPS/DoH ${path.dohEndpoint ?: "—"} → $doh (${path.dohMs} ms)")
                        appendLine("  UDP/53 → $udp (${path.udpMs} ms)")
                    }
                }
                appendLine()
                appendLine("Résolution finale (avec replis) :")
                hosts.forEach { host ->
                    val custom = runCatching {
                        val started = System.currentTimeMillis()
                        val result = FrDns.lookup(host)
                        "${result.take(3).joinToString { it.hostAddress ?: it.toString() }} " +
                            "(${System.currentTimeMillis() - started} ms)"
                    }.getOrElse { "✗ ${it.message?.take(60) ?: "échec"}" }
                    appendLine("• $host → $custom")
                }
            }
            handler.post {
                AlertDialog.Builder(dialogContext)
                    .setTitle("Test DNS")
                    .setMessage(report)
                    .setNegativeButton("Fermer", null)
                    .setPositiveButton("Modifier le DNS") { _, _ -> editDnsFromContext(dialogContext) }
                    .show()
            }
        }
    }

    private fun editDnsFromContext(dialogContext: Context) {
        val input = EditText(dialogContext).apply {
            setText(FrSettings.dnsHosts.joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        AlertDialog.Builder(dialogContext)
            .setTitle("DNS personnalisé")
            .setMessage(
                "Une adresse par ligne (1.1.1.1, 8.8.8.8 ou URL https://…/dns-query). " +
                    "Vide = DNS de l'appareil.",
            )
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                preferences.edit()
                    .putString(FrSettings.KEY_DNS_HOSTS, input.text.toString().trim())
                    .commit()
                FrDns.clearCache()
                displayToast("DNS enregistré")
            }
            .show()
    }

    private data class SourceChoice(
        val label: String,
        val value: String,
        val enabled: Boolean,
    )

    private fun showCatalogLanguagePicker(dialogContext: Context) {
        val values = FrSettings.CATALOG_LANGUAGE_LABELS.keys.toList()
        val labels = FrSettings.CATALOG_LANGUAGE_LABELS.map { (code, label) ->
            FrSettings.flagLabel(code, label)
        }.toTypedArray()
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
                val flag = FrSettings.flagForLanguages(scraper.contentLanguage)
                SourceChoice(
                    label = "$flag ${scraper.name}$recommendation · $origin$status",
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
        val languageLabels = linkedMapOf(
            "fr" to "Français",
            "en" to "English",
            "es" to "Español",
            "de" to "Deutsch",
            "it" to "Italiano",
            "pt" to "Português",
            "ja" to "日本語",
            "hi" to "हिन्दी",
            "tr" to "Türkçe",
            "id" to "Bahasa Indonesia",
            "pl" to "Polski",
            "ar" to "العربية",
            "ta" to "தமிழ்",
            "te" to "తెలుగు",
            "ml" to "മലയാളം",
            "kn" to "ಕನ್ನಡ",
        )
        val values = (languageLabels.keys + "all").toTypedArray()
        val labels = values.map { value ->
            if (value == "all") "🌍 Toutes les langues" else FrSettings.flagLabel(value, languageLabels[value] ?: value)
        }.toTypedArray()
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

    private fun showNuvioOrderDialog(dialogContext: Context) {
        displayToast("Chargement des sources actives…")
        settingsScope.launch {
            val scrapers = runCatching { NuvioClient.scrapers() }.getOrDefault(emptyList())
            handler.post {
                if (scrapers.isEmpty()) {
                    displayToast("Aucune source active : activez d'abord des sources Nuvio", Toast.LENGTH_LONG)
                    return@post
                }
                val byId = scrapers.associateBy { it.id.lowercase() }
                val ordered = linkedSetOf<String>()
                FrSettings.nuvioOrder.forEach { id ->
                    val key = id.lowercase()
                    if (byId.containsKey(key)) ordered += key
                }
                byId.keys.forEach { key -> if (key !in ordered) ordered += key }
                if (ordered.isEmpty()) {
                    displayToast("Aucune source active : activez d'abord des sources Nuvio", Toast.LENGTH_LONG)
                    return@post
                }
                showNuvioOrderDialogBody(dialogContext, ordered.toMutableList(), byId)
            }
        }
    }

    private fun showNuvioOrderDialogBody(
        dialogContext: Context,
        ordered: MutableList<String>,
        byId: Map<String, NuvioClient.NuvioScraper>,
    ) {
        val density = dialogContext.resources.displayMetrics.density
        val padding = (density * 12).toInt()
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        container.addView(
            TextView(dialogContext).apply {
                text = "N° 1 = source essayée en priorité. L'ordre est enregistré à chaque déplacement."
                textSize = 13f
                setPadding(0, 0, 0, (density * 10).toInt())
            },
        )

        val itemsContainer = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(itemsContainer)

        fun persistOrder() {
            val leftovers = FrSettings.nuvioOrder
                .map(String::trim).filter(String::isNotBlank).map(String::lowercase)
                .filterNot { leftover -> ordered.any { it == leftover } }
            preferences.edit()
                .putString(FrSettings.KEY_NUVIO_ORDER, (ordered + leftovers).distinct().joinToString("\n"))
                .commit()
        }

        fun arrowButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit): Button =
            Button(dialogContext).apply {
                this.text = text
                contentDescription = description
                isEnabled = enabled
                alpha = if (enabled) 1.0f else 0.3f
                textSize = 14f
                minWidth = 0
                minHeight = 0
                background = null
                val hPad = (density * 6).toInt()
                val vPad = (density * 4).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setOnClickListener { if (enabled) onClick() }
            }

        fun render() {
            itemsContainer.removeAllViews()
            ordered.forEachIndexed { index, key ->
                val scraper = byId.getValue(key)
                val isRec = scraper.id in FrSettings.RECOMMENDED_NUVIO_IDS
                val recLabel = if (isRec) " ★" else ""
                val labelText =
                    "${index + 1}. ${FrSettings.flagForLanguages(scraper.contentLanguage)} ${scraper.name}$recLabel"
                val textView = TextView(dialogContext).apply {
                    text = labelText
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                fun moveTo(target: Int) {
                    if (target < 0 || target >= ordered.size || target == index) return
                    ordered.add(target, ordered.removeAt(index))
                    persistOrder()
                    render()
                }
                val row = LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val rowVPad = (density * 4).toInt()
                    setPadding(0, rowVPad, 0, rowVPad)
                    addView(textView)
                    addView(arrowButton("⏫", "Déplacer tout en haut", index > 0) { moveTo(0) })
                    addView(arrowButton("▲", "Monter d'une place", index > 0) { moveTo(index - 1) })
                    addView(arrowButton("▼", "Descendre d'une place", index < ordered.size - 1) { moveTo(index + 1) })
                    addView(
                        arrowButton("⏬", "Déplacer tout en bas", index < ordered.size - 1) {
                            moveTo(ordered.size - 1)
                        },
                    )
                }
                itemsContainer.addView(row)
            }
        }
        render()

        AlertDialog.Builder(dialogContext)
            .setTitle("Classer les sources Nuvio (${ordered.size})")
            .setView(
                ScrollView(dialogContext).apply {
                    addView(container)
                },
            )
            .setPositiveButton("Terminé", null)
            .show()
    }

    /** Classement à flèches des critères de flux (langues et qualités), enregistré à chaque déplacement. */
    private fun showStreamOrderDialog(dialogContext: Context) {
        val ordered = (FrSettings.streamOrder + FrSettings.STREAM_CRITERIA).distinct().toMutableList()
        val density = dialogContext.resources.displayMetrics.density
        val padding = (density * 12).toInt()
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        container.addView(
            TextView(dialogContext).apply {
                text = "N° 1 = critère le plus souhaité. Un flux est classé d'après le premier critère qu'il " +
                    "satisfait, puis le suivant (VF 720p passe avant VOSTFR 1080p si VF est devant). " +
                    "L'ordre est enregistré à chaque déplacement."
                textSize = 13f
                setPadding(0, 0, 0, (density * 8).toInt())
            },
        )

        fun persistOrder() {
            preferences.edit()
                .putString(FrSettings.KEY_STREAM_ORDER, ordered.joinToString("\n"))
                .commit()
        }

        fun arrowButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit): Button =
            Button(dialogContext).apply {
                this.text = text
                contentDescription = description
                isEnabled = enabled
                alpha = if (enabled) 1.0f else 0.3f
                textSize = 14f
                minWidth = 0
                minHeight = 0
                background = null
                val hPad = (density * 6).toInt()
                val vPad = (density * 4).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                setOnClickListener { if (enabled) onClick() }
            }

        val itemsContainer = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun render() {
            itemsContainer.removeAllViews()
            ordered.forEachIndexed { index, criterion ->
                val isLanguage = criterion in StreamLabel.LANGUAGE_ORDER
                val labelText = "${index + 1}. ${if (isLanguage) "🗣️" else "🎞️"} " +
                    if (isLanguage) {
                        StreamLabel.languageLabel(criterion)
                    } else {
                        StreamLabel.qualityValue(criterion)?.let(StreamLabel::qualityLabel) ?: criterion
                    }
                val textView = TextView(dialogContext).apply {
                    text = labelText
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                fun moveTo(target: Int) {
                    if (target < 0 || target >= ordered.size || target == index) return
                    ordered.add(target, ordered.removeAt(index))
                    persistOrder()
                    render()
                }
                val row = LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val rowVPad = (density * 4).toInt()
                    setPadding(0, rowVPad, 0, rowVPad)
                    addView(textView)
                    addView(arrowButton("⏫", "Déplacer tout en haut", index > 0) { moveTo(0) })
                    addView(arrowButton("▲", "Monter d'une place", index > 0) { moveTo(index - 1) })
                    addView(arrowButton("▼", "Descendre d'une place", index < ordered.size - 1) { moveTo(index + 1) })
                    addView(
                        arrowButton("⏬", "Déplacer tout en bas", index < ordered.size - 1) {
                            moveTo(ordered.size - 1)
                        },
                    )
                }
                itemsContainer.addView(row)
            }
        }

        val addQuality = Button(dialogContext).apply {
            text = "+ Ajouter une qualité"
            contentDescription = "Ajouter une résolution personnalisée"
            setOnClickListener {
                val input = EditText(dialogContext).apply {
                    hint = "540p, 2160p, 8K…"
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                AlertDialog.Builder(dialogContext)
                    .setTitle("Ajouter une qualité")
                    .setMessage("Saisissez une résolution verticale entre 144p et 8640p.")
                    .setView(input)
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Ajouter") { _, _ ->
                        val quality = StreamLabel.qualityValue(input.text.toString())
                        if (quality == null || quality !in 144..8640) {
                            displayToast("Résolution non reconnue")
                        } else {
                            val token = StreamLabel.qualityText(quality)
                            if (token !in ordered) ordered.add(token)
                            val customs = (FrSettings.customQualities + quality).distinct().sortedDescending()
                            preferences.edit()
                                .putString(FrSettings.KEY_CUSTOM_QUALITIES, customs.joinToString("\n") { "${it}p" })
                                .putString(FrSettings.KEY_STREAM_ORDER, ordered.joinToString("\n"))
                                .commit()
                            render()
                        }
                    }
                    .show()
            }
        }
        container.addView(addQuality)
        container.addView(itemsContainer)
        render()

        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("Classer langues et qualités (${ordered.size})")
            .setView(
                ScrollView(dialogContext).apply {
                    addView(container)
                },
            )
            .setNeutralButton("Ordre conseillé", null)
            .setPositiveButton("Terminé") { _, _ -> persistOrder() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                ordered.clear()
                ordered.addAll((FrSettings.DEFAULT_STREAM_ORDER + FrSettings.STREAM_CRITERIA).distinct())
                persistOrder()
                render()
                displayToast("Ordre conseillé rétabli : VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K…")
            }
        }
        runCatching { dialog.show() }
    }

    private fun exportBackup(dialogContext: Context) {
        val json = SettingsBackup.export(preferences)
        val clipboard = dialogContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("FR Unifié — sauvegarde", json))
        AlertDialog.Builder(dialogContext)
            .setTitle("Sauvegarde copiée")
            .setMessage(
                "${preferences.all.size} réglages copiés dans le presse-papiers. " +
                    "Conservez ce JSON dans un endroit sûr.",
            )
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun showBackupRestoreDialog(dialogContext: Context) {
        val input = EditText(dialogContext).apply {
            hint = "Collez ici le JSON de sauvegarde"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
        }
        AlertDialog.Builder(dialogContext)
            .setTitle("Restaurer une sauvegarde")
            .setMessage("Les réglages présents dans le JSON seront remplacés. Redémarrez ensuite l'extension.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Restaurer") { _, _ ->
                val result = runCatching { SettingsBackup.restore(preferences, input.text.toString()) }
                displayToast(
                    result.fold({ "$it réglage(s) restauré(s)" }, { "Échec : ${it.message?.take(100)}" }),
                    Toast.LENGTH_LONG,
                )
            }
            .show()
    }

    private fun runStremioUpdate(dialogContext: Context) {
        displayToast("Mise à jour Stremio en cours…", Toast.LENGTH_LONG)
        settingsScope.launch {
            val report = runCatching { StremioCatalog.updateAddons() }
            handler.post {
                AlertDialog.Builder(dialogContext)
                    .setTitle(if (report.isSuccess) "Addons Stremio à jour" else "Mise à jour impossible")
                    .setMessage(
                        report.map(StremioCatalog.UpdateReport::summary).getOrElse {
                            it.message
                                ?: "Erreur inconnue"
                        },
                    )
                    .setPositiveButton("Fermer", null)
                    .show()
            }
        }
    }

    /** Relecture immédiate des dépôts Nuvio et rafraîchissement de tous les scripts. */
    private fun runNuvioUpdate(dialogContext: Context) {
        displayToast("Mise à jour des sources Nuvio en cours…", Toast.LENGTH_LONG)
        settingsScope.launch {
            val report = runCatching { NuvioClient.updateSources() }
            handler.post {
                AlertDialog.Builder(dialogContext)
                    .setTitle(if (report.isSuccess) "Sources Nuvio à jour" else "Mise à jour impossible")
                    .setMessage(
                        report.map(NuvioClient.UpdateReport::summary).getOrElse { failure ->
                            failure.message?.take(300) ?: "Erreur inconnue"
                        },
                    )
                    .setPositiveButton("Fermer", null)
                    .show()
            }
        }
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
            val locale = Regex("/[a-z]{2}-[A-Z]{2}(?:/|$)").find("$clean/")?.value?.trim('/')
            val flag = locale?.let { FrSettings.flagForLanguages(listOf(it)) } ?: "🌐"
            val recommended = if (FrSettings.DEFAULT_STREMIO_ADDONS.any { StremioClient.base(it) == clean }) {
                " ★ conseillée"
            } else {
                ""
            }
            SourceChoice("$flag $display$recommended", clean, FrSettings.isStremioEnabled(clean))
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
        val preference = ListPreference(context).apply {
            this.key = key
            this.title = title
            this.entries = entries
            entryValues = values
            setDefaultValue(default)
        }
        fun summaryFor(value: String): String {
            val index = preference.findIndexOfValue(value)
            return if (index >= 0 && index < entries.size) entries[index] else "Choisir une valeur"
        }
        preference.summary = summaryFor(preferences.getString(key, default) ?: default)
        preference.setOnPreferenceChangeListener { _, newValue ->
            preference.summary = summaryFor(newValue?.toString().orEmpty())
            true
        }
        addPreference(preference)
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

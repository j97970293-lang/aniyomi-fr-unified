package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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

    /**
     * « all » : FR Unifié n'est pas qu'une extension française — son contenu est
     * international (sites FR, TR, EN, …) et son interface propose le français et
     * l'anglais. Elle apparaît donc dans tous les filtres de langue d'Aniyomi.
     */
    override val lang = "all"
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
            if (version < 9) {
                if (!all.containsKey(FrSettings.KEY_UI_LANGUAGE)) {
                    editor.putString(FrSettings.KEY_UI_LANGUAGE, L10n.FR)
                }
                // L'ancien interrupteur « catalogues principaux » est remplacé par
                // les quatre cases du catalogue : il était coupé = tous coupés.
                if ((all[FrSettings.KEY_USE_MAIN_CATALOGS] as? Boolean) == false) {
                    editor.putBoolean(FrSettings.KEY_USE_TMDB, false)
                    editor.putBoolean(FrSettings.KEY_USE_ANIME, false)
                    editor.putBoolean(FrSettings.KEY_USE_JIKAN, false)
                }
                editor.remove(FrSettings.KEY_USE_MAIN_CATALOGS)
                // « Flux maximum par site » resté à l'ancienne valeur par défaut (4) :
                // passage à illimité, comme dans NuviO.
                if ((all[FrSettings.KEY_NUVIO_MAX] as? String) == "4") {
                    editor.putString(FrSettings.KEY_NUVIO_MAX, "0")
                }
            }
            if (version < 10) {
                // Les réglages « modes de recherche » (rapide/équilibré/complet) et
                // « langues Nuvio » n'existent plus : tous les sites activés partent,
                // aucune langue ne bloque l'exécution d'un site.
                editor.remove(FrSettings.KEY_NUVIO_SEARCH_MODE)
                editor.remove(FrSettings.KEY_NUVIO_LANGUAGES)
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
                    L10n.t("${item.title} — Épisodes spéciaux", "${item.title} — Special episodes")
                } else {
                    L10n.t("${item.title} — Saison $number", "${item.title} — Season $number")
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
        L10n.t("Catalogue affiché", "Displayed catalog"),
        arrayOf(
            L10n.t("Mixte", "Mixed"),
            L10n.t("Films", "Movies"),
            L10n.t("Séries", "Series"),
            L10n.t("Animés", "Anime"),
            "Stremio",
        ),
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
        L10n.t("Catalogue Stremio (chaque entrée du manifest)", "Stremio catalog (every manifest entry)"),
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
        L10n.t(
            "Option Stremio · ${extraName.replaceFirstChar(Char::titlecase)}",
            "Stremio option · ${extraName.replaceFirstChar(Char::titlecase)}",
        ),
        (if (extra.required) extra.options else listOf(L10n.t("Tous", "All")) + extra.options).toTypedArray(),
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
                    L10n.t(
                        "Chaque catalogs[] est détecté automatiquement ; réinitialiser les filtres après un ajout.",
                        "Every catalogs[] entry is detected automatically; reset the filters after an addition.",
                    ),
                ),
            )
            add(StremioCatalogFilter(catalogs, selected.key))
            val extras = selected.extras.filter {
                it.options.isNotEmpty() &&
                    !it.name.equals("search", true) &&
                    !it.name.equals("skip", true)
            }
            if (extras.isNotEmpty()) {
                add(
                    AnimeFilter.Header(
                        L10n.t(
                            "Après un changement de catalogue : Filtrer, puis Réinitialiser les filtres.",
                            "After changing catalog: Filter, then Reset the filters.",
                        ),
                    ),
                )
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
        append(L10n.t("Ordre de lecture : ", "Playback order: "))
        if (FrSettings.engineOrder == "stremio_first") {
            append(L10n.t("Stremio puis Nuvio", "Stremio then Nuvio"))
        } else {
            append(L10n.t("Nuvio puis Stremio", "Nuvio then Stremio"))
        }
        append(
            L10n.t(
                ". Les moteurs sont indépendants et le second sert de repli.",
                ". The engines are independent and the second one is a fallback.",
            ),
        )
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
                    title = anime.title.takeIf(String::isNotBlank)?.let {
                        L10n.t("$it — Toutes les saisons", "$it — All seasons")
                    } ?: L10n.t("Toutes les saisons", "All seasons")
                    thumbnail_url = anime.thumbnail_url
                    background_url = anime.background_url
                    description = L10n.t(
                        "Toutes les saisons réunies dans une seule liste d'épisodes " +
                            "(réglage « Fusionner les saisons »).",
                        "All seasons merged into a single episode list " +
                            "(« Merged seasons » setting).",
                    )
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
                this.title = if (number == 0) {
                    L10n.t("$title — Épisodes spéciaux", "$title — Special episodes")
                } else {
                    L10n.t("$title — Saison $number", "$title — Season $number")
                }
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
                    L10n.t("${anime.title} — Épisodes spéciaux", "${anime.title} — Special episodes")
                } else {
                    L10n.t("${anime.title} — Saison $number", "${anime.title} — Season $number")
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
                    name = L10n.t("Film", "Movie")
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
                    episode.optString("name").takeIf(String::isNotBlank)
                        ?.let { L10n.t("Épisode $number — $it", "Episode $number — $it") }
                        ?: L10n.t("Épisode $number", "Episode $number")
                }
                episode_number = number.toFloat()
                scanlator = if (seasonNumber == 0) {
                    L10n.t("Spécial", "Special")
                } else {
                    L10n.t("Saison $seasonNumber", "Season $seasonNumber")
                }
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
                    name = L10n.t("Film", "Movie")
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
                    name = L10n.t("Flux TV", "TV stream")
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
                    L10n.t("Épisode $episode", "Episode $episode")
                } else {
                    L10n.t("Épisode $episode — $episodeTitle", "Episode $episode — $episodeTitle")
                }
                episode_number = episode.toFloat()
                scanlator = if (season == 0) {
                    L10n.t("Spécial", "Special")
                } else {
                    L10n.t("Saison $season", "Season $season")
                }
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
                scanlator = L10n.t("Saison $season", "Season $season")
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
            name = if (isMovie) {
                L10n.t("Film", "Movie")
            } else {
                L10n.t("Épisode $number", "Episode $number")
            }
            episode_number = number.toFloat()
            scanlator = if (isMovie) {
                L10n.t("Film animé", "Anime movie")
            } else {
                L10n.t("VF / VOSTFR", "FRENCH / SUBBED")
            }
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
        header(L10n.t("🌍 1 · GÉNÉRAL", "🌍 1 · GENERAL"))
        list(
            FrSettings.KEY_UI_LANGUAGE,
            L10n.FR,
            L10n.t("Langue de l'application", "App language"),
            L10n.UI_LANGUAGE_LABELS.values.toTypedArray(),
            L10n.UI_LANGUAGE_LABELS.keys.toTypedArray(),
        )
        list(
            FrSettings.KEY_POPULAR,
            "mixed",
            L10n.t("Onglet d'accueil (Populaires / Derniers)", "Home tab (Popular / Latest)"),
            arrayOf(
                L10n.t("Mixte", "Mixed"),
                L10n.t("Films", "Movies"),
                L10n.t("Séries", "Series"),
                L10n.t("Animés", "Anime"),
                "Stremio",
            ),
            arrayOf("mixed", "movies", "series", "anime", "stremio"),
        )
        action(
            "action_catalogs",
            L10n.t("Catalogues (TMDB, AniList, Jikan, Stremio)", "Catalogs (TMDB, AniList, Jikan, Stremio)"),
            L10n.t(
                "Choix multiple des catalogues affichés à l'accueil.",
                "Multi-select of the catalogs shown on the home screen.",
            ),
        ) { showCatalogsPopup(context) }
        action(
            "action_stremio_catalog",
            L10n.t("Rangée du catalogue Stremio", "Stremio catalog row"),
            L10n.t(
                "Charge les manifests actifs puis mémorise la rangée sélectionnée.",
                "Loads the active manifests and remembers the selected row.",
            ),
        ) { showStremioCatalogPicker(context) }
        action(
            "action_catalog_languages",
            L10n.t("Langues des catalogues", "Catalog languages"),
            L10n.t(
                "Choix multiple : TMDB et les catalogues Stremio localisés (🇫🇷 🇬 🇪🇸…).",
                "Multi-select: TMDB and localized Stremio catalogs (🇫🇷 🇧 🇪…).",
            ),
        ) { showCatalogLanguagePicker(context) }
        list(
            FrSettings.KEY_SERIES_LAYOUT,
            "classic",
            L10n.t("Organisation des saisons d'une série", "How series seasons are organized"),
            arrayOf(
                L10n.t(
                    "Classique — la fiche, puis la liste des saisons",
                    "Classic — the entry, then the season list",
                ),
                L10n.t(
                    "Fusionnées — toutes les saisons dans une seule fiche",
                    "Merged — all seasons in a single entry",
                ),
                L10n.t(
                    "Séparées — une fiche par saison dès le catalogue",
                    "Split — one entry per season in the catalog",
                ),
            ),
            arrayOf("classic", "merged", "split"),
        )
        switch(
            FrSettings.KEY_QUICK_SEARCH,
            false,
            L10n.t("Recherche rapide", "Quick search"),
            L10n.t(
                "N'interroge que TMDB et AniList (bornés à 6 s) : pas de repli Jikan ni d'addons " +
                    "Stremio en mode mixte.",
                "Only queries TMDB and AniList (6 s cap): no Jikan fallback nor Stremio addons in mixed mode.",
            ),
        )

        header(L10n.t("🧭 2 · LECTURE (moteurs et flux)", "🧭 2 · PLAYBACK (engines and streams)"))
        list(
            FrSettings.KEY_ENGINE_ORDER,
            "nuvio_first",
            L10n.t("Ordre des moteurs de lecture", "Playback engine order"),
            arrayOf(
                L10n.t("Nuvio d'abord, Stremio en secours", "Nuvio first, Stremio as fallback"),
                L10n.t("Stremio d'abord, Nuvio en secours", "Stremio first, Nuvio as fallback"),
            ),
            arrayOf("nuvio_first", "stremio_first"),
        )
        action(
            "action_stream_order",
            L10n.t("Classer les flux (langues et qualités)", "Rank streams (languages and qualities)"),
            L10n.t(
                "Flèches ⬆️️ : VF avant VOSTFR, 1080p avant 4K… Ajoutez vos langues et qualités.",
                "Arrows ⬆️⬇️: VF before VOSTFR, 1080p before 4K… Add your own languages and qualities.",
            ),
        ) { showStreamOrderDialog(context) }
        switch(
            FrSettings.KEY_USE_SUBS,
            true,
            L10n.t("Sous-titres externes", "External subtitles"),
            L10n.t(
                "Inclut l'addon OpenSubtitles v3 ; langues réglables ci-dessous.",
                "Includes the OpenSubtitles v3 addon; languages below.",
            ),
        )
        action(
            "action_sub_langs",
            L10n.t("Langues des sous-titres", "Subtitle languages"),
            L10n.t(
                "Codes séparés par des virgules : fre, fra, fr, eng, en…",
                "Comma-separated codes: fre, fra, fr, eng, en…",
            ),
        ) { showSubtitleLangsPopup(context) }

        header(L10n.t("📺 3 · SOURCES NUVIO (sites de streaming)", "📺 3 · NUVIO SOURCES (streaming sites)"))
        switch(
            FrSettings.KEY_USE_NUVIO,
            true,
            L10n.t("Activer les sources Nuvio", "Enable Nuvio sources"),
            L10n.t(
                "Scrapeurs intégrés ; chaque site reste sélectionnable ci-dessous.",
                "Built-in scrapers; every site stays selectable below.",
            ),
        )
        action(
            "action_nuvio_sources",
            L10n.t("Choisir les sources", "Choose the sources"),
            L10n.t(
                "Drapeau = langue, dépôt affiché comme dans NuviO. Appuyez longuement pour supprimer un dépôt.",
                "Flag = language, repo shown like in NuviO. Long-press to remove a repository.",
            ),
        ) { showNuvioPicker(context) }
        action(
            "action_nuvio_order",
            L10n.t("Classer les sources (flèches)", "Rank the sources (arrows)"),
            L10n.t(
                "Met une source en haut ou en bas (⬆️️) au lieu d'écrire la liste des noms.",
                "Move a source to the top or bottom (⬆️️) instead of writing the list of names.",
            ),
        ) { showNuvioOrderDialog(context) }
        action(
            "action_nuvio_add",
            L10n.t("Ajouter un dépôt Nuvio", "Add a Nuvio repository"),
            L10n.t(
                "Collez ici l'URL d'un manifest (scrapers[]) Nuvio.",
                "Paste the URL of a Nuvio manifest (scrapers[]) here.",
            ),
        ) { showExternalSourceDialog(context, ExternalSourceImporter.Kind.NUVIO) }
        action(
            "action_nuvio_search_options",
            L10n.t("Options de recherche des sources", "Source search options"),
            L10n.t(
                "Parallélisme, flux maximum par site, vérification anti-popups, mise à jour automatique.",
                "Concurrency, max streams per site, anti-popup verification, automatic updates.",
            ),
        ) { showNuvioOptionsPopup(context) }
        action(
            "action_nuvio_source_config",
            L10n.t("Configurer les sources (clés API, jetons…)", "Configure sources (API keys, tokens…)"),
            L10n.t(
                "Variables d'environnement demandées par certaines sources (manifests avec « env »).",
                "Environment variables requested by some sources (manifests with « env »).",
            ),
        ) { showSourceConfigPopup(context) }
        action(
            "action_nuvio_diagnostic",
            L10n.t("Diagnostic des sources", "Source diagnostics"),
            L10n.t(
                "Teste Rhino et jusqu'à cinq sources actives sur un vrai titre.",
                "Tests Rhino and up to five active sources on a real title.",
            ),
        ) { showNuvioDiagnostic(context) }

        header(L10n.t("🧩 4 · STREMIO (addons)", "🧩 4 · STREMIO (addons)"))
        switch(
            FrSettings.KEY_USE_STREMIO,
            true,
            L10n.t("Lecture Stremio activée", "Stremio playback enabled"),
            L10n.t(
                "Serveurs affichés même sans Nuvio, classés selon l'ordre choisi en section 2.",
                "Servers shown even without Nuvio, ranked per the section 2 order.",
            ),
        )
        action(
            "action_stremio_sources",
            L10n.t("Choisir les addons", "Choose the addons"),
            L10n.t(
                "Addons de catalogue, de métadonnées, de flux et de sous-titres. Appuyez longuement pour supprimer.",
                "Catalog, metadata, stream and subtitle addons. Long-press to remove.",
            ),
        ) { showStremioPicker(context) }
        action(
            "action_stremio_add",
            L10n.t("Ajouter un addon Stremio", "Add a Stremio addon"),
            L10n.t(
                "Accepte un manifest d'addon (flux, catalogue, meta ou sous-titres).",
                "Accepts an addon manifest (streams, catalog, meta or subtitles).",
            ),
        ) { showExternalSourceDialog(context, ExternalSourceImporter.Kind.STREMIO) }
        action(
            "action_stremio_options",
            L10n.t("Options Stremio", "Stremio options"),
            L10n.t(
                "Flux maximum, mise à jour automatique des manifests.",
                "Maximum streams, automatic manifest updates.",
            ),
        ) { showStremioOptionsPopup(context) }

        header(L10n.t("🌐 5 · RÉSEAU (DNS)", "🌐 5 · NETWORK (DNS)"))
        action(
            "action_dns_presets",
            L10n.t("Préréglages DNS en un clic", "One-click DNS presets"),
            L10n.t(
                "Cloudflare, Google, Quad9, AdGuard, téléphone ou personnalisé.",
                "Cloudflare, Google, Quad9, AdGuard, phone or custom.",
            ),
        ) { showDnsPresetDialog(context) }
        action(
            "action_dns_settings",
            L10n.t("Réglages DNS avancés", "Advanced DNS settings"),
            L10n.t(
                "DNS personnalisé (DoH ou UDP 53) et test de la résolution.",
                "Custom DNS (DoH or UDP 53) and resolution test.",
            ),
        ) { showDnsSettingsPopup(context) }

        header(L10n.t("🛠️ 6 · AVANCÉ", "🛠️ 6 · ADVANCED"))
        action(
            "action_advanced",
            L10n.t("Paramètres avancés", "Advanced settings"),
            L10n.t(
                "Clé API TMDB, clés API des sources, User-Agent, Referer et cookies.",
                "TMDB API key, source API keys, User-Agent, Referer and cookies.",
            ),
        ) { showAdvancedPopup(context) }

        header(L10n.t("💾 7 · SAUVEGARDE ET RESTAURATION", "💾 7 · BACKUP AND RESTORE"))
        action(
            "action_backup_export",
            L10n.t("Créer une sauvegarde", "Create a backup"),
            L10n.t(
                "Copier, partager vers un dossier de votre choix, ou enregistrer dans Téléchargements.",
                "Copy, share to a folder of your choice, or save to Downloads.",
            ),
        ) { exportBackup(context) }
        action(
            "action_backup_restore",
            L10n.t("Restaurer une sauvegarde", "Restore a backup"),
            L10n.t(
                "Choisir un fichier, coller le JSON, ou synchroniser depuis un lien.",
                "Choose a file, paste the JSON, or sync from a link.",
            ),
        ) { showBackupImportDialog(context) }
        action(
            "action_backup_sync",
            L10n.t("Synchronisation par lien (facultatif)", "Sync by link (optional)"),
            L10n.t(
                "Lien HTTPS vers un JSON de sauvegarde, restauré chaque jour au lancement.",
                "HTTPS link to a backup JSON, restored daily at startup.",
            ),
        ) { showBackupSyncPopup(context) }

        header(L10n.t("ℹ️ 8 · AIDE", "ℹ️ 8 · HELP"))
        action(
            "action_guide",
            L10n.t("Guide des réglages", "Settings guide"),
            L10n.t(
                "Ouvrir un résumé lisible de toutes les options et de leurs effets.",
                "Open a readable summary of every option and its effect.",
            ),
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
            appendLine(L10n.t("🌍 1 · GÉNÉRAL", "🌍 1 · GENERAL"))
            appendLine(
                L10n.t(
                    "La langue de l'application change tous les libellés (français ou anglais). " +
                        "« Catalogues » choisit ce que vous voyez à l'accueil : TMDB (films/séries), " +
                        "AniList/Jikan (animés), Stremio. « Recherche rapide » ne consulte que TMDB et AniList.",
                    "The app language changes every label (French or English). " +
                        "« Catalogs » picks what you see at home: TMDB (movies/series), AniList/Jikan " +
                        "(anime), Stremio. « Quick search » only queries TMDB and AniList.",
                ),
            )
            appendLine()
            appendLine(L10n.t("🧭 2 · LECTURE", "🧭 2 · PLAYBACK"))
            appendLine(
                L10n.t(
                    "« Nuvio d'abord » essaie d'abord les sites de streaming (souvent la VF), puis " +
                        "Stremio en secours " +
                        "— ou l'inverse. Le classement des flux (langues et qualités, flèches ⬆️️) " +
                        "ordonne tout : chaque flux s'affiche « (VF) 1080p · source · moteur » et chaque serveur " +
                        "« Nuvio · source : VF, VOSTFR ».",
                    "« Nuvio first » tries the streaming sites first (often the French dub), then Stremio " +
                        "— or the reverse. The stream ranking (languages and qualities, arrows ⬆️️) orders " +
                        "everything: each stream shows « (VF) 1080p · source · engine » and each server " +
                        "« Nuvio · source: VF, VOSTFR ».",
                ),
            )
            appendLine()
            appendLine(L10n.t("📺 3 · SOURCES NUVIO", "📺 3 · NUVIO SOURCES"))
            appendLine(
                L10n.t(
                    "Choisissez les sites activés (drapeau = langue, dépôt affiché comme dans NuviO) puis " +
                        "classez-les avec les flèches : la première source est essayée en premier. " +
                        "Tous les sites partent en parallèle. Appuyez longuement sur un site pour supprimer " +
                        "son dépôt. « Options de recherche » : parallélisme, flux maximum par site, " +
                        "vérification anti-popups, mise à jour automatique (une fois par jour).",
                    "Choose the active sites (flag = language, repo shown like in NuviO) then rank them " +
                        "with the arrows: the first source is tried first. All sites run in parallel. " +
                        "Long-press a site to remove its repository. « Source search options »: concurrency, " +
                        "max streams per site, anti-popup verification, automatic updates (once a day).",
                ),
            )
            appendLine()
            appendLine(L10n.t("🧩 4 · STREMIO", "🧩 4 · STREMIO"))
            appendLine(
                L10n.t(
                    "Les addons fournissent catalogues et serveurs. Le chargement se fait au clic ; " +
                        "un addon lent ne bloque plus les autres. Appuyez longuement pour supprimer un addon.",
                    "Addons provide catalogs and servers. Loading happens on click; a slow addon no longer " +
                        "blocks the others. Long-press to remove an addon.",
                ),
            )
            appendLine()
            appendLine(L10n.t("🌐 5 · RÉSEAU", "🌐 5 · NETWORK"))
            appendLine(
                L10n.t(
                    "Le DNS personnalisé utilise d'abord le DoH (HTTPS, ex. 1.1.1.1) puis UDP 53, avec repli " +
                        "sur le DNS du téléphone. Il s'applique aux catalogues, manifests, sources et sondes. " +
                        "La lecture finale reste gérée par Aniyomi.",
                    "Custom DNS uses DoH first (HTTPS, e.g. 1.1.1.1), then UDP 53, falling back to the phone " +
                        "DNS. It applies to catalogs, manifests, sources and probes. " +
                        "Final playback stays with Aniyomi.",
                ),
            )
            appendLine()
            appendLine(L10n.t("💾 7 · SAUVEGARDE", "💾 7 · BACKUP"))
            appendLine(
                L10n.t(
                    "Créez une sauvegarde en la copiant, en la partageant vers le dossier ou l'application de " +
                        "votre choix, ou en l'enregistrant dans Téléchargements. Restauration depuis un fichier, " +
                        "un texte collé, ou un lien HTTPS facultatif (synchronisation quotidienne).",
                    "Create a backup by copying it, sharing it to the folder or app of your choice, or saving " +
                        "it to Downloads. Restore from a file, pasted text, or an optional HTTPS link " +
                        "(daily sync).",
                ),
            )
        }
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Guide des réglages", "Settings guide"))
            .setMessage(guide)
            .setPositiveButton(L10n.t("Fermer", "Close"), null)
            .show()
    }

    private fun showDnsPresetDialog(dialogContext: Context) {
        val labels = arrayOf(
            "☁️ Cloudflare — 1.1.1.1",
            "🔎 Google — 8.8.8.8",
            "🛡️ Quad9 — 9.9.9.9",
            "🚫 AdGuard — 94.140.14.14",
            L10n.t("📱 Téléphone — DNS du système", "📱 Phone — system DNS"),
            L10n.t("✏️ Personnalisé…", "✏️ Custom…"),
        )
        val values = arrayOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "94.140.14.14", "", null)
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Choisir le DNS", "Choose the DNS"))
            .setItems(labels) { _, index ->
                val value = values[index]
                if (value == null) {
                    showDnsSettingsPopup(dialogContext)
                } else {
                    preferences.edit().putString(FrSettings.KEY_DNS_HOSTS, value).commit()
                    FrDns.clearCache()
                    displayToast(
                        if (value.isBlank()) {
                            L10n.t("DNS du téléphone activé", "Phone DNS enabled")
                        } else {
                            L10n.t("DNS activé : $value", "DNS enabled: $value")
                        },
                    )
                }
            }
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .show()
    }

    /** Popup « Réglages DNS avancés » : champ personnalisé + test de résolution. */
    private fun showDnsSettingsPopup(dialogContext: Context) {
        val density = dialogContext.resources.displayMetrics.density
        val padding = (density * 12).toInt()
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val input = EditText(dialogContext).apply {
            setText(FrSettings.dnsHosts.joinToString("\n"))
            hint = "1.1.1.1 / 8.8.8.8 / https://1.1.1.1/dns-query"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val testButton = Button(dialogContext).apply {
            text = L10n.t("Tester la résolution DNS", "Test DNS resolution")
            setOnClickListener { showDnsTestDialog(dialogContext) }
        }
        container.addView(
            TextView(dialogContext).apply {
                text = L10n.t(
                    "Une adresse par ligne (1.1.1.1, 8.8.8.8 ou URL https://…/dns-query). " +
                        "Vide = DNS de l'appareil. DoH d'abord, puis UDP 53.",
                    "One address per line (1.1.1.1, 8.8.8.8 or https://…/dns-query URL). " +
                        "Empty = device DNS. DoH first, then UDP 53.",
                )
                textSize = 13f
                setPadding(0, 0, 0, (density * 8).toInt())
            },
        )
        container.addView(input)
        container.addView(testButton)
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Réglages DNS avancés", "Advanced DNS settings"))
            .setView(container)
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                preferences.edit()
                    .putString(FrSettings.KEY_DNS_HOSTS, input.text.toString().trim())
                    .commit()
                FrDns.clearCache()
                displayToast(L10n.t("DNS enregistré", "DNS saved"))
            }
            .show()
    }

    private fun showDnsTestDialog(dialogContext: Context) {
        displayToast(L10n.t("Test DNS en cours…", "DNS test in progress…"), Toast.LENGTH_LONG)
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
                    .setTitle(L10n.t("Test DNS", "DNS test"))
                    .setMessage(report)
                    .setNegativeButton(L10n.t("Fermer", "Close"), null)
                    .setPositiveButton(L10n.t("Modifier le DNS", "Edit DNS")) { _, _ ->
                        showDnsSettingsPopup(dialogContext)
                    }
                    .show()
            }
        }
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
            .setTitle(L10n.t("Langues des catalogues", "Catalog languages"))
            .setMessage(
                L10n.t(
                    "La première langue cochée est la langue principale (fiches TMDB).",
                    "The first checked language is the primary one (TMDB entries).",
                ),
            )
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                val selected = values.indices.filter { checked[it] }.map { values[it] }
                    .ifEmpty { listOf(FrSettings.catalogLanguage) }
                val primary = FrSettings.catalogLanguage.takeIf { it in selected } ?: selected.first()
                preferences.edit()
                    .putString(FrSettings.KEY_CATALOG_LANGUAGE, selected.joinToString("\n"))
                    .putString(FrSettings.KEY_CATALOG_PRIMARY_LANGUAGE, primary)
                    .apply()
                displayToast(
                    L10n.t(
                        "Catalogues : ${selected.size} langue(s)",
                        "Catalogs: ${selected.size} language(s)",
                    ),
                )
            }
            .show()
    }

    /** Popup « Catalogues » : les quatre catalogues en cases à cocher (remplace les quatre interrupteurs). */
    private fun showCatalogsPopup(dialogContext: Context) {
        val rows = listOf(
            FrSettings.KEY_USE_TMDB to L10n.t(
                "TMDB — films et séries",
                "TMDB — movies and series",
            ),
            FrSettings.KEY_USE_ANIME to L10n.t(
                "AniList — animés",
                "AniList — anime",
            ),
            FrSettings.KEY_USE_JIKAN to L10n.t(
                "Jikan / MyAnimeList — animés",
                "Jikan / MyAnimeList — anime",
            ),
            FrSettings.KEY_USE_STREMIO_CATALOG to L10n.t("Catalogue Stremio", "Stremio catalog"),
        )
        val checks = BooleanArray(rows.size) {
            when (rows[it].first) {
                FrSettings.KEY_USE_TMDB -> FrSettings.useTmdbCatalog
                FrSettings.KEY_USE_ANIME -> FrSettings.useAniListCatalog
                FrSettings.KEY_USE_JIKAN -> FrSettings.useJikanCatalog
                else -> FrSettings.useStremioCatalog
            }
        }
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Catalogues", "Catalogs"))
            .setMultiChoiceItems(rows.map { it.second }.toTypedArray(), checks) { _, index, value ->
                checks[index] = value
            }
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                preferences.edit()
                    .putBoolean(FrSettings.KEY_USE_TMDB, checks[0])
                    .putBoolean(FrSettings.KEY_USE_ANIME, checks[1])
                    .putBoolean(FrSettings.KEY_USE_JIKAN, checks[2])
                    .putBoolean(FrSettings.KEY_USE_STREMIO_CATALOG, checks[3])
                    .apply()
            }
            .show()
    }

    /** Popup « Langues des sous-titres » : codes séparés par des virgules. */
    private fun showSubtitleLangsPopup(dialogContext: Context) {
        val input = EditText(dialogContext).apply {
            setText(FrSettings.subtitleLangs.joinToString(","))
            hint = "fre,fra,fr,eng,en"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(false)
            minLines = 2
        }
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Langues des sous-titres", "Subtitle languages"))
            .setMessage(
                L10n.t(
                    "Codes séparés par des virgules : fre, fra, fr, eng, en… " +
                        "Vide = tous les sous-titres.",
                    "Comma-separated codes: fre, fra, fr, eng, en… " +
                        "Empty = all subtitles.",
                ),
            )
            .setView(input)
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                preferences.edit()
                    .putString(FrSettings.KEY_SUB_LANGS, input.text.toString().trim())
                    .apply()
                displayToast(L10n.t("Langues des sous-titres enregistrées", "Subtitle languages saved"))
            }
            .show()
    }

    /**
     * Une rangée de boutons radio dans un popup de réglages : le titre, les
     * options (valeur, libellé) et la valeur courante ; [value] lit le choix.
     */
    private class RadioChoice(
        private val dialogContext: Context,
        container: LinearLayout,
        title: String,
        options: List<Pair<String, String>>,
        current: String,
    ) {
        private val density = dialogContext.resources.displayMetrics.density
        private val group = RadioGroup(dialogContext)
        private val valueById = linkedMapOf<Int, String>()

        init {
            container.addView(
                TextView(dialogContext).apply {
                    text = title
                    textSize = 13f
                    setPadding(0, (density * 10).toInt(), 0, (density * 4).toInt())
                },
            )
            group.orientation = RadioGroup.HORIZONTAL
            options.forEach { (value, label) ->
                val button = RadioButton(dialogContext).apply {
                    text = label
                    id = View.generateViewId()
                    isChecked = value == current
                }
                valueById[button.id] = value
                group.addView(button)
            }
            container.addView(group)
        }

        val value: String
            get() = valueById[group.checkedRadioButtonId].orEmpty()
    }

    /**
     * Popup « Options de recherche des sources » : regroupement des réglages
     * difficiles (parallélisme, flux maximum par site, vérification anti-popups,
     * mise à jour automatique) dans une seule fenêtre, comme dans Cloudstream.
     */
    private fun showNuvioOptionsPopup(dialogContext: Context) {
        val density = dialogContext.resources.displayMetrics.density
        val padding = (density * 12).toInt()
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val concurrency = RadioChoice(
            dialogContext,
            container,
            L10n.t("Sites interrogés en même temps", "Sites queried at the same time"),
            listOf(
                "2" to L10n.t("2 — prudent", "2 — cautious"),
                "3" to L10n.t("3 — recommandé", "3 — recommended"),
                "4" to L10n.t("4 — rapide", "4 — fast"),
                "6" to L10n.t("6 — parallèle", "6 — parallel"),
            ),
            FrSettings.nuvioConcurrency.toString(),
        )
        val maxPerSite = RadioChoice(
            dialogContext,
            container,
            L10n.t("Flux maximum par site", "Maximum streams per site"),
            listOf(
                "2" to "2",
                "4" to "4",
                "8" to "8",
                "12" to "12",
                "0" to L10n.t("Illimité", "Unlimited"),
            ),
            FrSettings.nuvioMaxPerScraper.toString(),
        )
        val verifyCheck = CheckBox(dialogContext).apply {
            text = L10n.t(
                "Vérifier les liens (anti-popups) avant lecture",
                "Verify links (anti-popup) before playback",
            )
            isChecked = FrSettings.verifyStreamContent
        }
        container.addView(verifyCheck)
        val autoUpdateCheck = CheckBox(dialogContext).apply {
            text = L10n.t(
                "Mise à jour automatique des sources (1×/jour)",
                "Automatic source updates (1×/day)",
            )
            isChecked = FrSettings.nuvioAutoUpdate
        }
        container.addView(autoUpdateCheck)
        val updateNow = Button(dialogContext).apply {
            text = L10n.t("Mettre à jour les sources maintenant", "Update the sources now")
            setOnClickListener { runNuvioUpdate(dialogContext) }
        }
        container.addView(updateNow)
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Options de recherche des sources", "Source search options"))
            .setView(
                ScrollView(dialogContext).apply {
                    addView(container)
                },
            )
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                preferences.edit()
                    .putString(FrSettings.KEY_NUVIO_CONCURRENCY, concurrency.value)
                    .putString(FrSettings.KEY_NUVIO_MAX, maxPerSite.value)
                    .putBoolean(FrSettings.KEY_VERIFY_STREAM_CONTENT, verifyCheck.isChecked)
                    .putBoolean(FrSettings.KEY_NUVIO_AUTO_UPDATE, autoUpdateCheck.isChecked)
                    .apply()
                displayToast(
                    L10n.t(
                        "Options enregistrées (${concurrency.value} en parallèle)",
                        "Options saved (${concurrency.value} in parallel)",
                    ),
                )
            }
            .show()
    }

    /**
     * Popup « Configurer les sources » : les variables d'environnement déclarées par
     * les manifests Nuvio (`env` / `requiredEnv`) sont réglables ici, sans ouvrir
     * le manifest. Une source dont une clé obligatoire est vide n'est pas exécutée
     * (signalée « à configurer » dans le diagnostic et le sélecteur).
     */
    private fun showSourceConfigPopup(dialogContext: Context) {
        displayToast(L10n.t("Chargement des sources…", "Loading sources…"))
        settingsScope.launch {
            val scrapers = runCatching { NuvioClient.scrapers(includeDisabled = true) }
                .getOrDefault(emptyList())
                .filter { it.envDefaults.isNotEmpty() || it.requiredEnv.isNotEmpty() }
            handler.post {
                if (scrapers.isEmpty()) {
                    AlertDialog.Builder(dialogContext)
                        .setTitle(L10n.t("Configurer les sources", "Configure the sources"))
                        .setMessage(
                            L10n.t(
                                "Aucune source configurée ne demande de variables d'environnement. " +
                                    "Les clés API génériques restent dans « Paramètres avancés ».",
                                "No configured source requires environment variables. " +
                                    "Generic API keys stay in « Advanced settings ».",
                            ),
                        )
                        .setPositiveButton(L10n.t("Fermer", "Close"), null)
                        .show()
                    return@post
                }
                val density = dialogContext.resources.displayMetrics.density
                val padding = (density * 12).toInt()
                val container = LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)
                }
                val fields = linkedMapOf<String, LinkedHashMap<String, EditText>>()
                scrapers.forEach { scraper ->
                    val keys = (scraper.requiredEnv + scraper.envDefaults.keys).distinct()
                    container.addView(
                        TextView(dialogContext).apply {
                            text = "${FrSettings.flagForLanguages(scraper.contentLanguage)} " +
                                "${scraper.name} · ${NuvioClient.repoLabel(scraper.repoBase)}"
                            textSize = 13f
                            setPadding(0, (density * 8).toInt(), 0, (density * 2).toInt())
                        },
                    )
                    val perScraper = linkedMapOf<String, EditText>()
                    keys.forEach { key ->
                        val merged = NuvioClient.mergedEnv(scraper)
                        val edit = EditText(dialogContext).apply {
                            hint = key
                            setText(merged[key].orEmpty())
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                        container.addView(edit)
                        perScraper[key] = edit
                    }
                    fields[scraper.id] = perScraper
                }
                AlertDialog.Builder(dialogContext)
                    .setTitle(L10n.t("Configurer les sources", "Configure the sources"))
                    .setView(
                        ScrollView(dialogContext).apply {
                            addView(container)
                        },
                    )
                    .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
                    .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                        fields.forEach { (scraperId, perScraper) ->
                            perScraper.forEach { (key, edit) ->
                                FrSettings.putSourceEnv(scraperId, key, edit.text.toString().trim())
                            }
                        }
                        // La lecture de la configuration se fait à l'exécution : rien à invalider.
                        displayToast(
                            L10n.t("Configuration des sources enregistrée", "Source configuration saved"),
                        )
                    }
                    .show()
            }
        }
    }

    /** Popup « Options Stremio » : flux maximum + mise à jour automatique. */
    private fun showStremioOptionsPopup(dialogContext: Context) {
        val density = dialogContext.resources.displayMetrics.density
        val padding = (density * 12).toInt()
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val maxStreams = RadioChoice(
            dialogContext,
            container,
            L10n.t("Flux maximum Stremio (par addon)", "Maximum Stremio streams (per addon)"),
            listOf(
                "4" to "4",
                "8" to "8",
                "12" to "12",
                "20" to "20",
                "0" to L10n.t("Illimité", "Unlimited"),
            ),
            FrSettings.stremioMaxStreams.toString(),
        )
        val autoUpdateCheck = CheckBox(dialogContext).apply {
            text = L10n.t(
                "Mise à jour automatique des manifests (1×/jour)",
                "Automatic manifest updates (1×/day)",
            )
            isChecked = FrSettings.stremioAutoUpdate
        }
        container.addView(autoUpdateCheck)
        val updateNow = Button(dialogContext).apply {
            text = L10n.t("Mettre à jour les addons maintenant", "Update the addons now")
            setOnClickListener { runStremioUpdate(dialogContext) }
        }
        container.addView(updateNow)
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Options Stremio", "Stremio options"))
            .setView(
                ScrollView(dialogContext).apply {
                    addView(container)
                },
            )
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                preferences.edit()
                    .putString(FrSettings.KEY_STREMIO_MAX, maxStreams.value)
                    .putBoolean(FrSettings.KEY_STREMIO_AUTO_UPDATE, autoUpdateCheck.isChecked)
                    .apply()
                displayToast(L10n.t("Options Stremio enregistrées", "Stremio options saved"))
            }
            .show()
    }

    /** Popup « Paramètres avancés » : clés et en-têtes (les réglages les plus techniques). */
    private fun showAdvancedPopup(dialogContext: Context) {
        val density = dialogContext.resources.displayMetrics.density
        val padding = (density * 12).toInt()
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun caption(text: String) {
            container.addView(
                TextView(dialogContext).apply {
                    this.text = text
                    textSize = 12f
                    setPadding(0, (density * 8).toInt(), 0, (density * 2).toInt())
                },
            )
        }
        fun field(hint: String, initial: String, minLines: Int = 1): EditText =
            EditText(dialogContext).apply {
                this.hint = hint
                setText(initial)
                inputType = InputType.TYPE_CLASS_TEXT or if (minLines > 1) {
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    0
                }
                if (minLines > 1) this.minLines = minLines
            }
        caption(
            L10n.t(
                "Clé API TMDB (laisser la clé proposée ou la vôtre)",
                "TMDB API key (keep the suggested one or yours)",
            ),
        )
        val tmdb = field("f3d7…", FrSettings.tmdbApiKey)
        container.addView(tmdb)
        caption(
            L10n.t(
                "Clés API des sources (une ligne NOM=valeur, injectée dans process.env)",
                "Source API keys (one line NAME=value, injected into process.env)",
            ),
        )
        val tokens = field(
            "API_KEY=…",
            preferences.getString(FrSettings.KEY_TOKENS, "").orEmpty(),
            minLines = 3,
        )
        container.addView(tokens)
        caption(L10n.t("User-Agent des requêtes des sources", "User-Agent of the source requests"))
        val ua = field("Mozilla/…", FrSettings.nuvioUserAgent)
        container.addView(ua)
        caption(L10n.t("Referer HTTP utilisé par les sources", "HTTP Referer used by the sources"))
        val referer = field("https://…", FrSettings.nuvioReferer)
        container.addView(referer)
        caption(L10n.t("Cookies optionnels pour les domaines protégés", "Optional cookies for protected domains"))
        val cookies = field("nom=valeur; …", FrSettings.nuvioCookies, minLines = 2)
        container.addView(cookies)
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Paramètres avancés", "Advanced settings"))
            .setView(
                ScrollView(dialogContext).apply {
                    addView(container)
                },
            )
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                preferences.edit()
                    .putString(FrSettings.KEY_TMDB, tmdb.text.toString().trim())
                    .putString(FrSettings.KEY_TOKENS, tokens.text.toString().trim())
                    .putString(FrSettings.KEY_UA, ua.text.toString().trim())
                    .putString(FrSettings.KEY_REFERER, referer.text.toString().trim())
                    .putString(FrSettings.KEY_COOKIES, cookies.text.toString().trim())
                    .apply()
                displayToast(L10n.t("Paramètres avancés enregistrés", "Advanced settings saved"))
            }
            .show()
    }

    private fun showNuvioPicker(dialogContext: Context) {
        displayToast(L10n.t("Chargement des sources Nuvio…", "Loading Nuvio sources…"))
        settingsScope.launch {
            val nuvioResult = runCatching { NuvioClient.scrapers(includeDisabled = true) }
            val diagnostics = NuvioClient.diagnostics()
            val all = nuvioResult.getOrDefault(emptyList())
            val choices = all.map { scraper ->
                // Le dépôt d'origine est toujours visible, comme dans l'application NuviO :
                // un site français dans un dépôt international reste identifiable.
                val origin = NuvioClient.repoLabel(scraper.repoBase)
                val recommendation = if (scraper.id in FrSettings.RECOMMENDED_NUVIO_IDS) {
                    L10n.t(" ★ conseillée", " ★ recommended")
                } else {
                    ""
                }
                val configMark = if (NuvioClient.missingRequiredEnv(scraper).isNotEmpty()) {
                    L10n.t(" · ⚙️ à configurer", " · ⚙️ to configure")
                } else {
                    ""
                }
                val status = diagnostics[scraper.id]?.let { " · ${it.take(45)}" }.orEmpty()
                val flag = FrSettings.flagForLanguages(scraper.contentLanguage)
                SourceChoice(
                    label = "$flag ${scraper.name}$recommendation · $origin$configMark$status",
                    value = scraper.id,
                    enabled = FrSettings.isNuvioEnabled(scraper.id),
                )
            }
            val reposByScraper = all.associate { it.id.lowercase() to it.repoBase }
            handler.post {
                if (choices.isEmpty()) {
                    displayToast(
                        nuvioResult.exceptionOrNull()?.message?.let {
                            L10n.t(
                                "Nuvio indisponible : ${it.take(100)}",
                                "Nuvio unavailable: ${it.take(100)}",
                            )
                        } ?: L10n.t(
                            "Aucun scrapeur Nuvio configuré (ajoutez un dépôt)",
                            "No Nuvio scraper configured (add a repository)",
                        ),
                        Toast.LENGTH_LONG,
                    )
                    return@post
                }
                val checked = BooleanArray(choices.size) { choices[it].enabled }
                var wildcardMode = FrSettings.nuvioEnabled.any { it.equals("all", true) }
                val dialog = AlertDialog.Builder(dialogContext)
                    .setTitle(
                        L10n.t(
                            "Nuvio (${checked.count { it }}/${choices.size} actives)",
                            "Nuvio (${checked.count { it }}/${choices.size} active)",
                        ),
                    )
                    .setMessage(
                        L10n.t(
                            "Appuyez longuement pour supprimer le dépôt entier.",
                            "Long-press to remove the whole repository.",
                        ),
                    )
                    .setMultiChoiceItems(
                        choices.map(SourceChoice::label).toTypedArray(),
                        checked,
                    ) { _, index, value -> checked[index] = value }
                    .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
                    .setNeutralButton(L10n.t("Conseillées", "Recommended"), null)
                    .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
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
                        displayToast(
                            L10n.t(
                                "Nuvio : ${checked.count { it }} source(s) active(s)",
                                "Nuvio: ${checked.count { it }} active source(s)",
                            ),
                        )
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
                    // Suppression d'un dépôt : toutes les sources qui en viennent disparaissent.
                    dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                        val repo = reposByScraper[choices[position].value.lowercase()]
                        if (repo.isNullOrBlank()) return@setOnItemLongClickListener false
                        val removedCount = choices.count { choice ->
                            reposByScraper[choice.value.lowercase()] == repo
                        }
                        AlertDialog.Builder(dialogContext)
                            .setTitle(L10n.t("Supprimer le dépôt", "Remove the repository"))
                            .setMessage(
                                L10n.t(
                                    "Supprimer « ${NuvioClient.repoLabel(repo)} » ($removedCount source(s)) ? " +
                                        "Ajoutez-le à nouveau avec « Ajouter un dépôt Nuvio » pour le restaurer.",
                                    "Remove « ${NuvioClient.repoLabel(repo)} » ($removedCount source(s))? " +
                                        "Add it back with « Add a Nuvio repository » to restore it.",
                                ),
                            )
                            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
                            .setPositiveButton(L10n.t("Supprimer", "Remove")) { _, _ ->
                                val remaining = FrSettings.nuvioRepos.filter { it != repo }
                                preferences.edit()
                                    .putString(FrSettings.KEY_NUVIO_REPOS, remaining.joinToString("\n"))
                                    .apply()
                                NuvioClient.invalidateRepository(repo)
                                displayToast(
                                    L10n.t("Dépôt supprimé", "Repository removed"),
                                    Toast.LENGTH_LONG,
                                )
                            }
                            .show()
                        true
                    }
                }
                runCatching { dialog.show() }
                    .onFailure {
                        displayToast(
                            L10n.t(
                                "Impossible d'ouvrir le sélecteur Nuvio",
                                "Cannot open the Nuvio picker",
                            ),
                            Toast.LENGTH_LONG,
                        )
                    }
            }
        }
    }

    private fun showNuvioOrderDialog(dialogContext: Context) {
        displayToast(L10n.t("Chargement des sources…", "Loading sources…"))
        settingsScope.launch {
            // includeDisabled = true : le classement ne doit jamais s'ouvrir sur
            // une liste vide tant qu'un dépôt est lisible (les sources désactivées
            // restent visibles et classables, signalées « désactivée »).
            val result = runCatching { NuvioClient.scrapers(includeDisabled = true) }
            val scrapers = result.getOrDefault(emptyList())
            val failure = result.exceptionOrNull()
            handler.post {
                if (scrapers.isEmpty()) {
                    AlertDialog.Builder(dialogContext)
                        .setTitle(L10n.t("Classer les sources", "Rank the sources"))
                        .setMessage(
                            L10n.t(
                                "Aucune source n'est connue pour l'instant : les dépôts ne sont pas (ou plus) " +
                                    "lisibles. Mettez-les à jour, ou ajoutez un dépôt, puis réessayez.\n\n" +
                                    (failure?.message?.take(160) ?: ""),
                                "No source is known yet: the repositories are not (anymore) readable. " +
                                    "Update them, or add a repository, then try again.\n\n" +
                                    (failure?.message?.take(160) ?: ""),
                            ),
                        )
                        .setNegativeButton(L10n.t("Fermer", "Close"), null)
                        .setPositiveButton(L10n.t("Mettre à jour maintenant", "Update now")) { d, _ ->
                            d.dismiss()
                            runNuvioUpdate(dialogContext)
                        }
                        .show()
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
                    displayToast(
                        L10n.t(
                            "Aucune source connue : mettez à jour les dépôts",
                            "No known source: update the repositories",
                        ),
                        Toast.LENGTH_LONG,
                    )
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
                text = L10n.t(
                    "N° 1 = source essayée en priorité. L'ordre est enregistré à chaque déplacement. " +
                        "Le dépôt de chaque source est affiché sous le nom.",
                    "No. 1 = source tried first. The order is saved on every move. " +
                        "Each source's repository is shown under its name.",
                )
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
                val disabledLabel = if (!FrSettings.isNuvioEnabled(scraper.id)) {
                    L10n.t(" · désactivée", " · disabled")
                } else {
                    ""
                }
                val labelText =
                    "${index + 1}. ${FrSettings.flagForLanguages(scraper.contentLanguage)} ${scraper.name}$recLabel" +
                        disabledLabel
                val nameView = TextView(dialogContext).apply {
                    text = labelText
                    textSize = 14f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val repoView = TextView(dialogContext).apply {
                    text = "    " + NuvioClient.repoLabel(scraper.repoBase)
                    textSize = 12f
                    alpha = 0.7f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                fun moveTo(target: Int) {
                    if (target < 0 || target >= ordered.size || target == index) return
                    ordered.add(target, ordered.removeAt(index))
                    persistOrder()
                    render()
                }
                val textColumn = LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(nameView)
                    addView(repoView)
                }
                val row = LinearLayout(dialogContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val rowVPad = (density * 4).toInt()
                    setPadding(0, rowVPad, 0, rowVPad)
                    addView(textColumn)
                    addView(arrowButton("⏫", L10n.t("Tout en haut", "To the top"), index > 0) { moveTo(0) })
                    addView(
                        arrowButton("▲", L10n.t("Monter d'une place", "Move up one"), index > 0) {
                            moveTo(index - 1)
                        },
                    )
                    addView(
                        arrowButton("▼", L10n.t("Descendre d'une place", "Move down one"), index < ordered.size - 1) {
                            moveTo(index + 1)
                        },
                    )
                    addView(
                        arrowButton("⏬", L10n.t("Tout en bas", "To the bottom"), index < ordered.size - 1) {
                            moveTo(ordered.size - 1)
                        },
                    )
                }
                itemsContainer.addView(row)
            }
        }
        render()

        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Classer les sources Nuvio (${ordered.size})", "Rank the Nuvio sources (${ordered.size})"))
            .setView(
                ScrollView(dialogContext).apply {
                    addView(container)
                },
            )
            .setPositiveButton(L10n.t("Terminé", "Done"), null)
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
                text = L10n.t(
                    "N° 1 = critère le plus souhaité. Un flux est classé d'après le premier critère qu'il " +
                        "satisfait, puis le suivant (VF 720p passe avant VOSTFR 1080p si VF est devant). " +
                        "L'ordre est enregistré à chaque déplacement. Ajoutez vos propres langues " +
                        "(EN, TR, …) et qualités avec les boutons en bas.",
                    "No. 1 = most wanted criterion. A stream is ranked by the first criterion it satisfies, " +
                        "then the next one (VF 720p comes before VOSTFR 1080p when VF is ahead). " +
                        "The order is saved on every move. Add your own languages (EN, TR, …) and " +
                        "qualities with the buttons at the bottom.",
                )
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
                val isLanguage = criterion in StreamLabel.LANGUAGE_ORDER ||
                    FrSettings.customLanguages.any { it.equals(criterion, true) }
                val labelText = "${index + 1}. " +
                    when {
                        isLanguage && criterion in StreamLabel.LANGUAGE_ORDER ->
                            "🗣️ ${StreamLabel.languageLabel(criterion)}"

                        isLanguage ->
                            "${FrSettings.LANGUAGE_FLAGS[criterion.lowercase()] ?: "🌐"} " +
                                "${criterion.uppercase()} — ${L10n.t("langue", "language")}"

                        else ->
                            "🎞️ ${StreamLabel.qualityValue(criterion)?.let(StreamLabel::qualityLabel) ?: criterion}"
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
                    addView(arrowButton("⏫", L10n.t("Tout en haut", "To the top"), index > 0) { moveTo(0) })
                    addView(
                        arrowButton("▲", L10n.t("Monter d'une place", "Move up one"), index > 0) {
                            moveTo(index - 1)
                        },
                    )
                    addView(
                        arrowButton("▼", L10n.t("Descendre d'une place", "Move down one"), index < ordered.size - 1) {
                            moveTo(index + 1)
                        },
                    )
                    addView(
                        arrowButton("⏬", L10n.t("Tout en bas", "To the bottom"), index < ordered.size - 1) {
                            moveTo(ordered.size - 1)
                        },
                    )
                }
                itemsContainer.addView(row)
            }
        }

        val addQuality = Button(dialogContext).apply {
            text = L10n.t("+ Ajouter une qualité", "+ Add a quality")
            contentDescription = L10n.t(
                "Ajouter une résolution personnalisée",
                "Add a custom resolution",
            )
            setOnClickListener {
                val input = EditText(dialogContext).apply {
                    hint = "540p / 540 / 2160p / 8K"
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                AlertDialog.Builder(dialogContext)
                    .setTitle(L10n.t("Ajouter une qualité", "Add a quality"))
                    .setMessage(
                        L10n.t(
                            "Saisissez une résolution entre 144p et 8640p (540p, 540, 2160p, 8K…).",
                            "Enter a resolution between 144p and 8640p (540p, 540, 2160p, 8K…).",
                        ),
                    )
                    .setView(input)
                    .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
                    .setPositiveButton(L10n.t("Ajouter", "Add")) { _, _ ->
                        val rawQuality = input.text.toString().trim().uppercase()
                        val quality = StreamLabel.qualityValue(rawQuality)
                            ?: Regex("(\\d{3,4})").find(rawQuality)?.groupValues?.get(1)?.toIntOrNull()
                                ?.takeIf { it in 144..8640 }
                        if (quality == null || quality !in 144..8640) {
                            displayToast(
                                L10n.t(
                                    "Résolution non reconnue (ex. 540p, 1080p, 4K)",
                                    "Unrecognized resolution (e.g. 540p, 1080p, 4K)",
                                ),
                            )
                        } else {
                            val token = StreamLabel.qualityText(quality)
                            if (token !in ordered) ordered.add(token)
                            val customs = (FrSettings.customQualities + quality).distinct().sortedDescending()
                            preferences.edit()
                                .putString(
                                    FrSettings.KEY_CUSTOM_QUALITIES,
                                    customs.joinToString("\n") { "${it}p" },
                                )
                                .putString(FrSettings.KEY_STREAM_ORDER, ordered.joinToString("\n"))
                                .commit()
                            render()
                        }
                    }
                    .show()
            }
        }
        val addLanguage = Button(dialogContext).apply {
            text = L10n.t("+ Ajouter une langue", "+ Add a language")
            contentDescription = L10n.t(
                "Ajouter une langue classable (EN, TR, ES…)",
                "Add a rankable language (EN, TR, ES…)",
            )
            setOnClickListener {
                val options = FrSettings.ADDABLE_STREAM_LANGUAGES
                    .filterNot { code ->
                        ordered.any { it.equals(code.uppercase(), true) }
                    }
                    .map { code ->
                        "${FrSettings.flagLabel(code, code.uppercase())} — $code"
                    }
                    .toTypedArray()
                if (options.isEmpty()) {
                    displayToast(
                        L10n.t(
                            "Toutes les langues proposées sont déjà classées",
                            "All suggested languages are already ranked",
                        ),
                    )
                    return@setOnClickListener
                }
                AlertDialog.Builder(dialogContext)
                    .setTitle(L10n.t("Ajouter une langue au classement", "Add a language to the ranking"))
                    .setItems(options) { dialog, which ->
                        val code = FrSettings.ADDABLE_STREAM_LANGUAGES
                            .filterNot { existing ->
                                ordered.any { it.equals(existing.uppercase(), true) }
                            }[which]
                        val token = code.uppercase()
                        if (token !in ordered) ordered.add(token)
                        val customs = (FrSettings.customLanguages + token).distinct()
                        preferences.edit()
                            .putString(FrSettings.KEY_STREAM_LANGUAGES, customs.joinToString("\n"))
                            .putString(FrSettings.KEY_STREAM_ORDER, ordered.joinToString("\n"))
                            .commit()
                        render()
                        dialog.dismiss()
                    }
                    .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
                    .show()
            }
        }
        container.addView(addQuality)
        container.addView(addLanguage)
        container.addView(itemsContainer)
        render()

        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Classer les flux (langues et qualités)", "Rank streams (languages and qualities)"))
            .setView(
                ScrollView(dialogContext).apply {
                    addView(container)
                },
            )
            .setNeutralButton(L10n.t("Ordre conseillé", "Recommended order"), null)
            .setPositiveButton(L10n.t("Terminé", "Done")) { _, _ -> persistOrder() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                ordered.clear()
                ordered.addAll((FrSettings.DEFAULT_STREAM_ORDER + FrSettings.STREAM_CRITERIA).distinct())
                persistOrder()
                render()
                displayToast(
                    L10n.t(
                        "Ordre conseillé rétabli : VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K…",
                        "Recommended order restored: VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K…",
                    ),
                )
            }
        }
        runCatching { dialog.show() }
    }

    /**
     * Création d'une sauvegarde sans lien obligatoire : copie presse-papiers,
     * partage vers l'application ou le dossier de son choix, ou enregistrement
     * direct dans le dossier Téléchargements.
     */
    private fun exportBackup(dialogContext: Context) {
        val json = SettingsBackup.export(preferences)
        val items = arrayOf(
            L10n.t("Copier dans le presse-papiers", "Copy to the clipboard"),
            L10n.t("Partager (choisir une app ou un dossier)", "Share (choose an app or a folder)"),
            L10n.t("Enregistrer dans le dossier Téléchargements", "Save to the Downloads folder"),
        )
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Créer une sauvegarde", "Create a backup"))
            .setMessage(
                L10n.t(
                    "${preferences.all.size} réglages prêts à l'export.",
                    "${preferences.all.size} settings ready to export.",
                ),
            )
            .setItems(items) { _, which ->
                when (which) {
                    0 -> copyBackupToClipboard(dialogContext, json)
                    1 -> shareBackup(dialogContext, json)
                    2 -> settingsScope.launch { saveBackupToFile(dialogContext, json) }
                }
            }
            .show()
    }

    private fun copyBackupToClipboard(dialogContext: Context, json: String) {
        val clipboard = dialogContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("FR Unifié — sauvegarde", json))
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Sauvegarde copiée", "Backup copied"))
            .setMessage(
                L10n.t(
                    "${preferences.all.size} réglages copiés dans le presse-papiers. " +
                        "Conservez ce JSON dans un endroit sûr.",
                    "${preferences.all.size} settings copied to the clipboard. " +
                        "Keep this JSON in a safe place.",
                ),
            )
            .setPositiveButton(L10n.t("Fermer", "Close"), null)
            .show()
    }

    private fun shareBackup(dialogContext: Context, json: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "FR Unifié — sauvegarde")
        }
        runCatching {
            dialogContext.startActivity(
                Intent.createChooser(send, L10n.t("Partager la sauvegarde", "Share the backup")),
            )
        }.onFailure {
            displayToast(
                L10n.t(
                    "Aucune application de partage trouvée",
                    "No sharing application found",
                ),
                Toast.LENGTH_LONG,
            )
        }
    }

    private fun saveBackupToFile(dialogContext: Context, json: String) {
        val name = "fr-unified-backup-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) +
            ".json"
        val result = runCatching {
            val resolver = dialogContext.contentResolver
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("insert")
                resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("write")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?: error("no downloads directory")
                dir.mkdirs()
                File(dir, name).writeText(json, Charsets.UTF_8)
            }
        }
        handler.post {
            displayToast(
                result.fold(
                    {
                        L10n.t(
                            "Sauvegarde enregistrée : $it",
                            "Backup saved: $it",
                        )
                    },
                    {
                        L10n.t(
                            "Échec de l'enregistrement : ${it.message?.take(80) ?: ""}",
                            "Save failed: ${it.message?.take(80) ?: ""}",
                        )
                    },
                ),
                Toast.LENGTH_LONG,
            )
        }
    }

    /**
     * Restauration sans lien obligatoire : choisir un fichier de sauvegarde
     * (dossier Téléchargements), coller le JSON, ou synchroniser depuis un lien.
     */
    private fun showBackupImportDialog(dialogContext: Context) {
        val files = listBackupFiles(dialogContext)
        val items = buildList {
            add(
                if (files.isEmpty()) {
                    L10n.t(
                        "Choisir un fichier de sauvegarde… (aucun trouvé)",
                        "Choose a backup file… (none found)",
                    )
                } else {
                    L10n.t(
                        "Choisir un fichier de sauvegarde… (${files.size} trouvé(s))",
                        "Choose a backup file… (${files.size} found)",
                    )
                },
            )
            add(L10n.t("Coller le JSON", "Paste the JSON"))
            add(L10n.t("Lien HTTPS (synchronisation)", "HTTPS link (sync)"))
        }
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Restaurer une sauvegarde", "Restore a backup"))
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (files.isEmpty()) {
                        displayToast(
                            L10n.t(
                                "Aucun fichier de sauvegarde trouvé dans Téléchargements",
                                "No backup file found in Downloads",
                            ),
                            Toast.LENGTH_LONG,
                        )
                    } else {
                        pickBackupFile(dialogContext, files)
                    }

                    1 -> showBackupPasteDialog(dialogContext)

                    2 -> showBackupSyncPopup(dialogContext)
                }
            }
            .show()
    }

    private data class BackupFileEntry(val name: String, val uri: Uri)

    /** Fichiers JSON de sauvegarde FR Unifié du dossier Téléchargements (best effort). */
    private fun listBackupFiles(dialogContext: Context): List<BackupFileEntry> = runCatching {
        val resolver = dialogContext.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads._ID)
            val cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                arrayOf("%.json"),
                MediaStore.Downloads.DATE_ADDED + " DESC",
            )
            val entries = buildList {
                cursor?.use { c ->
                    val nameIndex = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val idIndex = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    while (c.moveToNext()) {
                        val id = c.getLong(idIndex)
                        val fileName = c.getString(nameIndex) ?: continue
                        if (!fileName.lowercase().contains("fr-unified", true)) continue
                        add(
                            BackupFileEntry(
                                fileName,
                                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                            ),
                        )
                    }
                }
            }
            entries
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir?.exists() != true) {
                emptyList()
            } else {
                dir.listFiles { file ->
                    file.isFile &&
                        file.name.endsWith(".json", true) &&
                        file.name.contains("fr-unified", true)
                }
                    .orEmpty()
                    .sortedByDescending { it.lastModified() }
                    .map { BackupFileEntry(it.name, Uri.fromFile(it)) }
            }
        }
    }.getOrDefault(emptyList())

    private fun pickBackupFile(dialogContext: Context, files: List<BackupFileEntry>) {
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Choisir un fichier de sauvegarde", "Choose a backup file"))
            .setItems(files.map(BackupFileEntry::name).toTypedArray()) { _, which ->
                val entry = files[which]
                val result = runCatching {
                    dialogContext.contentResolver.openInputStream(entry.uri)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("read")
                }
                result.fold(
                    { raw -> applyBackup(dialogContext, raw) },
                    {
                        displayToast(
                            L10n.t(
                                "Impossible de lire le fichier : ${it.message?.take(80) ?: ""}",
                                "Cannot read the file: ${it.message?.take(80) ?: ""}",
                            ),
                            Toast.LENGTH_LONG,
                        )
                    },
                )
            }
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .show()
    }

    private fun showBackupPasteDialog(dialogContext: Context) {
        val input = EditText(dialogContext).apply {
            hint = L10n.t("Collez ici le JSON de sauvegarde", "Paste the backup JSON here")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 8
        }
        AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Coller le JSON de sauvegarde", "Paste the backup JSON"))
            .setMessage(
                L10n.t(
                    "Les réglages présents dans le JSON seront remplacés. " +
                        "Redémarrez ensuite l'application.",
                    "The settings present in the JSON will be replaced. " +
                        "Restart the application afterwards.",
                ),
            )
            .setView(input)
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Restaurer", "Restore")) { _, _ ->
                applyBackup(dialogContext, input.text.toString())
            }
            .show()
    }

    private fun applyBackup(dialogContext: Context, raw: String) {
        val result = runCatching { SettingsBackup.restore(preferences, raw) }
        displayToast(
            result.fold(
                {
                    L10n.t(
                        "$it réglage(s) restauré(s)",
                        "$it setting(s) restored",
                    )
                },
                {
                    L10n.t(
                        "Échec : ${it.message?.take(100)}",
                        "Failed: ${it.message?.take(100)}",
                    )
                },
            ),
            Toast.LENGTH_LONG,
        )
    }

    /**
     * Synchronisation par lien (facultatif, comme avant) : lien HTTPS vers un JSON
     * de sauvegarde + restauration automatique quotidienne.
     */
    private fun showBackupSyncPopup(dialogContext: Context) {
        val density = dialogContext.resources.displayMetrics.density
        val padding = (density * 12).toInt()
        val container = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val input = EditText(dialogContext).apply {
            hint = "https://…/backup.json"
            setText(FrSettings.backupUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
        }
        val autoRestore = CheckBox(dialogContext).apply {
            text = L10n.t(
                "Restaurer automatiquement chaque jour au lancement",
                "Restore automatically every day at startup",
            )
            isChecked = FrSettings.backupAutoRestore
        }
        container.addView(input)
        container.addView(autoRestore)
        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle(L10n.t("Synchronisation par lien (facultatif)", "Sync by link (optional)"))
            .setMessage(
                L10n.t(
                    "Lien direct vers un JSON de sauvegarde (GitHub Gist brut, serveur personnel…).",
                    "Direct link to a backup JSON (raw GitHub Gist, personal server…).",
                ),
            )
            .setView(container)
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setNeutralButton(L10n.t("Restaurer maintenant", "Restore now"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                saveBackupSync(input.text.toString(), autoRestore.isChecked)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val url = input.text.toString().trim()
                if (url.isEmpty() || !url.startsWith("https://")) {
                    displayToast(
                        L10n.t(
                            "Saisissez d'abord un lien https://",
                            "Enter an https:// link first",
                        ),
                        Toast.LENGTH_LONG,
                    )
                    return@setOnClickListener
                }
                saveBackupSync(url, autoRestore.isChecked)
                dialog.dismiss()
                displayToast(
                    L10n.t("Restauration depuis le lien…", "Restoring from the link…"),
                    Toast.LENGTH_LONG,
                )
                settingsScope.launch {
                    val result = runCatching { SettingsBackup.restore(preferences, FrRuntime.getText(url)) }
                    handler.post {
                        displayToast(
                            result.fold(
                                {
                                    L10n.t(
                                        "$it réglage(s) restauré(s)",
                                        "$it setting(s) restored",
                                    )
                                },
                                {
                                    L10n.t(
                                        "Échec : ${it.message?.take(100)}",
                                        "Failed: ${it.message?.take(100)}",
                                    )
                                },
                            ),
                            Toast.LENGTH_LONG,
                        )
                    }
                }
            }
        }
        runCatching { dialog.show() }
    }

    private fun saveBackupSync(url: String, autoRestore: Boolean) {
        val clean = url.trim()
        if (clean.isNotEmpty() && !clean.startsWith("https://")) {
            displayToast(
                L10n.t("Le lien doit commencer par https://", "The link must start with https://"),
                Toast.LENGTH_LONG,
            )
            return
        }
        preferences.edit()
            .putString(FrSettings.KEY_BACKUP_URL, clean)
            .putBoolean(FrSettings.KEY_BACKUP_AUTO_RESTORE, autoRestore)
            .apply()
        displayToast(L10n.t("Synchronisation enregistrée", "Sync saved"))
    }

    private fun runStremioUpdate(dialogContext: Context) {
        displayToast(L10n.t("Mise à jour Stremio en cours…", "Updating Stremio…"), Toast.LENGTH_LONG)
        settingsScope.launch {
            val report = runCatching { StremioCatalog.updateAddons() }
            handler.post {
                AlertDialog.Builder(dialogContext)
                    .setTitle(
                        if (report.isSuccess) {
                            L10n.t("Addons Stremio à jour", "Stremio addons up to date")
                        } else {
                            L10n.t("Mise à jour impossible", "Update failed")
                        },
                    )
                    .setMessage(
                        report.map { it.summary() }.getOrElse {
                            it.message ?: L10n.t("Erreur inconnue", "Unknown error")
                        },
                    )
                    .setPositiveButton(L10n.t("Fermer", "Close"), null)
                    .show()
            }
        }
    }

    /** Relecture immédiate des dépôts Nuvio et rafraîchissement de tous les scripts. */
    private fun runNuvioUpdate(dialogContext: Context) {
        displayToast(
            L10n.t("Mise à jour des sources Nuvio en cours…", "Updating Nuvio sources…"),
            Toast.LENGTH_LONG,
        )
        settingsScope.launch {
            val report = runCatching { NuvioClient.updateSources() }
            handler.post {
                AlertDialog.Builder(dialogContext)
                    .setTitle(
                        if (report.isSuccess) {
                            L10n.t("Sources Nuvio à jour", "Nuvio sources up to date")
                        } else {
                            L10n.t("Mise à jour impossible", "Update failed")
                        },
                    )
                    .setMessage(
                        report.map { it.summary() }.getOrElse { failure ->
                            failure.message?.take(300) ?: L10n.t("Erreur inconnue", "Unknown error")
                        },
                    )
                    .setPositiveButton(L10n.t("Fermer", "Close"), null)
                    .show()
            }
        }
    }

    private fun showNuvioDiagnostic(dialogContext: Context) {
        displayToast(
            L10n.t(
                "Diagnostic Nuvio réel en cours (jusqu’à cinq sources)…",
                "Real Nuvio diagnostics running (up to five sources)…",
            ),
            Toast.LENGTH_LONG,
        )
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
                val repoSummary = FrSettings.nuvioRepos.joinToString(", ") { NuvioClient.repoLabel(it) }
                buildString {
                    appendLine(engine)
                    appendLine()
                    appendLine(
                        L10n.t(
                            "Dépôts : ${FrSettings.nuvioRepos.size} ($repoSummary)",
                            "Repositories: ${FrSettings.nuvioRepos.size} ($repoSummary)",
                        ),
                    )
                    appendLine(L10n.t("Scrapeurs détectés : ${all.size}", "Scrapers detected: ${all.size}"))
                    appendLine(L10n.t("Scrapeurs actifs : ${active.size}", "Active scrapers: ${active.size}"))
                    if (checks.isEmpty()) {
                        appendLine()
                        append(L10n.t("Aucune source active à tester.", "No active source to test."))
                    } else {
                        appendLine()
                        appendLine(L10n.t("Tests réseau Android/Rhino :", "Android/Rhino network tests:"))
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
                    .setTitle(
                        if (report.isSuccess) {
                            L10n.t("Diagnostic Nuvio", "Nuvio diagnostics")
                        } else {
                            L10n.t("Diagnostic Nuvio impossible", "Nuvio diagnostics failed")
                        },
                    )
                    .setMessage(
                        report.getOrElse { failure ->
                            failure.message?.take(700) ?: L10n.t("Erreur inconnue", "Unknown error")
                        },
                    )
                    .setNegativeButton(L10n.t("Fermer", "Close"), null)
                    .setPositiveButton(L10n.t("Choisir les sources", "Choose the sources")) { _, _ ->
                        showNuvioPicker(dialogContext)
                    }
                    .show()
            }
        }
    }

    private fun showStremioCatalogPicker(dialogContext: Context) {
        displayToast(L10n.t("Chargement des catalogues Stremio…", "Loading Stremio catalogs…"))
        settingsScope.launch {
            val result = runCatching { StremioCatalog.catalogs() }
            val catalogs = result.getOrDefault(emptyList())
            val selected = runCatching { StremioCatalog.selectedCatalog() }.getOrNull()
            handler.post {
                if (catalogs.isEmpty()) {
                    displayToast(
                        result.exceptionOrNull()?.message?.let {
                            L10n.t(
                                "Catalogues Stremio indisponibles : ${it.take(100)}",
                                "Stremio catalogs unavailable: ${it.take(100)}",
                            )
                        } ?: L10n.t(
                            "Aucun addon actif n'expose de catalogue",
                            "No active addon exposes a catalog",
                        ),
                        Toast.LENGTH_LONG,
                    )
                    return@post
                }
                val selectedIndex = catalogs.indexOfFirst { it.key == selected?.key }.coerceAtLeast(0)
                AlertDialog.Builder(dialogContext)
                    .setTitle(L10n.t("Catalogue Stremio (${catalogs.size})", "Stremio catalog (${catalogs.size})"))
                    .setSingleChoiceItems(
                        catalogs.map(StremioCatalog.Catalog::label).toTypedArray(),
                        selectedIndex,
                    ) { dialog, index ->
                        preferences.edit()
                            .putString(FrSettings.KEY_STREMIO_CATALOG, catalogs[index].key)
                            .apply()
                        displayToast(
                            L10n.t("Catalogue : ${catalogs[index].label}", "Catalog: ${catalogs[index].label}"),
                        )
                        dialog.dismiss()
                    }
                    .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
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
                L10n.t(" ★ conseillée", " ★ recommended")
            } else {
                ""
            }
            SourceChoice("$flag $display$recommended", clean, FrSettings.isStremioEnabled(clean))
        }
        if (choices.isEmpty()) {
            displayToast(
                L10n.t("Aucun addon Stremio configuré", "No Stremio addon configured"),
                Toast.LENGTH_LONG,
            )
            return
        }
        val checked = BooleanArray(choices.size) { choices[it].enabled }
        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle(
                L10n.t(
                    "Stremio (${checked.count { it }}/${choices.size} actifs)",
                    "Stremio (${checked.count { it }}/${choices.size} active)",
                ),
            )
            .setMessage(
                L10n.t(
                    "Appuyez longuement pour supprimer un addon.",
                    "Long-press to remove an addon.",
                ),
            )
            .setMultiChoiceItems(
                choices.map(SourceChoice::label).toTypedArray(),
                checked,
            ) { _, index, value -> checked[index] = value }
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setNeutralButton(L10n.t("Tout activer", "Enable all"), null)
            .setPositiveButton(L10n.t("Enregistrer", "Save")) { _, _ ->
                val visible = choices.map(SourceChoice::value).toSet()
                val disabled = FrSettings.stremioDisabled.filterNot(visible::contains) +
                    choices.indices.filter { !checked[it] }.map { choices[it].value }
                preferences.edit()
                    .putString(FrSettings.KEY_STREMIO_DISABLED, disabled.distinct().joinToString("\n"))
                    .apply()
                displayToast(
                    L10n.t(
                        "Stremio : ${checked.count { it }} addon(s) actif(s)",
                        "Stremio: ${checked.count { it }} active addon(s)",
                    ),
                )
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                checked.indices.forEach { index ->
                    checked[index] = true
                    dialog.listView.setItemChecked(index, true)
                }
            }
            // Suppression d'un addon : retiré de la liste ET désactivé (les addons
            // par défaut ne doivent pas ressusciter via la fusion des valeurs).
            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                val addon = choices[position].value
                AlertDialog.Builder(dialogContext)
                    .setTitle(L10n.t("Supprimer l'addon", "Remove the addon"))
                    .setMessage(
                        L10n.t(
                            "Supprimer « ${addon.substringAfter("://").removeSuffix("/manifest.json")} » ?",
                            "Remove « ${addon.substringAfter("://").removeSuffix("/manifest.json")} »?",
                        ),
                    )
                    .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
                    .setPositiveButton(L10n.t("Supprimer", "Remove")) { _, _ ->
                        val remaining = FrSettings.stremioUrls.filter { it != addon }
                        val disabled = FrSettings.stremioDisabled + addon
                        preferences.edit()
                            .putString(FrSettings.KEY_STREMIO, remaining.joinToString("\n"))
                            .putString(FrSettings.KEY_STREMIO_DISABLED, disabled.distinct().joinToString("\n"))
                            .apply()
                        displayToast(
                            L10n.t("Addon supprimé", "Addon removed"),
                            Toast.LENGTH_LONG,
                        )
                    }
                    .show()
                true
            }
        }
        runCatching { dialog.show() }
            .onFailure {
                displayToast(
                    L10n.t(
                        "Impossible d'ouvrir le sélecteur Stremio",
                        "Cannot open the Stremio picker",
                    ),
                    Toast.LENGTH_LONG,
                )
            }
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
            .setTitle(L10n.t("Ajouter — $kindName", "Add — $kindName"))
            .setMessage(
                L10n.t(
                    "Cette entrée accepte uniquement le format $kindName.",
                    "This entry only accepts the $kindName format.",
                ),
            )
            .setView(input)
            .setNegativeButton(L10n.t("Annuler", "Cancel"), null)
            .setPositiveButton(L10n.t("Analyser", "Parse")) { _, _ ->
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
            displayToast(L10n.t("Saisissez une URL", "Enter a URL"), Toast.LENGTH_LONG)
            return
        }
        displayToast(
            L10n.t("Analyse ${externalKindName(expectedKind)}…", "Parsing ${externalKindName(expectedKind)}…"),
        )
        settingsScope.launch {
            val outcome = runCatching {
                val result = ExternalSourceImporter.inspect(input)
                require(result.kind == expectedKind) {
                    L10n.t(
                        "Format ${externalKindName(result.kind)} détecté. " +
                            "Ajoutez cette URL depuis la section ${externalKindName(result.kind)}.",
                        "${externalKindName(result.kind)} format detected. " +
                            "Add this URL from the ${externalKindName(result.kind)} section.",
                    )
                }
                result
            }
            outcome.onSuccess(::persistExternalSource)
            handler.post {
                outcome.onSuccess { result ->
                    AlertDialog.Builder(dialogContext)
                        .setTitle(
                            L10n.t(
                                "${externalKindName(result.kind)} ajouté",
                                "${externalKindName(result.kind)} added",
                            ),
                        )
                        .setMessage(result.summary)
                        .setNegativeButton(L10n.t("Fermer", "Close"), null)
                        .setPositiveButton(L10n.t("Ouvrir la section", "Open the section")) { _, _ ->
                            when (result.kind) {
                                ExternalSourceImporter.Kind.NUVIO -> showNuvioPicker(dialogContext)
                                ExternalSourceImporter.Kind.STREMIO -> showStremioPicker(dialogContext)
                            }
                        }
                        .show()
                }.onFailure { failure ->
                    AlertDialog.Builder(dialogContext)
                        .setTitle(L10n.t("Import impossible", "Import failed"))
                        .setMessage(failure.message?.take(500) ?: L10n.t("Format non reconnu", "Unrecognized format"))
                        .setPositiveButton(L10n.t("Fermer", "Close"), null)
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
            return if (index >= 0 && index < entries.size) {
                entries[index]
            } else {
                L10n.t("Choisir une valeur", "Choose a value")
            }
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

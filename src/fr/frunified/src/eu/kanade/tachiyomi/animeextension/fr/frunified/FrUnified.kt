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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Port Aniyomi de FR Unifié.
 *
 * Aniyomi isole les APK d'extensions : une extension ne peut pas appeler de façon
 * fiable les autres extensions installées. Cette version conserve donc le catalogue
 * unique TMDB/AniList et agrège directement les scrapeurs Nuvio et addons Stremio.
 */
class FrUnified : Source() {
    override val name = "FR Unifié"
    override val lang = "fr"
    override val baseUrl = "https://www.themoviedb.org"
    override val supportsLatest = true

    override val migration: SharedPreferences.() -> Unit = {
        val version = (all[FrSettings.KEY_SETTINGS_VERSION] as? Number)?.toInt() ?: 0
        if (version < FrSettings.SETTINGS_VERSION) {
            edit().apply {
                val stremio = all[FrSettings.KEY_STREMIO] as? String
                if (stremio.isNullOrBlank()) {
                    putString(FrSettings.KEY_STREMIO, FrSettings.DEFAULT_STREMIO_ADDONS.joinToString("\n"))
                }
                if (!all.containsKey(FrSettings.KEY_CLOUDSTREAM_REPOS)) {
                    putString(
                        FrSettings.KEY_CLOUDSTREAM_REPOS,
                        FrSettings.DEFAULT_CLOUDSTREAM_REPOS.joinToString("\n"),
                    )
                }
                putInt(FrSettings.KEY_SETTINGS_VERSION, FrSettings.SETTINGS_VERSION)
                apply()
            }
        }
    }

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        FrSettings.init(preferences)
        FrRuntime.init(client)
        NuvioClient.init(context)
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.7")

    // ----------------------------------------------- accueil et recherche

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val items = when (FrSettings.popularCatalog) {
            "movies" -> TmdbCatalog.row("movie/popular", page, kind = "movie")

            "series" -> TmdbCatalog.row("tv/popular", page, kind = "tv")

            "anime" -> AnimeCatalog.row("popular", page)

            else -> coroutineScope {
                listOf(
                    async { TmdbCatalog.row("trending/all/week", page) },
                    async { AnimeCatalog.row("trending", page) },
                ).awaitAll().flatten().deduplicate()
            }
        }
        return AnimesPage(items.map(CatalogItem::toSAnime), items.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = coroutineScope {
        val items = listOf(
            async { TmdbCatalog.row("movie/now_playing", page, kind = "movie") },
            async { TmdbCatalog.row("tv/on_the_air", page, kind = "tv") },
            async { AnimeCatalog.row("trending", page) },
        ).awaitAll().flatten().deduplicate()
        AnimesPage(items.map(CatalogItem::toSAnime), items.isNotEmpty())
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = coroutineScope {
        if (query.isBlank()) return@coroutineScope getPopularAnime(page)
        val type = filters.filterIsInstance<ContentTypeFilter>().firstOrNull()?.value ?: "all"
        val jobs = buildList {
            if (FrSettings.useTmdbCatalog && type != "anime") {
                add(
                    async {
                        TmdbCatalog.search(query, page).filter {
                            type == "all" || it.id.kind == type
                        }
                    },
                )
            }
            if (FrSettings.useAnimeCatalog && type in setOf("all", "anime")) {
                add(async { AnimeCatalog.search(query, page) })
            }
        }
        val items = jobs.awaitAll().flatten().deduplicate()
        AnimesPage(items.map(CatalogItem::toSAnime), items.isNotEmpty())
    }

    private fun List<CatalogItem>.deduplicate(): List<CatalogItem> {
        val seen = hashSetOf<String>()
        return filter { seen.add("${TitleMatch.normalize(it.title)}:${it.year}") }
    }

    class ContentTypeFilter : AnimeFilter.Select<String>(
        "Type de contenu",
        arrayOf("Tout", "Films", "Séries", "Animés"),
    ) {
        val value: String
            get() = arrayOf("all", "movie", "tv", "anime")[state]
    }

    override fun getFilterList() = AnimeFilterList(ContentTypeFilter())

    // --------------------------------------------------------------- fiche

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = CatalogId.parse(anime.url) ?: return anime
        return when (id.catalog) {
            "tmdb" -> tmdbDetails(id, anime)
            "anilist" -> aniListDetails(id, anime)
            "mal" -> malDetails(id, anime)
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

    private fun detailsDescription(item: CatalogItem, suffix: String): String = buildString {
        item.overview?.takeIf(String::isNotBlank)?.let { append(it.trim()) }
        if (isNotEmpty()) append("\n\n")
        append(suffix)
    }

    private fun sourceSummary(): String = buildString {
        append("Liens : scrapeurs Nuvio et passerelles CloudStream")
        if (FrSettings.stremioUrls.isNotEmpty()) append(" + ${FrSettings.stremioUrls.size} addon(s) Stremio")
        append(". Les autres extensions Aniyomi installées ne sont pas accessibles entre APK.")
    }

    // ------------------------------------------------------------- saisons

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val id = CatalogId.parse(anime.url) ?: return emptyList()
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

    // ------------------------------------------------------------- épisodes

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = CatalogId.parse(anime.url) ?: return emptyList()
        return when (id.catalog) {
            "tmdb" -> tmdbEpisodes(id)
            "anilist" -> aniListEpisodes(id)
            "mal" -> malEpisodes(id)
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
        val count = if (isMovie) 1 else media.optInt("episodes").takeIf { it > 0 } ?: 24
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
        val count = if (isMovie) 1 else data.optInt("episodes").takeIf { it > 0 } ?: 24
        return genericAnimeEpisodes(
            titles = JikanCatalog.allTitles(data).ifEmpty { item.titles },
            year = item.year,
            count = count,
            isMovie = isMovie,
            anilistId = null,
            malId = id.id.toIntOrNull(),
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
        val nuvio = async {
            val out = java.util.concurrent.CopyOnWriteArrayList<Video>()
            runCatching { NuvioClient.streams(payload) { out += it } }
            out.toList()
        }
        val stremio = async { runCatching { StremioClient.streams(payload) }.getOrDefault(emptyList()) }
        val subtitles = async { runCatching { StremioClient.subtitles(payload) }.getOrDefault(emptyList()) }

        val tracks = subtitles.await()
        val videos = (nuvio.await() + stremio.await())
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
            .sortedWith(compareByDescending<Video> { it.preferred }.thenByDescending { it.resolution ?: 0 })

        videos.groupBy { video ->
            video.videoTitle.substringBefore(" • ").substringBefore(" · ").ifBlank { "FR Unifié" }
        }.map { (provider, providerVideos) ->
            Hoster(
                hosterUrl = "frunified://${provider.hashCode()}",
                hosterName = provider,
                videoList = providerVideos,
            )
        }.sortedBy { it.hosterName.lowercase() }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    override fun List<Hoster>.sortHosters(): List<Hoster> = sortedBy { it.hosterName.lowercase() }

    override fun List<Video>.sortVideos(): List<Video> =
        sortedWith(compareByDescending<Video> { it.preferred }.thenByDescending { it.resolution ?: 0 })

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
        action(
            "Gérer les sources actives",
            "Fenêtre multi-sélection Nuvio, Stremio et passerelles CloudStream.",
        ) { showSourcePicker(context) }
        action(
            "Ajouter Nuvio / Stremio / CloudStream",
            "Ajouter un manifest, un addon ou un dépôt CloudStream repo.json.",
        ) { showExternalSourceDialog(context) }

        switch(FrSettings.KEY_USE_TMDB, true, "Catalogue TMDB", "Films et séries en français")
        switch(
            FrSettings.KEY_USE_ANIME,
            true,
            "Catalogue AniList / MAL",
            "Animés via AniList, avec repli MyAnimeList",
        )
        list(
            FrSettings.KEY_POPULAR,
            "mixed",
            "Accueil populaire",
            arrayOf("Mixte", "Films", "Séries", "Animés"),
            arrayOf("mixed", "movies", "series", "anime"),
        )
        edit(
            FrSettings.KEY_TMDB,
            FrSettings.DEFAULT_TMDB_KEY,
            "Clé API TMDB",
            "Laisser la valeur proposée ou saisir votre clé v3.",
            multiline = false,
        )

        switch(
            FrSettings.KEY_USE_NUVIO,
            true,
            "Sources Nuvio — activer",
            "Scrapeurs français intégrés au moment de la lecture",
        )
        edit(
            FrSettings.KEY_NUVIO_REPOS,
            FrSettings.DEFAULT_NUVIO_REPOS.joinToString("\n"),
            "Dépôts Nuvio",
            "Une URL manifest.json par ligne.",
        )
        edit(
            FrSettings.KEY_NUVIO_REPOS_DISABLED,
            "",
            "Dépôts Nuvio désactivés",
            "Une URL exacte par ligne.",
        )
        edit(FrSettings.KEY_NUVIO_DISABLED, "", "Scrapeurs désactivés", "Un identifiant par ligne.")
        edit(FrSettings.KEY_NUVIO_ORDER, "", "Ordre des scrapeurs", "Identifiants prioritaires, un par ligne.")
        edit(
            FrSettings.KEY_NUVIO_PRIORITY,
            FrSettings.DEFAULT_NUVIO_PRIORITY.joinToString(","),
            "Priorité des flux",
            "Motifs séparés par des virgules (VF, VOSTFR, 1080…).",
            multiline = false,
        )
        list(
            FrSettings.KEY_NUVIO_MAX,
            "12",
            "Flux maximum par scrapeur",
            arrayOf("4", "8", "12", "20", "Illimité"),
            arrayOf("4", "8", "12", "20", "0"),
        )
        list(
            FrSettings.KEY_NUVIO_CONCURRENCY,
            "6",
            "Scrapeurs simultanés",
            arrayOf("2", "4", "6", "8", "12"),
            arrayOf("2", "4", "6", "8", "12"),
        )
        switch(
            FrSettings.KEY_NUVIO_ALL,
            false,
            "Nuvio — toutes les langues",
            "Par défaut, seuls les scrapeurs français sont utilisés",
        )

        switch(
            FrSettings.KEY_USE_STREMIO,
            true,
            "Addons Stremio — activer",
            "Utiliser les addons configurés ci-dessous",
        )
        edit(
            FrSettings.KEY_STREMIO,
            FrSettings.DEFAULT_STREMIO_ADDONS.joinToString("\n"),
            "Addons Stremio",
            "Une URL manifest.json par ligne.",
        )
        edit(
            FrSettings.KEY_CLOUDSTREAM_REPOS,
            FrSettings.DEFAULT_CLOUDSTREAM_REPOS.joinToString("\n"),
            "Dépôts CloudStream suivis",
            "Références repo.json importées ; les providers compatibles passent par Nuvio.",
        )
        switch(FrSettings.KEY_USE_SUBS, true, "Sous-titres externes", "Inclut OpenSubtitles v3")
        edit(
            FrSettings.KEY_SUB_LANGS,
            "fre,fra,fr,eng,en",
            "Langues des sous-titres",
            "Codes séparés par des virgules.",
            multiline = false,
        )

        edit(FrSettings.KEY_TOKENS, "", "Clés API avancées", "Une ligne NOM=valeur ; injectée dans process.env.")
        edit(
            FrSettings.KEY_UA,
            FrSettings.DEFAULT_USER_AGENT,
            "User-Agent Nuvio",
            "User-Agent des requêtes JS.",
            false,
        )
        edit(
            FrSettings.KEY_REFERER,
            "https://www.google.com/",
            "Referer Nuvio",
            "Referer HTTP par défaut.",
            false,
        )
        edit(FrSettings.KEY_COOKIES, "", "Cookies Nuvio", "Cookies facultatifs pour les domaines protégés.")
    }

    private enum class ChoiceKind { NUVIO, STREMIO }

    private data class SourceChoice(
        val label: String,
        val value: String,
        val kind: ChoiceKind,
        val enabled: Boolean,
    )

    private fun showSourcePicker(dialogContext: Context) {
        displayToast("Chargement des sources…")
        settingsScope.launch {
            val nuvioResult = runCatching { NuvioClient.scrapers(includeDisabled = true) }
            val choices = nuvioResult.getOrDefault(emptyList()).map { scraper ->
                val origin = scraper.repoBase.substringAfter("githubusercontent.com/")
                    .split('/').take(2).joinToString("/").takeIf(String::isNotBlank)
                    ?: scraper.repoBase.substringAfter("://").substringBefore('/')
                val family = if (ExternalSourceImporter.isCloudStreamBridge(scraper.id)) {
                    "CloudStream→Nuvio"
                } else {
                    "Nuvio"
                }
                SourceChoice(
                    label = "${scraper.name}  ·  $family/$origin",
                    value = scraper.id,
                    kind = ChoiceKind.NUVIO,
                    enabled = FrSettings.isNuvioEnabled(scraper.id),
                )
            } +
                FrSettings.stremioUrls.distinct().map { addon ->
                    val clean = StremioClient.base(addon)
                    SourceChoice(
                        label = clean.substringAfter("://").substringBefore('/') + "  ·  Stremio",
                        value = clean,
                        kind = ChoiceKind.STREMIO,
                        enabled = FrSettings.isStremioEnabled(clean),
                    )
                }
            handler.post {
                if (choices.isEmpty()) {
                    displayToast(
                        nuvioResult.exceptionOrNull()?.message?.let { "Sources indisponibles : ${it.take(100)}" }
                            ?: "Aucune source configurée",
                        Toast.LENGTH_LONG,
                    )
                    return@post
                }
                val checked = BooleanArray(choices.size) { choices[it].enabled }
                val dialog = AlertDialog.Builder(dialogContext)
                    .setTitle("Sources actives (${checked.count { it }}/${choices.size})")
                    .setMultiChoiceItems(
                        choices.map(SourceChoice::label).toTypedArray(),
                        checked,
                    ) { _, index, value -> checked[index] = value }
                    .setNegativeButton("Annuler", null)
                    .setNeutralButton("Tout activer", null)
                    .setPositiveButton("Enregistrer") { _, _ ->
                        val visibleNuvio = choices.filter { it.kind == ChoiceKind.NUVIO }.map { it.value }.toSet()
                        val visibleStremio = choices.filter { it.kind == ChoiceKind.STREMIO }.map { it.value }.toSet()
                        val disabledNuvio = FrSettings.nuvioDisabled.filterNot(visibleNuvio::contains) +
                            choices.indices.filter { !checked[it] && choices[it].kind == ChoiceKind.NUVIO }
                                .map { choices[it].value }
                        val disabledStremio = FrSettings.stremioDisabled.filterNot(visibleStremio::contains) +
                            choices.indices.filter { !checked[it] && choices[it].kind == ChoiceKind.STREMIO }
                                .map { choices[it].value }
                        preferences.edit()
                            .putString(FrSettings.KEY_NUVIO_DISABLED, disabledNuvio.distinct().joinToString("\n"))
                            .putString(FrSettings.KEY_STREMIO_DISABLED, disabledStremio.distinct().joinToString("\n"))
                            .apply()
                        displayToast("Sélection enregistrée : ${checked.count { it }} source(s) active(s)")
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
                    .onFailure { displayToast("Impossible d'ouvrir la fenêtre de sources", Toast.LENGTH_LONG) }
            }
        }
    }

    private fun showExternalSourceDialog(dialogContext: Context) {
        val input = EditText(dialogContext).apply {
            hint = "https://…/manifest.json ou …/repo.json"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false)
            minLines = 2
        }
        AlertDialog.Builder(dialogContext)
            .setTitle("Ajouter une source externe")
            .setMessage("Formats acceptés : dépôt Nuvio, addon Stremio ou dépôt CloudStream repo.json.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Analyser") { _, _ -> importExternalSource(dialogContext, input.text.toString()) }
            .show()
    }

    private fun importExternalSource(dialogContext: Context, input: String) {
        if (input.isBlank()) {
            displayToast("Saisissez une URL", Toast.LENGTH_LONG)
            return
        }
        displayToast("Analyse de la source…")
        settingsScope.launch {
            val outcome = runCatching { ExternalSourceImporter.inspect(input) }
            outcome.onSuccess(::persistExternalSource)
            handler.post {
                outcome.onSuccess { result ->
                    val detail = if (result.kind == ExternalSourceImporter.Kind.CLOUDSTREAM) {
                        result.summary +
                            "\n\n" +
                            "Aniyomi ne peut pas charger directement un fichier .cs3. " +
                            "Les équivalents reconnus ont été activés dans le moteur Nuvio."
                    } else {
                        result.summary
                    }
                    AlertDialog.Builder(dialogContext)
                        .setTitle("Source ajoutée")
                        .setMessage(detail)
                        .setNegativeButton("Fermer", null)
                        .setPositiveButton("Choisir les sources") { _, _ -> showSourcePicker(dialogContext) }
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

            ExternalSourceImporter.Kind.CLOUDSTREAM -> {
                val values = (FrSettings.cloudstreamRepos + result.url).distinct()
                editor.putString(FrSettings.KEY_CLOUDSTREAM_REPOS, values.joinToString("\n"))
                editor.putString(
                    FrSettings.KEY_NUVIO_DISABLED,
                    FrSettings.nuvioDisabled.filterNot(result.nuvioIds::contains).joinToString("\n"),
                )
            }
        }
        editor.apply()
    }

    private fun PreferenceScreen.action(title: String, summary: String, onClick: () -> Unit) {
        addPreference(
            EditTextPreference(context).apply {
                this.title = title
                this.summary = summary
                setOnPreferenceClickListener {
                    onClick()
                    true
                }
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

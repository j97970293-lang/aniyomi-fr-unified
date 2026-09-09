package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.Context
import com.frunified.rhino.BaseFunction
import com.frunified.rhino.NativeArray
import com.frunified.rhino.ScriptRuntime
import com.frunified.rhino.Scriptable
import com.frunified.rhino.ScriptableObject
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.frunified.rhino.Context as RhinoContext

/**
 * Moteur « Nuvio » : exécute les scrapeurs JavaScript locaux du projet Nuvio
 * (dépôts Gowaru, Phisher, Michat88…) directement dans l'extension.
 *
 * Chaque scrapeur expose `getStreams(tmdbId, mediaType, seasonNum, episodeNum)`
 * et retourne une liste de flux. On fournit un environnement JS
 * (fetch, Promise, URL, Base64, setTimeout…) branché sur le réseau Android,
 * puis on convertit directement les résultats en [Video] pour le lecteur Aniyomi.
 */
object NuvioClient {

    private const val SCRIPT_TTL_MS = 12 * 60 * 60 * 1000L // 12 h
    private const val MANIFEST_TTL_MS = 6 * 60 * 60 * 1000L // 6 h
    private const val SCRAPER_TIMEOUT_MS = 40_000L
    private const val NUVIO_CONCURRENCY = 3

    private const val NETWORK_TIMEOUT_MS = 10_000
    private const val PROBE_TIMEOUT_MS = 5_000
    private const val RHINO_STACK_BYTES = 2L * 1024 * 1024

    data class NuvioScraper(
        val id: String,
        val name: String,
        val filename: String,
        val repoBase: String,
        val supportedTypes: List<String>,
        val contentLanguage: List<String>,
        val description: String,
        val manifestEnabled: Boolean = true,
        /** Valeurs par défaut des variables d'environnement déclarées par le manifest. */
        val envDefaults: Map<String, String> = emptyMap(),
        /** Clés d'environnement obligatoires (le site n'exécute pas sans elles). */
        val requiredEnv: List<String> = emptyList(),
    ) {
        val isFrench: Boolean get() = contentLanguage.any { it.startsWith("fr") }
        val scriptUrl: String
            get() = if (filename.startsWith("http://") || filename.startsWith("https://")) {
                filename
            } else {
                ("$repoBase/$filename")
                    .replace(Regex("https://+"), "https://")
                    .replace(Regex("http://+"), "http://")
            }
    }

    private val manifestCache = ConcurrentHashMap<String, Pair<Long, List<NuvioScraper>>>()
    private val tmdbCache = ConcurrentHashMap<String, Pair<Long, Int?>>()

    private data class RenewalContext(val scraperId: String, val payload: PlayPayload, val createdAt: Long)
    private val renewalContexts = ConcurrentHashMap<String, RenewalContext>()

    @Volatile private var semaphoreCapacity = NUVIO_CONCURRENCY

    @Volatile private var semaphore = Semaphore(semaphoreCapacity)

    /** Sémaphore des seuls TESTS (2 max) : ne bloque jamais la lecture réelle. */
    private val testSemaphore = Semaphore(2)

    /** Borne les sondes de revérification au clic (indépendant de la concurrence Nuvio). */
    private val probeLimiter = Semaphore(6)

    /** Re-crée le sémaphore uniquement si le réglage a changé, pas lorsqu'un permit est occupé. */
    @Synchronized
    private fun syncSemaphore(): Semaphore {
        val wanted = FrSettings.nuvioConcurrency
        if (semaphoreCapacity != wanted) {
            semaphore = Semaphore(wanted)
            semaphoreCapacity = wanted
        }
        return semaphore
    }

    /** Résultats du dernier passage (id scrapeur -> « ✓ 12 liens » ou « ✗ raison »). */
    private val lastResults = ConcurrentHashMap<String, String>()
    fun diagnostics(): Map<String, String> = lastResults.toMap()

    /** Télémétrie par scrapeur : dernières requêtes HTTP et lignes console JS. */
    private val fetchLog = ConcurrentHashMap<String, MutableList<String>>()
    private val consoleLog = ConcurrentHashMap<String, List<String>>()
    private val currentScraper = ThreadLocal<String?>()

    @Volatile
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (cacheDir == null) {
            cacheDir = runCatching { File(context.filesDir, "nuvio-v8").apply { mkdirs() } }.getOrNull()
        }
    }

    fun invalidateRepository(repo: String) {
        manifestCache.remove(repo.trim())
    }

    // ------------------------------------------------------------ dépôts

    /** Tous les scrapeurs des dépôts configurés, avec inclusion facultative des désactivés pour l'interface. */
    suspend fun scrapers(includeDisabled: Boolean = false): List<NuvioScraper> = coroutineScope {
        // La liste explicitement vidée (tous les dépôts supprimés) reste vide.
        val repos = FrSettings.nuvioRepos.filter { FrSettings.isNuvioRepoEnabled(it) }
        val loaded = repos.map { repo ->
            async { withTimeoutOrNull(20_000L) { manifest(repo) }.orEmpty() }
        }.awaitAll().flatten()
        selectableScrapers(loaded, includeDisabled)
    }

    /**
     * Libellé du dépôt d'origine d'une source, comme dans l'application NuviO :
     * « D3adlyRocket/Anime-Nuvio » pour un dépôt GitHub, « serveur.com/nuvio » sinon.
     * L'utilisateur doit toujours savoir de quel dépôt vient chaque site (dépôt
     * français ou international).
     */
    fun repoLabel(repoBase: String): String {
        val noScheme = repoBase.trim().substringAfter("://").removeSuffix("/").removeSuffix("manifest.json")
        val parts = noScheme.split('/').filter(String::isNotBlank)
        return when {
            noScheme.startsWith("raw.githubusercontent.com", true) && parts.size >= 3 ->
                "${parts[1]}/${parts[2]}"

            noScheme.startsWith("github.com", true) && parts.size >= 3 ->
                "${parts[1]}/${parts[2]}"

            parts.size >= 2 -> "${parts[0]}/${parts[1]}"

            else -> noScheme
        }
    }

    internal fun selectableScrapers(
        values: List<NuvioScraper>,
        includeDisabled: Boolean,
    ): List<NuvioScraper> = values
        .filter { includeDisabled || FrSettings.isNuvioEnabled(it.id) }
        .filter { includeDisabled || it.manifestEnabled }
        .filter {
            includeDisabled ||
                it.supportedTypes.isEmpty() ||
                it.supportedTypes.any { type ->
                    type.lowercase() in setOf(
                        "movie",
                        "tv",
                        "series",
                        "anime",
                        "cartoon",
                        "animation",
                        "anime_movie",
                    )
                }
        }
        // Aucune configuration de langue n'empêche un site d'être exécuté :
        // tous les sites activés partent, y compris les sites non français.
        // La langue de chaque flux est ensuite classée par l'ordre des critères
        // (VF, VOSTFR, VO, EN, TR…) dans le classement à flèches.
        // Un dépôt ajouté plus tard remplace la variante par défaut portant le même id.
        .associateBy { it.id.lowercase() }
        .values
        .sortedWith { a, b ->
            val oa = FrSettings.nuvioOrder.indexOfFirst { it.equals(a.id, true) }
                .let { if (it < 0) Int.MAX_VALUE else it }
            val ob = FrSettings.nuvioOrder.indexOfFirst { it.equals(b.id, true) }
                .let { if (it < 0) Int.MAX_VALUE else it }
            if (oa != ob) {
                oa.compareTo(ob)
            } else {
                val fa = if (a.isFrench) 0 else 1
                val fb = if (b.isFrench) 0 else 1
                if (fa != fb) fa.compareTo(fb) else a.name.lowercase().compareTo(b.name.lowercase())
            }
        }

    private suspend fun manifest(repo: String): List<NuvioScraper> {
        manifestCache[repo]?.let { (expiry, list) -> if (expiry > System.currentTimeMillis()) return list }
        val cached = manifestCache[repo]?.second ?: emptyList()

        val json = runCatching {
            withContext(Dispatchers.IO) { JSONObject(httpGet(repo.trim(), emptyMap())) }
        }.getOrNull() ?: return cached
        val list = parseManifest(repo, json)
        if (list.isEmpty() && json.optJSONArray("scrapers") == null) return cached

        manifestCache[repo] = (System.currentTimeMillis() + MANIFEST_TTL_MS) to list
        return list
    }

    internal fun parseManifest(repo: String, json: JSONObject): List<NuvioScraper> {
        val array = json.optJSONArray("scrapers") ?: return emptyList()
        val base = repo.trim().removeSuffix("/").removeSuffix("manifest.json").removeSuffix("/")
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val filename = entry.optString("filename").takeIf(String::isNotBlank) ?: return@mapNotNull null
            NuvioScraper(
                id = entry.optString("id").ifBlank { filename },
                name = entry.optString("name").ifBlank { filename },
                filename = filename,
                repoBase = base,
                supportedTypes = stringArray(entry.optJSONArray("supportedTypes")),
                contentLanguage = stringArray(entry.optJSONArray("contentLanguage")),
                description = entry.optString("description"),
                manifestEnabled = entry.optBoolean("enabled", true),
                envDefaults = envObject(entry.optJSONObject("env")),
                requiredEnv = stringArray(entry.optJSONArray("requiredEnv"))
                    .ifEmpty { envObject(entry.optJSONObject("requiredEnv")).keys.toList() },
            )
        }
    }

    private fun stringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf { it.isNotBlank() } }
    }

    /** Variables d'environnement déclarées par un manifest (`{ "API_KEY": "défaut" }`). */
    private fun envObject(envJson: JSONObject?): Map<String, String> {
        if (envJson == null) return emptyMap()
        return envJson.keys().asSequence()
            .mapNotNull { key -> key.takeIf { envJson.optString(key).isNotBlank() } }
            .associateWith { envJson.optString(it) }
    }

    // ----------------------------------------------------- exécution JS

    private fun scriptFile(scraper: NuvioScraper): File? = cacheDir?.let {
        val id = scraper.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val origin = Integer.toHexString(scraper.scriptUrl.hashCode())
        File(it, "$id-$origin.js")
    }

    private fun scriptCandidates(scraper: NuvioScraper): List<String> = buildList {
        add(scraper.scriptUrl)
        if (!scraper.filename.startsWith("http")) {
            // Quelques manifests amont ont gardé un ancien préfixe `src/` alors
            // que leurs bundles ont été déplacés à la racine du dépôt.
            scraper.filename.removePrefix("src/").takeIf { it != scraper.filename }?.let {
                add("${scraper.repoBase}/$it")
            }
            if ('/' in scraper.filename) {
                add("${scraper.repoBase}/providers/${scraper.filename.substringAfterLast('/')}")
            }
        }
    }.distinct()

    private suspend fun script(scraper: NuvioScraper): String? {
        val file = scriptFile(scraper)
        val fresh = file?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < SCRIPT_TTL_MS }
        if (fresh != null) {
            val cached = runCatching { fresh.readText() }.getOrNull()
            if (!cached.isNullOrBlank()) return cached
        }
        val code = fetchScript(scraper)?.second
        if (code != null) runCatching { file?.writeText(code) }
        return code
    }

    /**
     * Télécharge le script d'une source (première URL candidate qui répond).
     * Avec [since] > 0, la requête est conditionnelle et un 304 est renvoyé tel quel.
     */
    private suspend fun fetchScript(scraper: NuvioScraper, since: Long = 0L): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val headers = if (since > 0L) mapOf("If-Modified-Since" to httpDate(since)) else emptyMap()
            scriptCandidates(scraper).firstNotNullOfOrNull { url ->
                val (status, body) = doHttp(url, "GET", headers, null)
                when {
                    status == 304 -> 304 to ""
                    status in 200..299 && body.isNotBlank() -> status to body
                    else -> null
                }
            }
        }

    private fun httpDate(timestamp: Long): String =
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date(timestamp))

    // ------------------------------------------------------ mise à jour

    /** Compte rendu d'une mise à jour des dépôts et des scripts Nuvio. */
    data class UpdateReport(
        val repositories: Int,
        val scrapers: Int,
        val updated: Int,
        val unchanged: Int,
        val failed: Int,
        val elapsedMs: Long,
    ) {
        fun summary(): String = buildString {
            append(L10n.t("Dépôts relus : ", "Repositories re-read: ")).append(repositories)
            append(" · ").append(L10n.t("sources actives : ", "active sources: ")).append(scrapers)
            append('\n')
            append(L10n.t("Scripts mis à jour : ", "Scripts updated: ")).append(updated)
            append(" · ").append(L10n.t("inchangés : ", "unchanged: ")).append(unchanged)
            if (failed > 0) append(" · ").append(L10n.t("en échec : ", "failed: ")).append(failed)
            append(" (").append(elapsedMs / 1000).append(L10n.t(" s)", " s)"))
        }
    }

    private val updateLock = Mutex()

    /**
     * Relit tous les manifests actifs puis retélécharge le script de chaque source
     * sélectionnable (requête conditionnelle `If-Modified-Since` : un script inchangé
     * ne coûte qu'un aller-retour). La date de passage est mémorisée pour [autoUpdateIfDue].
     */
    suspend fun updateSources(): UpdateReport = updateLock.withLock {
        val startedAt = System.currentTimeMillis()
        val repos = FrSettings.nuvioRepos.filter { FrSettings.isNuvioRepoEnabled(it) }
        repos.forEach(::invalidateRepository)
        val scrapers = trySuspend { scrapers() }.getOrDefault(emptyList())
        val results = coroutineScope {
            scrapers.chunked(4).flatMap { batch ->
                batch.map { scraper -> async { trySuspend { refreshScript(scraper) }.getOrNull() } }.awaitAll()
            }
        }
        FrSettings.saveNuvioLastUpdate(System.currentTimeMillis())
        UpdateReport(
            repositories = repos.size,
            scrapers = scrapers.size,
            updated = results.count { it == true },
            unchanged = results.count { it == false },
            failed = results.count { it == null },
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** `true` : script changé ; `false` : inchangé ; `null` : téléchargement impossible. */
    private suspend fun refreshScript(scraper: NuvioScraper): Boolean? {
        val file = scriptFile(scraper)
        val existing = file?.takeIf { it.exists() && it.length() > 0L }
        val (status, code) = fetchScript(scraper, existing?.lastModified() ?: 0L) ?: return null
        if (status == 304) {
            existing?.setLastModified(System.currentTimeMillis())
            return false
        }
        val previous = existing?.let { runCatching { it.readText() }.getOrNull() }
        val changed = previous != code
        if (changed) {
            runCatching { file?.writeText(code) }
        } else {
            existing?.setLastModified(System.currentTimeMillis())
        }
        return changed
    }

    /**
     * Mise à jour automatique au démarrage : au plus une fois par
     * [FrSettings.NUVIO_AUTO_UPDATE_INTERVAL_MS], et seulement si le réglage est actif.
     */
    suspend fun autoUpdateIfDue(now: Long = System.currentTimeMillis()): UpdateReport? {
        if (!FrSettings.useNuvio || !FrSettings.nuvioAutoUpdate) return null
        val last = FrSettings.nuvioLastUpdate
        if (last in 1..now && now - last < FrSettings.NUVIO_AUTO_UPDATE_INTERVAL_MS) return null
        return updateSources()
    }

    /**
     * Le Rhino Android intégré ne prend pas en charge `for … of` et traite certaines
     * déclarations `const` comme si elles partageaient la portée de la fonction. Les
     * bundles internationaux sont donc abaissés vers une syntaxe compatible, sans
     * modifier les chaînes, commentaires ou littéraux d'expression régulière.
     */
    internal fun transpileForOf(source: String): String {
        val output = StringBuilder(source.length + 256)
        var copiedUntil = 0
        var index = 0
        var serial = 0
        while (index < source.length) {
            index = skipJsLiteralOrComment(source, index).takeIf { it > index } ?: index
            if (
                index + 3 <= source.length &&
                source.startsWith("for", index) &&
                (index == 0 || !source[index - 1].isJavaIdentifierPart()) &&
                (index + 3 == source.length || !source[index + 3].isJavaIdentifierPart())
            ) {
                var open = index + 3
                while (open < source.length && source[open].isWhitespace()) open++
                if (open < source.length && source[open] == '(') {
                    val close = matchingParenthesis(source, open)
                    if (close > open) {
                        val body = source.substring(open + 1, close)
                        val of = topLevelOf(body)
                        if (of >= 0) {
                            val declaration = body.substring(0, of).trim()
                                .replace(Regex("^(?:const|let|var)\\s+"), "")
                            val expression = body.substring(of + 2).trim()
                            val variables = when {
                                declaration.matches(Regex("[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*")) ->
                                    listOf(declaration)

                                declaration.matches(
                                    Regex(
                                        "\\[\\s*[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*\\s*,\\s*[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*\\s*]",
                                    ),
                                ) -> declaration.trim('[', ']').split(',').map(String::trim)

                                else -> emptyList()
                            }
                            if (variables.isNotEmpty() && expression.isNotBlank()) {
                                val array = "__fr_a$serial"
                                val cursor = "__fr_i$serial"
                                val value = "__fr_v$serial"
                                val assign = if (variables.size == 1) {
                                    "(${variables[0]}=$array[$cursor])"
                                } else {
                                    "($value=$array[$cursor]),(${variables[0]}=$value[0]),(${variables[1]}=$value[1])"
                                }
                                val declared = (listOf(value) + variables).distinct().joinToString(",")
                                output.append(source, copiedUntil, open + 1)
                                output.append("let $array=__frToArray(($expression)),$cursor=0,$declared;")
                                output.append("$cursor<$array.length&&($assign,true);$cursor++")
                                copiedUntil = close
                                index = close
                                serial++
                            }
                        }
                    }
                }
            }
            index++
        }
        output.append(source, copiedUntil, source.length)
        return lowerConstDeclarations(output.toString())
    }

    private fun lowerConstDeclarations(source: String): String {
        val output = StringBuilder(source.length)
        var copiedUntil = 0
        var index = 0
        while (index < source.length) {
            val skipped = skipJsLiteralOrComment(source, index)
            if (skipped > index) {
                index = skipped
                continue
            }
            if (
                source.startsWith("const", index) &&
                (index == 0 || !source[index - 1].isJavaIdentifierPart()) &&
                (index + 5 == source.length || !source[index + 5].isJavaIdentifierPart())
            ) {
                output.append(source, copiedUntil, index).append("var")
                copiedUntil = index + 5
                index += 5
            } else {
                index++
            }
        }
        output.append(source, copiedUntil, source.length)
        return output.toString()
    }

    private fun topLevelOf(value: String): Int {
        var round = 0
        var square = 0
        var curly = 0
        var index = 0
        while (index + 1 < value.length) {
            val skipped = skipJsLiteralOrComment(value, index)
            if (skipped > index) {
                index = skipped
                continue
            }
            when (value[index]) {
                '(' -> round++
                ')' -> round--
                '[' -> square++
                ']' -> square--
                '{' -> curly++
                '}' -> curly--
            }
            if (
                round == 0 &&
                square == 0 &&
                curly == 0 &&
                value.startsWith("of", index) &&
                index > 0 &&
                value[index - 1].isWhitespace() &&
                index + 2 < value.length &&
                value[index + 2].isWhitespace()
            ) {
                return index
            }
            index++
        }
        return -1
    }

    private fun matchingParenthesis(value: String, open: Int): Int {
        var depth = 0
        var index = open
        while (index < value.length) {
            val skipped = skipJsLiteralOrComment(value, index)
            if (skipped > index) {
                index = skipped
                continue
            }
            when (value[index]) {
                '(' -> depth++

                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun skipJsLiteralOrComment(value: String, start: Int): Int {
        if (start >= value.length) return start
        val quote = value[start]
        if (quote == '\'' || quote == '"' || quote == '`') {
            var index = start + 1
            while (index < value.length) {
                if (value[index] == '\\') {
                    index += 2
                } else if (value[index] == quote) {
                    return index + 1
                } else {
                    index++
                }
            }
            return value.length
        }
        if (quote == '/' && value.getOrNull(start + 1) == '/') {
            val end = value.indexOf('\n', start + 2)
            return if (end < 0) value.length else end + 1
        }
        if (quote == '/' && value.getOrNull(start + 1) == '*') {
            val end = value.indexOf("*/", start + 2)
            return if (end < 0) value.length else end + 2
        }
        if (quote == '/' && isRegexStart(value, start)) {
            var index = start + 1
            var inClass = false
            while (index < value.length) {
                when {
                    value[index] == '\\' -> index += 2

                    value[index] == '[' -> {
                        inClass = true
                        index++
                    }

                    value[index] == ']' -> {
                        inClass = false
                        index++
                    }

                    value[index] == '/' && !inClass -> {
                        index++
                        while (index < value.length && value[index].isLetter()) index++
                        return index
                    }

                    value[index] == '\n' || value[index] == '\r' -> return start

                    else -> index++
                }
            }
        }
        return start
    }

    private fun isRegexStart(value: String, slash: Int): Boolean {
        var previous = slash - 1
        while (previous >= 0 && value[previous].isWhitespace() && value[previous] != '\n') previous--
        if (previous < 0 || value[previous] == '\n') return true
        if (value[previous] in "(=:[,!&|?{};+-*%~<>") return true
        val before = value.substring(0, previous + 1)
        return Regex("(?:return|case|throw|else|do|typeof|delete|void|yield)\\s*$").containsMatchIn(before)
    }

    /** Exécute tous les scrapeurs activés en parallèle (bornés par le sémaphore de concurrence). */
    suspend fun streams(payload: PlayPayload, callback: (Video) -> Unit): Boolean {
        if (!FrSettings.useNuvio) return false
        lastResults.clear()
        val limiter = syncSemaphore()

        val tmdbId = tmdbId(payload) ?: return false
        val all = orderForPayload(scrapers(), payload)
        if (all.isEmpty()) return false

        val mediaType = if (payload.isSeries) "tv" else "movie"
        val season = if (payload.isSeries) (payload.season ?: 1) else 0
        val episode = if (payload.isSeries) (payload.episode ?: 1) else 0
        val (tmdbTarget, absoluteTarget) = resolveEpisodeTargets(tmdbId, season, episode, payload)
        return runParallelScrapers(
            all,
            limiter,
            tmdbId,
            mediaType,
            tmdbTarget,
            absoluteTarget,
            payload,
            callback,
        )
    }

    /**
     * Tous les providers sont lancés en parallèle (bornés par le sémaphore de
     * concurrence) et interrogés jusqu'au bout : il n'y a plus de mode rapide,
     * équilibré ou complet ni d'arrêt quand une VF est trouvée. Chaque site actif
     * renvoie ses liens, bornés uniquement par le réglage « flux maximum par site »
     * (illimité par défaut, comme dans NuviO) — c'est pourquoi la recherche
     * affiche autant de serveurs que les sites en fournissent.
     *
     * Pour les animés longs (One Piece…) ou les séries multi-saisons, chaque scrapeur
     * reçoit d'abord le format adapté à son type (numérotation absolue S1E1120 pour les
     * sources animées, saison/épisode S21E35 pour les sources généralistes), avec
     * bascule automatique en repli en cas de 0 lien.
     */
    private suspend fun runParallelScrapers(
        scrapers: List<NuvioScraper>,
        limiter: Semaphore,
        tmdbId: Int,
        mediaType: String,
        tmdbTarget: Pair<Int, Int>,
        absoluteTarget: Pair<Int, Int>,
        payload: PlayPayload,
        callback: (Video) -> Unit,
    ): Boolean = coroutineScope {
        val results = scrapers.map { scraper ->
            async {
                limiter.withPermit {
                    val isAnimeScraper = scraper.id in ANIME_FOCUSED_IDS
                    val (pS, pE) = if (isAnimeScraper) absoluteTarget else tmdbTarget
                    val (fS, fE) = if (isAnimeScraper) tmdbTarget else absoluteTarget

                    var ok = trySuspend {
                        runScraper(scraper, tmdbId, mediaType, pS, pE, payload, callback)
                    }.getOrDefault(false)

                    if (!ok && (pS to pE) != (fS to fE)) {
                        ok = trySuspend {
                            runScraper(scraper, tmdbId, mediaType, fS, fE, payload, callback)
                        }.getOrDefault(false)
                    }
                    ok
                }
            }
        }.awaitAll()
        results.any { it }
    }

    private fun orderForPayload(scrapers: List<NuvioScraper>, payload: PlayPayload): List<NuvioScraper> {
        val anime = payload.kind == "anime" || payload.anilistId != null || payload.malId != null
        val mediaType = if (payload.isSeries) "tv" else "movie"
        val compatible = scrapers.filter { scraper ->
            scraper.supportedTypes.isEmpty() ||
                scraper.supportedTypes.any { type ->
                    val normalized = type.lowercase()
                    normalized == mediaType ||
                        (mediaType == "tv" && normalized == "series") ||
                        (anime && normalized in setOf("anime", "animation", "anime_movie"))
                }
        }.filterNot { !anime && it.id in ANIME_FOCUSED_IDS }
        val priority = if (anime) {
            listOf("frenchstream", "movix", "anime-sama")
        } else {
            listOf("frenchstream", "movix")
        }
        return compatible.sortedWith(
            compareBy<NuvioScraper> {
                // L'ordre enregistré par le sélecteur utilisateur prime toujours.
                FrSettings.nuvioOrder.indexOfFirst { id -> id.equals(it.id, true) }
                    .let { index -> if (index < 0) Int.MAX_VALUE else index }
            }.thenBy {
                priority.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index }
            }.thenBy { it.name.lowercase() },
        )
    }

    private val ANIME_FOCUSED_IDS = setOf(
        "anime-ultime",
        "anime-sama",
        "animesama-co",
        "animesultra",
        "animevostfr",
        "animoflix",
        "french-manga",
        "voiranime-homes",
        "mugiwarastream",
        "sekai",
        "voiranime",
        "voiranime-rip",
        "vostfree",
        "waveanime",
        "neko-sama",
        "fullanime",
        "animevost-fr",
    )

    // ------------------------------------------- compatibilité Android (dex)

    /** Dernier incident d'initialisation du moteur (visible dans les réglages). */
    @Volatile
    private var engineError: String? = null

    /** Bandeau de diagnostic : « OK » ou la raison exacte du dernier échec. */
    fun engineStatus(): String {
        val err = engineError
        if (err != null) return "✗ moteur : $err"
        return runCatching {
            val cx = RhinoContext.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = RhinoContext.VERSION_ES6
                val scope = cx.initStandardObjects(com.frunified.rhino.TopLevel())
                installRuntime(cx, scope)
                val probe = cx.evaluateString(
                    scope,
                    "(typeof RegExp === 'function') && /^a(b+)c$/.test('abbbc') && " +
                        "(function(){ try { null.x; return false; } catch (e) { return String(e).length > 0; } })()",
                    "selftest",
                    1,
                    null,
                )
                val ok = RhinoContext.toBoolean(probe)
                if (ok) {
                    "✓ moteur Rhino opérationnel (RegExp + messages)"
                } else {
                    "✗ auto-test JS négatif"
                }
            } finally {
                RhinoContext.exit()
            }
        }.getOrElse { t -> "✗ moteur : " + (t.message?.take(120) ?: t::class.simpleName.orEmpty()) }
    }

    /**
     * Rhino récupère deux choses par `ServiceLoader` / `ResourceBundle`, c'est-à-dire
     * par des **fichiers de ressources** (`META-INF/services/com.frunified.rhino.RegExpLoader`
     * et `com/frunified/rhino/resources/Messages.properties`). Un plugin CloudStream
     * ne contient qu'un `classes.dex` : ces fichiers n'existent pas sur l'appareil.
     *
     * Conséquences avant ce correctif (invisibles sur JVM où le jar complet est au
     * classpath, donc jamais détectées par les bancs d'essai) :
     *  • aucun `RegExpLoader` → `RegExp` absent et **chaque littéral `/…/` fait
     *    échouer la compilation** du script (`checkRegExpProxy`) ;
     *  • aucun bundle de messages → l'erreur elle-même devient une
     *    `MissingResourceException` (« ✗ interne: can't find bundle for base name … »),
     *    ce qui masquait la vraie cause et tuait **tous** les scrapeurs.
     *
     * On rétablit les deux à la main : proxy + constructeur `RegExp` enregistrés
     * dans le contexte, et bundle de messages fourni par une classe compilée
     * (`com.frunified.rhino.resources.Messages`, déxée avec le plugin).
     */
    private fun installRuntime(cx: RhinoContext, scope: Scriptable) {
        val scopeObj = scope as? com.frunified.rhino.ScriptableObject
        if (scopeObj == null) {
            engineError = "scope Rhino inattendu"
            return
        }
        val proxyResult = runCatching {
            com.frunified.rhino.ScriptRuntime.setRegExpProxy(
                cx,
                com.frunified.rhino.regexp.RegExpLoaderImpl().newProxy(),
            )
        }
        val registerResult = runCatching {
            // registerRegExp(Context, ScriptableObject, boolean) est privé : on le
            // retrouve par son nom (plus robuste qu'une signature exacte).
            val register = com.frunified.rhino.ScriptRuntime::class.java.declaredMethods
                .firstOrNull { it.name == "registerRegExp" }
                ?: error("registerRegExp absent")
            register.isAccessible = true
            register.invoke(null, cx, scopeObj, false)
        }
        val messagesResult = runCatching {
            java.util.ResourceBundle.getBundle(
                "com.frunified.rhino.resources.Messages",
                java.util.Locale.getDefault(),
                com.frunified.rhino.ScriptRuntime::class.java.classLoader,
            ).getString("msg.dup.parms")
        }
        val failure = proxyResult.exceptionOrNull() ?: registerResult.exceptionOrNull()
            ?: messagesResult.exceptionOrNull()
        engineError = when {
            failure == null -> null

            proxyResult.isFailure ->
                "proxy RegExp : " +
                    (failure.message?.take(90) ?: failure::class.simpleName.orEmpty())

            registerResult.isFailure ->
                "constructeur RegExp : " +
                    (failure.message?.take(90) ?: failure::class.simpleName.orEmpty())

            else -> "messages Rhino : " + (failure.message?.take(90) ?: failure::class.simpleName.orEmpty())
        }
    }

    /** Exécute UN scrapeur dans un interpréteur Rhino isolé. */
    private suspend fun runScraper(
        scraper: NuvioScraper,
        tmdbId: Int,
        mediaType: String,
        season: Int,
        episode: Int,
        payload: PlayPayload,
        callback: (Video) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val missing = missingRequiredEnv(scraper)
        if (missing.isNotEmpty()) {
            lastResults[scraper.id] = L10n.t(
                "à configurer : ${missing.joinToString(", ")}",
                "needs configuration: ${missing.joinToString(", ")}",
            )
            return@withContext false
        }
        val startedAt = System.currentTimeMillis()
        fetchLog[scraper.id] = mutableListOf()
        consoleLog[scraper.id] = emptyList()
        val code = try {
            script(scraper)
        } catch (t: Throwable) {
            lastResults[scraper.id] = "✗ script: " + (t.message?.take(80) ?: t::class.simpleName.orEmpty())
            return@withContext false
        }
        if (code == null) {
            lastResults[scraper.id] = "✗ script introuvable"
            return@withContext false
        }

        // Certains bundles contiennent des expressions régulières très profondes.
        // La pile standard d'un worker ART/JVM peut déborder pendant leur compilation,
        // d'où ce thread court à pile dédiée (2 Mio) pour tout le cycle Rhino.
        val acceptingLinks = AtomicBoolean(true)
        try {
            runOnRhinoThread(scraper.id) rhino@{
                currentScraper.set(scraper.id)
                val cx = try {
                    RhinoContext.enter()
                } catch (t: Throwable) {
                    lastResults[scraper.id] = "✗ moteur: " + (t.message?.take(80) ?: t::class.simpleName.orEmpty())
                    currentScraper.remove()
                    return@rhino false
                }
                var scopeRef: Scriptable? = null
                try {
                    cx.optimizationLevel = -1 // interprété : compatible ART/Android
                    cx.languageVersion = RhinoContext.VERSION_ES6

                    // IMPORTANT : TopLevel() active le cache des builtins (cacheBuiltins) :
                    // sans lui, le prototype des fonctions génératrices n'est pas initialisé
                    // et les bundles transpilés (babel) échouent en « Cannot find function apply ».
                    val scope = cx.initStandardObjects(com.frunified.rhino.TopLevel())
                    scopeRef = scope
                    // Le dex Android ne transporte ni META-INF/services ni .properties :
                    // sans cela RegExp et les messages Rhino sont introuvables (cf. installRuntime).
                    installRuntime(cx, scope)
                    cx.evaluateString(scope, JS_ENV, "prelude", 1, null)
                    injectEnv(scope, scraper)

                    // module.exports / global.getStreams : les deux formats de sortie
                    val module = cx.newObject(scope)
                    val exports = cx.newObject(scope)
                    module.put("exports", module, exports)
                    scope.put("module", scope, module)
                    scope.put("exports", scope, exports)

                    // Les accès réseau quittent le thread Rhino : les résultats sont réinjectés
                    // uniquement par la boucle d'événements, ce qui rend Promise.all réellement parallèle.
                    val asyncFetch = AsyncFetchBridge(scraper.id)
                    scope.put("__queueFetch", scope, asyncFetch.QueueFunction())
                    scope.put("__pollFetch", scope, asyncFetch.PollFunction())
                    scope.put("__b64Encode", scope, B64Function(encode = true))
                    scope.put("__b64Decode", scope, B64Function(encode = false))

                    val compatibleCode = transpileForOf(code)
                    try {
                        cx.evaluateString(scope, compatibleCode, scraper.id, 1, null)
                    } catch (t: Throwable) {
                        lastResults[scraper.id] = "✗ erreur JS : " + jsError(compatibleCode, t) + diagSuffix(scraper.id)
                        return@rhino false
                    }

                    // Récupère getStreams : module.exports.getStreams OU global.getStreams
                    var fn: Any? = null
                    runCatching {
                        val exported = (module.get("exports", module) as? Scriptable) ?: scope
                        fn = exported.get("getStreams", exported)
                    }
                    if (fn == null || fn == Scriptable.NOT_FOUND) {
                        runCatching { fn = scope.get("getStreams", scope) }
                    }
                    if (fn == null || fn == Scriptable.NOT_FOUND || fn !is com.frunified.rhino.Callable) {
                        lastResults[scraper.id] = "✗ getStreams introuvable"
                        return@rhino false
                    }

                    val args = arrayOf<Any?>(
                        cx.evaluateString(scope, tmdbId.toString(), "n", 1, null),
                        cx.evaluateString(scope, JSONObject.quote(mediaType), "s", 1, null),
                        cx.evaluateString(scope, season.toString(), "n", 1, null),
                        cx.evaluateString(scope, episode.toString(), "n", 1, null),
                    )

                    var result: Any? = try {
                        (fn as com.frunified.rhino.Callable).call(cx, scope, scope, args)
                    } catch (t: Throwable) {
                        lastResults[scraper.id] = "✗ appel : " + jsError(code, t) + diagSuffix(scraper.id)
                        return@rhino false
                    }

                    // Boucle d'événements : Rhino reste mono-thread, tandis que fetch s'exécute sur
                    // un pool IO. Les vraies échéances setTimeout sont respectées sans bloquer les fetch.
                    val eventDeadline = System.currentTimeMillis() + SCRAPER_TIMEOUT_MS - 500L
                    do {
                        drain(cx, scope)
                        if (timerCount(cx, scope) <= 0) break
                        if (System.currentTimeMillis() < eventDeadline) Thread.sleep(10L)
                    } while (System.currentTimeMillis() < eventDeadline)
                    drain(cx, scope)

                    // Si le résultat est notre promesse, on lit sa valeur
                    val resultObj = result as? Scriptable
                    if (resultObj != null &&
                        runCatching {
                            ScriptableObject.hasProperty(resultObj, "__settled")
                        }.getOrDefault(false)
                    ) {
                        if (ScriptableObject.getProperty(resultObj, "__rejected") == java.lang.Boolean.TRUE) {
                            val rej = ScriptableObject.getProperty(resultObj, "__value")
                            lastResults[scraper.id] =
                                "✗ rejet : " + (rej?.toString()?.take(80) ?: "inconnu") + diagSuffix(scraper.id)
                            return@rhino false
                        }
                        result = ScriptableObject.getProperty(resultObj, "__value")
                    }

                    val streams = asArray(cx, scope, result)
                    if (streams == null) {
                        lastResults[scraper.id] = "✗ résultat non reconnu" + diagSuffix(scraper.id)
                        return@rhino false
                    }
                    var emitted = false
                    var count = 0
                    var rejected = 0
                    val maxHere = FrSettings.nuvioMaxPerScraper

                    for (i in 0 until
                        runCatching {
                            RhinoContext.toNumber(ScriptableObject.getProperty(streams, "length")).toInt()
                        }.getOrDefault(0)) {
                        if (maxHere > 0 && count >= maxHere) break
                        val obj = ScriptableObject.getProperty(streams, i) as? Scriptable ?: continue
                        val link = toLink(scope, scraper, payload, obj) ?: continue
                        val deniedStatus = deniedStreamStatus(link)
                        if (deniedStatus != null) {
                            rejected++
                            continue
                        }
                        emitted = true
                        count++
                        if (acceptingLinks.get()) callback(link)
                    }
                    val linksLabel = if (count == 1) {
                        L10n.t("lien", "link")
                    } else {
                        L10n.t("liens", "links")
                    }
                    val rejectedSuffix = if (rejected > 0) {
                        " · $rejected " + L10n.t("refusé(s)", "rejected")
                    } else {
                        ""
                    }
                    lastResults[scraper.id] = if (emitted) {
                        "✓ $count $linksLabel" + rejectedSuffix
                    } else {
                        "✓ 0 " +
                            L10n.t("lien", "links") +
                            rejectedSuffix +
                            diagSuffix(scraper.id)
                    }
                    emitted
                } catch (t: Throwable) {
                    // un scrapeur qui plante ne doit jamais faire planter la lecture
                    lastResults[scraper.id] =
                        "✗ interne: " + (t.message?.take(80) ?: t::class.simpleName.orEmpty()) + diagSuffix(scraper.id)
                    false
                } finally {
                    captureConsole(scopeRef, scraper.id)
                    RhinoContext.exit()
                    currentScraper.remove()
                }
            }
        } finally {
            acceptingLinks.set(false)
            val elapsed = System.currentTimeMillis() - startedAt
            lastResults[scraper.id]?.let { result ->
                lastResults[scraper.id] = "$result · ${elapsed}ms"
            }
        }
    }

    private fun runOnRhinoThread(id: String, block: () -> Boolean): Boolean {
        val outcome = AtomicReference<Result<Boolean>?>()
        val name = "frunified-rhino-" + id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val worker = Thread(
            null,
            { outcome.set(runCatching(block)) },
            name,
            RHINO_STACK_BYTES,
        )
        worker.isDaemon = true
        return try {
            worker.start()
            worker.join(SCRAPER_TIMEOUT_MS)
            if (worker.isAlive) {
                lastResults[id] = "✗ délai dépassé (${SCRAPER_TIMEOUT_MS / 1_000}s)"
                worker.interrupt()
                false
            } else {
                outcome.get()?.getOrElse { failure ->
                    lastResults[id] = "✗ thread Rhino: " +
                        (failure.message?.take(80) ?: failure::class.simpleName.orEmpty())
                    false
                } ?: false
            }
        } catch (interrupted: InterruptedException) {
            worker.interrupt()
            Thread.currentThread().interrupt()
            lastResults[id] = "✗ thread Rhino interrompu"
            false
        } catch (failure: Throwable) {
            lastResults[id] = "✗ thread Rhino: " +
                (failure.message?.take(80) ?: failure::class.simpleName.orEmpty())
            false
        }
    }

    /** Succinct : dernières requêtes + dernière ligne console JS (pour autopsier un échec). */
    private fun diagSuffix(id: String): String {
        val fetches = fetchLog[id].orEmpty().takeLast(6)
        val console = consoleLog[id].orEmpty().takeLast(2)
        val parts = mutableListOf<String>()
        if (fetches.isNotEmpty()) parts += fetches.joinToString("; ")
        if (console.isNotEmpty()) parts += "js: " + console.joinToString(" | ").take(110)
        return if (parts.isEmpty()) "" else " | " + parts.joinToString(" | ").take(260)
    }

    private fun captureConsole(scope: Scriptable?, id: String) {
        if (scope == null) return
        runCatching {
            val arr = scope.get("__console_lines", scope) as? Scriptable ?: return
            val n = runCatching {
                RhinoContext.toNumber(ScriptableObject.getProperty(arr, "length")).toInt()
            }.getOrDefault(0)
            if (n > 0) consoleLog[id] = (0 until n).map { RhinoContext.toString(ScriptableObject.getProperty(arr, it)) }
        }
    }

    private fun callJs(cx: RhinoContext, scope: Scriptable, name: String, args: Array<Any?>): Any? =
        runCatching {
            (scope.get(name, scope) as? com.frunified.rhino.Callable)?.call(cx, scope, scope, args)
        }.getOrNull()

    private fun drain(cx: RhinoContext, scope: Scriptable) {
        callJs(cx, scope, "__drain", emptyArray())
    }

    private fun timerCount(cx: RhinoContext, scope: Scriptable): Int =
        runCatching { RhinoContext.toNumber(callJs(cx, scope, "__timerCount", emptyArray())).toInt() }
            .getOrDefault(0)

    private fun asArray(cx: RhinoContext, scope: Scriptable, value: Any?): Scriptable? {
        if (value is NativeArray) return value
        if (value is com.frunified.rhino.NativeJavaObject) {
            val unwrapped = runCatching { value.unwrap() }.getOrNull()
            if (unwrapped is List<*>) {
                val arr = cx.newArray(scope, unwrapped.size)
                unwrapped.forEachIndexed { i, item -> arr.put(i, arr, RhinoContext.javaToJS(item, scope)) }
                return arr
            }
        }
        return null
    }

    // --------------------------------------------------- conversion flux

    private fun toLink(
        scope: Scriptable,
        scraper: NuvioScraper,
        payload: PlayPayload,
        obj: Scriptable,
    ): Video? {
        val scraperName = scraper.name
        fun prop(vararg names: String): String? {
            for (name in names) {
                val v = runCatching { ScriptableObject.getProperty(obj, name) }.getOrNull() ?: continue
                if (v == Scriptable.NOT_FOUND) continue
                val s = when (v) {
                    is String -> v
                    is Double -> if (v.isNaN()) null else v.toLong().toString()
                    is Number -> v.toString()
                    else -> v.toString()
                } ?: continue
                if (s.isNotBlank() && s != "null" && s != "undefined") return s
            }
            return null
        }

        val url = prop("url", "file", "videoUrl", "src")?.takeIf { it.startsWith("http") }
        val infoHash = prop("infoHash")?.takeIf { Regex("^[a-fA-F0-9]{40}$").matches(it) }
        val providerLabel = prop("name")
        val label = prop("title", "label", "description", "fileName", "name") ?: scraperName
        val quality = prop("quality", "resolution")
        val language = prop("language", "lang")
        val audio = audioTag(listOfNotNull(providerLabel, label, language).joinToString(" "))
            ?: StreamLabel.languageFromCode(language)
            ?: StreamLabel.languageFromProvider(scraper.contentLanguage)

        val headers = runCatching {
            val h = ScriptableObject.getProperty(obj, "headers")
            if (h !is Scriptable) {
                null
            } else {
                val map = linkedMapOf<String, String>()
                for (id in h.ids) {
                    if (id is String) {
                        val value = ScriptableObject.getProperty(h, id)?.toString()
                        if (!value.isNullOrBlank()) map[id] = value
                    }
                }
                map
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }

        val resolution = qualityOf("$label $quality")
        val kind = when {
            url != null -> "direct"
            infoHash != null -> "torrent"
            else -> return null
        }
        // « (VF) 1080p · flemmix · Nuvio · Uqload » : langue, qualité, source, moteur, puis
        // le détail du flux (lecteur, nom de fichier…) débarrassé des informations déjà affichées.
        val title = StreamLabel(
            language = audio,
            quality = resolution,
            source = scraperName,
            engine = StreamLabel.ENGINE_NUVIO,
            detail = StreamLabel.detail(
                listOf(providerLabel, label, quality),
                scraperName,
                prefix = if (kind == "torrent") "Torrent" else null,
                noise = payload.titles,
            ),
        ).render()
        val preferred = StreamRanker.isPreferred(title, resolution)
        val video = if (kind == "direct") {
            Video(
                videoUrl = url!!,
                videoTitle = title,
                resolution = resolution,
                headers = headers?.toOkHttpHeaders(),
                preferred = preferred,
            )
        } else {
            val magnet = "magnet:?xt=urn:btih:$infoHash" +
                "&dn=${java.net.URLEncoder.encode(payload.primaryTitle, "UTF-8")}" +
                TRACKERS.joinToString("") { "&tr=${java.net.URLEncoder.encode(it, "UTF-8")}" }
            Video(
                videoUrl = magnet,
                videoTitle = title,
                resolution = resolution,
                preferred = preferred,
            )
        }
        if (kind == "direct") {
            renewalContexts[video.videoUrl] = RenewalContext(scraper.id, payload, System.currentTimeMillis())
        }
        return video
    }

    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
    )

    private fun Map<String, String>.toOkHttpHeaders(): Headers? {
        val builder = Headers.Builder()
        var count = 0
        forEach { (key, value) ->
            runCatching { builder.add(key, value) }.onSuccess { count++ }
        }
        return if (count > 0) builder.build() else null
    }

    private fun qualityOf(text: String): Int? = StreamLabel.qualityOf(text)

    /** Langue audio explicite d'un libellé de flux (VF, VFF, VFQ, MULTI, VOSTFR) ; `null` sinon. */
    private fun audioTag(text: String): String? = StreamLabel.languageOf(text)?.takeIf { it != "VO" }

    // ------------------------------------------------- ID TMDB (animé)

    /**
     * TMDB est nécessaire pour les scrapeurs Nuvio (métadonnées/alias des titres).
     * Retrouvé par recherche si absent — on essaie TOUS les titres connus de la
     * fiche (titres FR, originaux, romaji, alternatifs) car les fiches
     * AniList/MAL (non-TMDB) n'ont pas d'id TMDB : c'est ce qui permet aux
     * scrapeurs anime de recevoir un id exploitable.
     */
    private suspend fun tmdbId(payload: PlayPayload): Int? {
        payload.tmdbId?.let { return it }
        // Premier passage avec l'année de la fiche ; second passage sans année lorsque rien ne
        // correspond (année de première diffusion différente entre AniList/MAL et TMDB, ou absente).
        val years = if (payload.year != null) listOf(payload.year, null) else listOf(null)
        for (year in years) {
            tmdbIdFor(payload, year)?.let { return it }
        }
        return null
    }

    private suspend fun tmdbIdFor(payload: PlayPayload, year: Int?): Int? {
        val now = System.currentTimeMillis()
        var best: Pair<Double, Int>? = null
        for (title in payload.titles.take(6)) {
            val key = "$title|$year"
            val cachedHit = tmdbCache[key]
            if (cachedHit != null && cachedHit.first > now) {
                cachedHit.second?.let { return it }
                continue
            }
            val found = trySuspend {
                TmdbCatalog.searchBest(title, year, payload.titles)?.let { item ->
                    val id = item.id.id.toIntOrNull()
                    if (id != null) {
                        val score = item.titles.maxOfOrNull { TitleMatch.score(payload.titles, it, year, item.year) }
                            ?: 0.0
                        score to id
                    } else {
                        null
                    }
                }
            }.getOrNull()
            tmdbCache[key] = (now + SCRIPT_TTL_MS) to found?.second
            if (found != null && (best == null || found.first > best!!.first)) best = found
        }
        return best?.second
    }

    private val seasonMapCache = ConcurrentHashMap<Int, List<Pair<Int, Int>>>()

    /**
     * Pour une série TMDB, retourne la liste des (seasonNumber, episodeCount) pour les saisons >= 1.
     */
    private suspend fun seasonCounts(tmdbId: Int): List<Pair<Int, Int>> {
        seasonMapCache[tmdbId]?.let { return it }
        val details = TmdbCatalog.details(CatalogId("tmdb", "tv", tmdbId.toString())) ?: return emptyList()
        val seasons = details.optJSONArray("seasons") ?: return emptyList()
        val list = (0 until seasons.length()).mapNotNull { i ->
            val s = seasons.optJSONObject(i) ?: return@mapNotNull null
            val num = s.optInt("season_number", -1)
            val count = s.optInt("episode_count", 0)
            if (num >= 1 && count > 0) num to count else null
        }.sortedBy { it.first }
        if (list.isNotEmpty()) seasonMapCache[tmdbId] = list
        return list
    }

    /**
     * Calcule (tmdbSeason, tmdbEpisode) et (1, absoluteEpisode) pour une série.
     */
    internal suspend fun resolveEpisodeTargets(
        tmdbId: Int,
        season: Int,
        episode: Int,
        payload: PlayPayload,
    ): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val direct = season to episode
        if (!payload.isSeries || episode <= 0) return direct to direct

        val counts = seasonCounts(tmdbId)
        if (counts.isEmpty()) {
            val abs = payload.absoluteEpisode ?: episode
            return direct to (1 to abs)
        }

        // Si season == 1 et episode > count de saison 1 (ex: One Piece S1E1120)
        val s1Count = counts.firstOrNull { it.first == 1 }?.second ?: 0
        if (season == 1 && (episode > s1Count || counts.size > 1)) {
            val abs = episode
            var remaining = abs
            var resolvedSeason = 1
            var resolvedEpisode = abs
            for ((sNum, sCount) in counts) {
                if (remaining <= sCount) {
                    resolvedSeason = sNum
                    resolvedEpisode = remaining
                    break
                }
                remaining -= sCount
            }
            return (resolvedSeason to resolvedEpisode) to (1 to abs)
        }

        // Si season > 1 (ex: TMDB One Piece S21E35)
        if (season > 1) {
            var totalBefore = 0
            for ((sNum, sCount) in counts) {
                if (sNum < season) totalBefore += sCount
            }
            val abs = totalBefore + episode
            return (season to episode) to (1 to abs)
        }

        val abs = payload.absoluteEpisode ?: episode
        return (season to episode) to (1 to abs)
    }

    /** Format d'erreur autoportant : classe + message + ligne fautive + extrait du code. */
    private fun jsError(code: String, t: Throwable): String {
        val cls = t::class.simpleName.orEmpty()
        val msg = (t.message ?: "").replace("\n", " ").take(90)
        val line = (t as? com.frunified.rhino.RhinoException)?.lineNumber() ?: -1
        val snippet = if (line > 0) {
            code.lineSequence().elementAtOrNull(line - 1)?.trim()?.take(110)
                ?.let { " | l$line: $it" }.orEmpty()
        } else {
            ""
        }
        return "$cls: $msg$snippet"
    }

    // ------------------------------------------------------- réseau

    /** Octets lus au début d'un lien direct (sondes anti-page HTML/popup). */
    private const val HEAD_PROBE_BYTES = 4 * 1024

    /** Octets lus pour une playlist HLS. */
    private const val PLAYLIST_PROBE_BYTES = 192 * 1024

    /** Code interne : le lien répond par une page HTML (popup/pub) et non par une vidéo. */
    private const val HTML_POPUP_STATUS = 490

    /**
     * Pendant le diagnostic uniquement : quand l'utilisateur choisit de tolérer
     * les pages HTML/popup, elles sont comptées comme des résultats valides.
     */
    @Volatile
    internal var diagTolerateHtmlPopup: Boolean = false

    private data class StreamProbe(val status: Int, val body: String?, val contentType: String?)

    /**
     * Écarte avant affichage un lien que le CDN refuse déjà (notamment les 403
     * FSVid/Movix). Pour HLS, sonde aussi le premier segment sans le télécharger.
     * Une panne de sonde (status 0) ne supprime pas le lien : seuls les refus
     * explicites et les pages HTML (popups téléchargées à la place de la vidéo)
     * sont éliminés.
     */
    internal fun acceptsStream(video: Video): Boolean = deniedStreamStatus(video) == null

    /**
     * Revérifie chaque lien HTTP juste avant lecture (les URL signées et les
     * popups HTML peuvent changer entre le listage et le clic). Les magnets
     * et les sondes en panne (status 0) sont conservés.
     */
    suspend fun reverify(videos: List<Video>): List<Video> {
        if (videos.isEmpty()) return videos
        return coroutineScope {
            videos.map { video ->
                async(Dispatchers.IO) {
                    probeLimiter.withPermit {
                        if (acceptsStream(video)) video else renewExpired(video)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Un refus explicite ou une page HTML déclenche le même provider avec le même épisode,
     * puis choisit uniquement un remplacement de même langue et qualité. Cela évite de
     * remettre une page d'hébergeur expirée au lecteur ou au gestionnaire de téléchargements.
     */
    private suspend fun renewExpired(expired: Video): Video? {
        val context = renewalContexts[expired.videoUrl] ?: return null
        if (System.currentTimeMillis() - context.createdAt > 6 * 60 * 60 * 1000L) {
            renewalContexts.remove(expired.videoUrl)
            return null
        }
        val scraper = scrapers(includeDisabled = true).firstOrNull { it.id.equals(context.scraperId, true) }
            ?: return null
        val id = tmdbId(context.payload) ?: return null
        val replacements = java.util.concurrent.CopyOnWriteArrayList<Video>()
        val mediaType = if (context.payload.isSeries) "tv" else "movie"
        val season = if (context.payload.isSeries) context.payload.season ?: 1 else 0
        val episode = if (context.payload.isSeries) context.payload.episode ?: 1 else 0
        val (tmdbTarget, absoluteTarget) = resolveEpisodeTargets(id, season, episode, context.payload)
        val isAnime = scraper.id in ANIME_FOCUSED_IDS
        val (pS, pE) = if (isAnime) absoluteTarget else tmdbTarget
        val (fS, fE) = if (isAnime) tmdbTarget else absoluteTarget

        var ok = runScraper(scraper, id, mediaType, pS, pE, context.payload) { replacements += it }
        if (!ok && (pS to pE) != (fS to fE)) {
            runScraper(scraper, id, mediaType, fS, fE, context.payload) { replacements += it }
        }
        val language = StreamLabel.languageIn(expired.videoTitle)
        val quality = expired.resolution ?: StreamLabel.parse(expired.videoTitle)?.quality
        return replacements.firstOrNull { candidate ->
            StreamLabel.languageIn(candidate.videoTitle) == language &&
                (candidate.resolution ?: StreamLabel.parse(candidate.videoTitle)?.quality) == quality &&
                acceptsStream(candidate)
        }
    }

    private fun deniedStreamStatus(video: Video): Int? {
        val initialUrl = video.videoUrl.takeIf { it.startsWith("http") } ?: return null
        val requestHeaders = linkedMapOf<String, String>()
        video.headers?.names()?.forEach { name ->
            video.headers?.get(name)?.let { requestHeaders[name] = it }
        }
        val lower = initialUrl.lowercase()
        val hls = lower.contains(".m3u8") || lower.contains("/hls")
        if (!hls) {
            val probe = probeRequest(initialUrl, requestHeaders, capBytes = HEAD_PROBE_BYTES, ranged = true)
            recordProbe(initialUrl, probe.status)
            if (isDeniedStatus(probe.status)) return probe.status
            if (probe.status in 200..399 && isPopupBody(probe.body, probe.contentType)) {
                recordProbe(initialUrl, HTML_POPUP_STATUS)
                return HTML_POPUP_STATUS
            }
            return null
        }

        var playlistUrl = initialUrl
        repeat(2) {
            val playlist = probeRequest(playlistUrl, requestHeaders, capBytes = PLAYLIST_PROBE_BYTES, ranged = false)
            recordProbe(playlistUrl, playlist.status)
            if (isDeniedStatus(playlist.status)) return playlist.status
            if (playlist.status !in 200..399 || playlist.body.isNullOrBlank()) return null
            if (isPopupBody(playlist.body, playlist.contentType)) {
                recordProbe(playlistUrl, HTML_POPUP_STATUS)
                return HTML_POPUP_STATUS
            }
            val next = playlist.body.lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                ?: return null
            val resolved = runCatching { URL(URL(playlistUrl), next).toString() }.getOrNull() ?: return null
            if (resolved.lowercase().contains(".m3u8")) {
                playlistUrl = resolved
            } else {
                val segment = probeRequest(resolved, requestHeaders, capBytes = HEAD_PROBE_BYTES, ranged = true)
                recordProbe(resolved, segment.status)
                if (isDeniedStatus(segment.status)) return segment.status
                if (segment.status in 200..399 && isPopupBody(segment.body, segment.contentType)) {
                    recordProbe(resolved, HTML_POPUP_STATUS)
                    return HTML_POPUP_STATUS
                }
                return null
            }
        }
        return null
    }

    /**
     * Détecte une réponse HTML (popup, page intermédiaire, publicité) déguisée en
     * lien vidéo — c'est ce que le téléchargeur enregistrait « à la place » du flux.
     * Les corps binaires (mp4, ts, mkv…) ne sont jamais confondus avec du HTML.
     */
    private fun isPopupBody(body: String?, contentType: String?): Boolean {
        if (!FrSettings.verifyStreamContent || diagTolerateHtmlPopup) return false
        val ct = contentType.orEmpty().lowercase()
        if (ct.contains("text/html")) return true
        val mediaLike = ct.contains("json") ||
            ct.contains("octet") ||
            ct.contains("mpegurl") ||
            ct.contains("video") ||
            ct.contains("audio") ||
            ct.contains("mp4") ||
            ct.contains("mp2t") ||
            ct.contains("quicktime") ||
            ct.contains("matroska")
        if (ct.isNotEmpty() && mediaLike) return false
        if (body.isNullOrBlank()) return false
        val sample = body.take(12_000).lowercase()
        return sample.startsWith("<!doctype") ||
            sample.startsWith("<html") ||
            sample.contains("<script") ||
            sample.contains("window.open(") ||
            sample.contains("popunder") ||
            sample.contains("adsterra")
    }

    private fun isDeniedStatus(status: Int): Boolean {
        if (status == 403 && FrSettings.tolerate403) return false
        return status in setOf(401, 403, 404, 410, 429, 451)
    }

    private fun recordProbe(url: String, status: Int) {
        currentScraper.get()?.let { id ->
            runCatching {
                val host = URL(url).host.ifBlank { "?" }
                val list = fetchLog.computeIfAbsent(id) { mutableListOf() }
                val detail = if (status == HTML_POPUP_STATUS) " (page HTML/popup)" else ""
                list.add("PROBE $host → $status$detail")
                while (list.size > 40) list.removeAt(0)
            }
        }
    }

    private fun probeRequest(
        url: String,
        headers: Map<String, String>,
        capBytes: Int,
        ranged: Boolean,
    ): StreamProbe {
        // Sur l'appareil, la sonde passe par le client OkHttp qui utilise le DNS
        // personnalisé de l'extension. En test JVM (pas de client), on retombe sur
        // une connexion Java directe afin de conserver les bancs d'essai locaux.
        if (FrRuntime.isHttpReady) {
            val extra = linkedMapOf<String, String>()
            extra.putAll(headers)
            if (ranged && extra.keys.none { it.equals("Range", true) }) {
                extra["Range"] = "bytes=0-${capBytes - 1}"
            }
            if (extra.keys.none { it.equals("User-Agent", true) }) {
                extra["User-Agent"] = FrSettings.nuvioUserAgent.ifBlank { FrSettings.DEFAULT_USER_AGENT }
            }
            val result = FrRuntime.rawExecute(url, "GET", extra, probe = true, maxBytes = capBytes)
                ?: return StreamProbe(0, null, null)
            return StreamProbe(
                result.status,
                result.body.takeIf(String::isNotBlank),
                result.contentType,
            )
        }
        return legacyProbe(url, headers, capBytes, ranged)
    }

    private fun legacyProbe(
        url: String,
        headers: Map<String, String>,
        capBytes: Int,
        ranged: Boolean,
    ): StreamProbe {
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return StreamProbe(0, null, null)
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", FrSettings.nuvioUserAgent.ifBlank { FrSettings.DEFAULT_USER_AGENT })
            conn.setRequestProperty("Accept", if (ranged) "*/*" else "application/vnd.apple.mpegurl,*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> runCatching { conn.setRequestProperty(name, value) } }
            if (ranged) conn.setRequestProperty("Range", "bytes=0-${capBytes - 1}")

            val status = conn.responseCode
            val body = if (status in 200..399) {
                val input = conn.inputStream
                val buffer = java.io.ByteArrayOutputStream(capBytes)
                val chunk = ByteArray(4096)
                var total = 0
                try {
                    while (total < capBytes) {
                        val read = input.read(chunk, 0, minOf(chunk.size, capBytes - total))
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                        total += read
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { input.close() }
                }
                String(buffer.toByteArray(), Charsets.ISO_8859_1).takeIf { it.isNotBlank() }
            } else {
                runCatching { conn.errorStream?.close() }
                null
            }
            StreamProbe(status, body, null)
        } catch (_: Throwable) {
            StreamProbe(0, null, null)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun httpGet(url: String, extraHeaders: Map<String, String>): String =
        doHttp(url, "GET", extraHeaders, null).second

    private fun doHttp(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): Pair<Int, String> {
        val effective = linkedMapOf<String, String>()
        effective.putAll(headers)
        if (effective.keys.none { it.equals("User-Agent", true) }) {
            effective["User-Agent"] = FrSettings.nuvioUserAgent.ifBlank { FrSettings.DEFAULT_USER_AGENT }
        }
        if (FrRuntime.isHttpReady) {
            val result = FrRuntime.rawExecute(url, method, effective, body)
                ?: return 0 to ""
            return result.status to result.body
        }
        return legacyDoHttp(url, method, effective, body)
    }

    private fun legacyDoHttp(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): Pair<Int, String> {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return 0 to ""
        }
        try {
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = NETWORK_TIMEOUT_MS
            conn.readTimeout = 25_000
            conn.instanceFollowRedirects = true
            if (headers.keys.none { it.equals("User-Agent", true) }) {
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                )
            }
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> runCatching { conn.setRequestProperty(k, v) } }

            if (body != null) {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } catch (t: Throwable) {
            return 0 to ""
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------ fonctions JS

    /**
     * Variables d'environnement effectives d'une source : les valeurs par défaut
     * du manifest complétées par les valeurs saisies dans les réglages.
     */
    fun mergedEnv(scraper: NuvioScraper): Map<String, String> =
        scraper.envDefaults + FrSettings.sourceEnvValues(scraper.id)

    /** Clés obligatoires manquantes (sans valeur ni valeur par défaut). */
    fun missingRequiredEnv(scraper: NuvioScraper): List<String> {
        val merged = mergedEnv(scraper)
        return scraper.requiredEnv.filter { merged[it].isNullOrBlank() }
    }

    /** Injecte les clés API globales et la configuration de la source dans process.env. */
    private fun injectEnv(scope: Scriptable, scraper: NuvioScraper) {
        runCatching {
            val process = scope.get("process", scope) as? Scriptable ?: return
            val env = ScriptableObject.getProperty(process, "env") as? Scriptable ?: return
            FrSettings.apiTokens.forEach { (k, v) ->
                env.put(k, env, v)
            }
            mergedEnv(scraper).forEach { (k, v) ->
                env.put(k, env, v)
            }
        }
    }

    /**
     * Test rapide d'UN scrapeur (bouton « Tester » des réglages).
     * Le contenu de test est adapté au type du scrapeur : les sites d'ANIMÉS
     * (Anime-Sama, Neko-Sama, VostFree…) ne connaissent pas « Fight Club » —
     * il faut un titre d'anime (One Piece, TMDB 37854) pour tester leur chemin
     * réel. Les sites movies/tv sont testés sur Fight Club (TMDB 550).
     */
    suspend fun testProvider(id: String): String {
        val scraper = trySuspend { scrapers().firstOrNull { it.id == id } }.getOrNull()
            ?: return "✗ source introuvable"
        val types = scraper.supportedTypes.map { it.lowercase() }
        val animeLike = id in ANIME_FOCUSED_IDS ||
            types.any { it.contains("anime") || it.contains("cartoon") } ||
            types.isEmpty() ||
            scraper.name.lowercase().contains("anime") ||
            scraper.name.lowercase().contains("sama") ||
            scraper.name.lowercase().contains("vost") ||
            scraper.name.lowercase().contains("manga")
        val wantBoth = types.any { it.contains("anime") } && types.any { it == "movie" || it == "tv" || it == "series" }

        val cases = buildList {
            if (!animeLike || wantBoth) {
                add(
                    TestCase(
                        "film",
                        550,
                        "movie",
                        0,
                        0,
                        PlayPayload(kind = "movie", titles = listOf("Fight Club"), year = 1999, tmdbId = 550),
                    ),
                )
            }
            if (animeLike) {
                add(
                    TestCase(
                        "anime",
                        37854,
                        "tv",
                        1,
                        1,
                        PlayPayload(
                            kind = "tv",
                            titles = listOf("One Piece"),
                            year = 1999,
                            tmdbId = 37854,
                            malId = 21,
                            anilistId = 21,
                            season = 1,
                            episode = 1,
                        ),
                    ),
                )
            }
        }
        val out = StringBuilder()
        var anyOk = false
        for (tc in cases) {
            val links = java.util.concurrent.CopyOnWriteArrayList<Video>()
            val startedAt = System.currentTimeMillis()
            // Les tests partent TOUS en parallèle depuis l'écran de réglages ;
            // sur un réseau mobile, 26 moteurs Rhino simultanés saturaient tout
            // et chaque scrapeur dépassait son timeout. Sémaphore dédié (2 max) :
            // tests doux ET jamais bloquants pour la lecture réelle.
            val ok = trySuspend {
                testSemaphore.withPermit {
                    withTimeoutOrNull(150_000L) {
                        runScraper(scraper, tc.tmdbId, tc.type, tc.season, tc.episode, tc.payload) { links += it }
                    } ?: false
                }
            }.getOrDefault(false)
            val elapsed = System.currentTimeMillis() - startedAt
            if (ok) {
                anyOk = true
                out.append("${tc.label}: ${links.size} lien(s) en ${elapsed}ms")
            } else {
                val diag = diagnostics()[scraper.id] ?: "aucun résultat (timeout ?)"
                out.append(if (diag.startsWith("✓")) "${tc.label}: $diag" else "${tc.label}: ✗ $diag")
            }
        }
        val txt = out.toString()
        return if (anyOk) "✓ " + txt.replace(Regex(": ✓ |: ✗ "), " · ") else txt
    }

    private data class TestCase(
        val label: String,
        val tmdbId: Int,
        val type: String,
        val season: Int,
        val episode: Int,
        val payload: PlayPayload,
    )

    private val fetchExecutor = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "frunified-js-fetch").apply { isDaemon = true }
    }

    /** Pont asynchrone par runtime : aucun objet Rhino ne quitte son thread propriétaire. */
    private class AsyncFetchBridge(private val scraperId: String) {
        private data class Completion(val id: Int, val status: Int, val text: String)
        private val serial = AtomicInteger()
        private val completions = ConcurrentLinkedQueue<Completion>()

        inner class QueueFunction : BaseFunction() {
            @Suppress("DEPRECATION")
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any {
                val url = args.getOrNull(0)?.toString()?.takeIf { it.startsWith("http") } ?: return -1
                val opts = args.getOrNull(1) as? Scriptable
                val method = runCatching {
                    ScriptableObject.getProperty(opts, "method") as? String
                }.getOrNull()?.uppercase() ?: "GET"
                val headers = linkedMapOf<String, String>()
                if (FrSettings.nuvioUserAgent.isNotBlank()) headers["User-Agent"] = FrSettings.nuvioUserAgent
                if (FrSettings.nuvioReferer.isNotBlank()) headers["Referer"] = FrSettings.nuvioReferer
                if (FrSettings.nuvioCookies.isNotBlank()) headers["Cookie"] = FrSettings.nuvioCookies
                runCatching {
                    val values = ScriptableObject.getProperty(opts, "headers") as? Scriptable
                    values?.ids?.forEach { key ->
                        if (key is String) {
                            val value = ScriptableObject.getProperty(values, key)?.toString()
                            if (!value.isNullOrBlank()) headers[key] = value
                        }
                    }
                }
                val body = runCatching {
                    ScriptableObject.getProperty(opts, "body").takeUnless {
                        it == null || it == Scriptable.NOT_FOUND || it is com.frunified.rhino.Undefined
                    }?.toString()
                }.getOrNull()
                val id = serial.incrementAndGet()
                fetchExecutor.execute {
                    val started = System.currentTimeMillis()
                    val (status, text) = doHttp(url, method, headers, body)
                    val host = runCatching { URL(url).host }.getOrDefault("?")
                    val list = fetchLog.computeIfAbsent(scraperId) { mutableListOf() }
                    synchronized(list) {
                        list.add("$method $host → $status (${System.currentTimeMillis() - started}ms)")
                        while (list.size > 40) list.removeAt(0)
                    }
                    completions.add(Completion(id, status, text))
                }
                return id
            }
        }

        inner class PollFunction : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any {
                val array = JSONArray()
                while (true) {
                    val item = completions.poll() ?: break
                    array.put(JSONObject().put("id", item.id).put("status", item.status).put("text", item.text))
                }
                return array.toString()
            }
        }
    }

    /** Ancien pont synchrone conservé pour compatibilité de tests binaires ; le runtime utilise AsyncFetchBridge. */
    private class FetchFunction : BaseFunction() {
        @Suppress("DEPRECATION")
        override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any? {
            val url = args.getOrNull(0)?.toString()?.takeIf { it.startsWith("http") }
            if (url == null) return makeResponse(cx, scope, 0, "Invalid URL")

            val opts = args.getOrNull(1) as? Scriptable
            val method = (
                runCatching {
                    ScriptableObject.getProperty(opts, "method") as? String
                }.getOrNull()
                )?.uppercase() ?: "GET"

            val headerMap = linkedMapOf<String, String>()
            // En-têtes « contournement Cloudflare » par défaut (réglages ⚙️),
            // les en-têtes fournis par le bundle priment.
            runCatching {
                if (FrSettings.nuvioUserAgent.isNotBlank()) headerMap["User-Agent"] = FrSettings.nuvioUserAgent
                if (FrSettings.nuvioReferer.isNotBlank()) headerMap["Referer"] = FrSettings.nuvioReferer
                if (FrSettings.nuvioCookies.isNotBlank()) headerMap["Cookie"] = FrSettings.nuvioCookies
            }
            runCatching {
                val headersObj = ScriptableObject.getProperty(opts, "headers")
                if (headersObj is Scriptable) {
                    for (id in headersObj.ids) {
                        if (id is String) {
                            val value = ScriptableObject.getProperty(headersObj, id)?.toString()
                            if (!value.isNullOrBlank()) headerMap[id] = value
                        }
                    }
                }
            }

            val body = runCatching {
                val value = ScriptableObject.getProperty(opts, "body")
                value.takeUnless {
                    it == null || it == Scriptable.NOT_FOUND || it is com.frunified.rhino.Undefined
                }?.toString()?.takeIf(String::isNotEmpty)
            }.getOrNull()

            val t0 = System.currentTimeMillis()
            val (status, text) = doHttp(url, method, headerMap, body)
            val ms = System.currentTimeMillis() - t0
            currentScraper.get()?.let { id ->
                runCatching {
                    val host = runCatching { URL(url).host }.getOrDefault("?")
                    val list = fetchLog.computeIfAbsent(id) { mutableListOf() }
                    list.add("$method $host → $status (${ms}ms)")
                    while (list.size > 40) list.removeAt(0)
                }
            }
            return makeResponse(cx, scope, status, text)
        }

        private fun makeResponse(cx: RhinoContext, scope: Scriptable, status: Int, text: String): Any? =
            runCatching {
                ScriptableObject.callMethod(scope, "__makeResponse", arrayOf<Any?>(status, text))
            }.getOrNull()
                // Undefined (et non Scriptable.NOT_FOUND, objet Java qui déclenche
                // l'avertissement « missed Context.javaToJS() » à chaque test de vérité).
                ?: com.frunified.rhino.Undefined.instance
    }

    private class B64Function(private val encode: Boolean) : BaseFunction() {
        @Suppress("DEPRECATION")
        override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<Any>): Any? {
            val input = args.getOrNull(0)?.toString() ?: return ""
            return if (encode) {
                android.util.Base64.encodeToString(input.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            } else {
                runCatching {
                    String(android.util.Base64.decode(input, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault("")
            }
        }
    }

    // ------------------------------------------------------- préambule JS

    internal val JS_ENV = """
var __console_lines = [];
var console = {
  log:function(x){ try { __console_lines.push(String(x)); if (__console_lines.length > 25) __console_lines.shift(); } catch (e) {} },
  warn:function(x){ try { __console_lines.push('[warn] ' + String(x)); if (__console_lines.length > 25) __console_lines.shift(); } catch (e) {} },
  error:function(x){ try { __console_lines.push('[error] ' + String(x)); if (__console_lines.length > 25) __console_lines.shift(); } catch (e) {} },
  debug:function(x){}
};
var global = this;
var window = this;
var self = this;
var globalThis = this;
var process = { env: {} };
var navigator = { userAgent: 'Mozilla/5.0 (Android)' };
var location = { href: 'https://frunified.fr/', protocol: 'https:', hostname: 'frunified.fr', origin: 'https://frunified.fr' };
var __timers = [], __timerSerial = 0, __fetchCallbacks = {}, __fetchPending = 0;
function setTimeout(fn, ms) {
  var id = ++__timerSerial;
  __timers.push({ id:id, fn:fn, due:Date.now() + Math.max(0, Number(ms) || 0), interval:0 });
  return id;
}
function clearTimeout(id) { __timers = __timers.filter(function(t){ return t.id !== id; }); }
function setInterval(fn, ms) {
  var id = ++__timerSerial, delay = Math.max(1, Number(ms) || 0);
  __timers.push({ id:id, fn:fn, due:Date.now() + delay, interval:delay });
  return id;
}
function clearInterval(id) { clearTimeout(id); }
function fetch(url, opts) {
  return new Promise(function(resolve, reject) {
    var id = __queueFetch(String(url), opts || {});
    if (id < 0) { reject(new Error('Invalid URL')); return; }
    __fetchPending++;
    __fetchCallbacks[id] = { resolve:resolve, reject:reject };
  });
}
function __drain() {
  var completed = [];
  try { completed = JSON.parse(String(__pollFetch())); } catch (e) {}
  for (var c = 0; c < completed.length; c++) {
    var item = completed[c], callback = __fetchCallbacks[item.id];
    if (!callback) continue;
    delete __fetchCallbacks[item.id]; __fetchPending--;
    try { callback.resolve(__makeResponse(item.status, item.text)); } catch (e) { callback.reject(e); }
  }
  var now = Date.now(), waiting = [];
  for (var i = 0; i < __timers.length; i++) {
    var timer = __timers[i];
    if (timer.due <= now) {
      try { timer.fn(); } catch (e) {}
      if (timer.interval > 0) { timer.due = now + timer.interval; waiting.push(timer); }
    } else waiting.push(timer);
  }
  __timers = waiting;
}
function __timerCount() { return __timers.length + __fetchPending; }

// ----- Polyfill du patch spread du moteur Rhino embarqué :
// f(...a) -> f.apply(this, __spread(a)) ; [a, ...b] -> __spread([a], b)
function __spread() {
  var a = [];
  for (var i = 0; i < arguments.length; i++) {
    var v = arguments[i];
    if (Array.isArray(v)) { for (var j = 0; j < v.length; j++) a.push(v[j]); }
    else a.push(v);
  }
  return a;
}
function __frToArray(value) {
  if (value == null) return [];
  if (typeof value.length === 'number') return value;
  var out = [];
  try {
    if (typeof Symbol !== 'undefined' && Symbol.iterator && typeof value[Symbol.iterator] === 'function') {
      var iterator = value[Symbol.iterator](), step;
      while (!(step = iterator.next()).done) out.push(step.value);
      return out;
    }
  } catch (e) {}
  try {
    if (typeof value.forEach === 'function') {
      value.forEach(function (entry) { out.push(entry); });
    }
  } catch (e) {}
  return out;
}

// ----- Promise minimale (chaînes .then synchrones + helpers babel asyncToGenerator)
function Promise(executor) {
  var self = this;
  self.__callbacks = []; self.__value = undefined; self.__settled = false; self.__rejected = false;
  function resolve(v) {
    if (self.__settled) return;
    if (v && typeof v.then === 'function') { try { v.then(resolve, reject); } catch (e) { reject(e); } return; }
    self.__settled = true; self.__value = v; self.__run();
  }
  function reject(e) { if (self.__settled) return; self.__settled = true; self.__rejected = true; self.__value = e; self.__run(); }
  self.then = function (onF, onR) {
    return new Promise(function (res, rej) {
      self.__callbacks.push({ f: onF, r: onR, res: res, rej: rej });
      self.__run();
    });
  };
  self.catch = function (onR) { return self.then(undefined, onR); };
  self.finally = function (onF) {
    return self.then(function (v) { if (onF) onF(); return v; }, function (e) { if (onF) onF(); throw e; });
  };
  self.__run = function () {
    if (!self.__settled) return;
    var cbs = self.__callbacks; self.__callbacks = [];
    for (var i = 0; i < cbs.length; i++) {
      (function (cb) {
        try {
          if (self.__rejected) {
            if (cb.r) { cb.res(cb.r(self.__value)); } else { cb.rej(self.__value); }
          } else {
            if (cb.f) { cb.res(cb.f(self.__value)); } else { cb.res(self.__value); }
          }
        } catch (e) { cb.rej(e); }
      })(cbs[i]);
    }
  };
  try { executor(resolve, reject); } catch (e) { reject(e); }
}
Promise.resolve = function (v) { return new Promise(function (res) { res(v); }); };
Promise.reject = function (e) { return new Promise(function (_, rej) { rej(e); }); };
Promise.all = function (arr) {
  return new Promise(function (res, rej) {
    var out = [], n = (arr && arr.length) || 0, c = 0;
    if (!n) { res(out); return; }
    for (var i = 0; i < n; i++) {
      (function (i) { try { Promise.resolve(arr[i]).then(function (v) { out[i] = v; if (++c === n) res(out); }, rej); } catch (e) { rej(e); } })(i);
    }
  });
};
Promise.race = function (arr) {
  return new Promise(function (res, rej) {
    for (var i = 0; i < ((arr && arr.length) || 0); i++) { Promise.resolve(arr[i]).then(res, rej); }
  });
};
Promise.allSettled = function (arr) {
  arr = arr || [];
  return new Promise(function (res) {
    var out = [], n = arr.length, c = 0;
    if (!n) { res(out); return; }
    function done(i, v, settled) {
      out[i] = settled ? { status: 'fulfilled', value: v } : { status: 'rejected', reason: v };
      if (++c === n) res(out);
    }
    for (var i = 0; i < n; i++) {
      (function (i) {
        try { Promise.resolve(arr[i]).then(function (v) { done(i, v, true); }, function (e) { done(i, e, false); }); }
        catch (e) { done(i, e, false); }
      })(i);
    }
  });
};
Promise.any = function (arr) {
  arr = arr || [];
  return new Promise(function (res, rej) {
    var errors = [], c = 0;
    if (!arr.length) { rej(new Error('All promises were rejected')); return; }
    for (var i = 0; i < arr.length; i++) {
      (function (i) {
        try { Promise.resolve(arr[i]).then(res, function (e) { errors[i] = e; if (++c === arr.length) rej(new Error('All promises were rejected')); }); }
        catch (e) { errors[i] = e; if (++c === arr.length) rej(new Error('All promises were rejected')); }
      })(i);
    }
  });
};

// ----- String.prototype.matchAll (retourne un vrai tableau : spreadable et .map/.concat utilisables)
if (!String.prototype.matchAll) {
  String.prototype.matchAll = function (re) {
    var str = String(this);
    var flags = '';
    if (re) {
      if (re.ignoreCase) flags += 'i';
      if (re.multiline) flags += 'm';
      if (re.dotAll) flags += 's';
      if (re.unicode) flags += 'u';
      if (re.sticky) flags += 'y';
    }
    var rx = (re && re.global) ? re : new RegExp(re ? re.source : String(re), 'g' + flags);
    if (!rx.global && !rx.sticky) rx = new RegExp(rx.source, flags + 'g');
    rx.lastIndex = 0;
    var out = [];
    var m;
    while ((m = rx.exec(str)) !== null) {
      out.push(m);
      if (m.index === rx.lastIndex) rx.lastIndex++;
    }
    // supporte aussi l'usage en itérateur : it.next()
    var idx = 0;
    out.next = function () {
      if (idx < out.length) return { value: out[idx++], done: false };
      return { value: undefined, done: true };
    };
    return out;
  };
}

// ----- Array.prototype.flatMap / flat (niveau 1, usage bundlé courant)
if (!Array.prototype.flatMap) {
  Array.prototype.flatMap = function (fn, thisArg) {
    var out = [];
    for (var i = 0; i < this.length; i++) {
      if (!(i in this)) continue;
      var v = fn.call(thisArg, this[i], i, this);
      if (Array.isArray(v)) { for (var j = 0; j < v.length; j++) out.push(v[j]); }
      else out.push(v);
    }
    return out;
  };
}
if (!Array.prototype.flat) {
  Array.prototype.flat = function (depth) {
    var d = depth == null ? 1 : depth;
    var out = [];
    for (var i = 0; i < this.length; i++) {
      var v = this[i];
      if (Array.isArray(v) && d > 0) {
        var sub = d === Infinity ? v.flat(Infinity) : v.flat(d - 1);
        for (var j = 0; j < sub.length; j++) out.push(sub[j]);
      } else out.push(v);
    }
    return out;
  };
}

// ----- String : padStart / padEnd / trimStart / trimEnd / includes / startsWith / endsWith
// (usage courant des bundles transpilés ; Rhino 1.9 ne les garantit pas tous)
if (!String.prototype.padStart) {
  String.prototype.padStart = function (len, fill) {
    var s = String(this);
    len = Math.max(0, Math.floor(Number(len) || 0));
    if (s.length >= len) return s;
    var pad = (fill == null) ? ' ' : String(fill);
    if (!pad.length) pad = ' ';
    var out = '';
    while (out.length < len - s.length) out += pad;
    return out.slice(0, len - s.length) + s;
  };
}
if (!String.prototype.padEnd) {
  String.prototype.padEnd = function (len, fill) {
    var s = String(this);
    len = Math.max(0, Math.floor(Number(len) || 0));
    if (s.length >= len) return s;
    var pad = (fill == null) ? ' ' : String(fill);
    if (!pad.length) pad = ' ';
    var out = '';
    while (out.length < len - s.length) out += pad;
    return s + out.slice(0, len - s.length);
  };
}
if (!String.prototype.trimStart) String.prototype.trimStart = function () { return String(this).replace(/^\s+/, ''); };
if (!String.prototype.trimEnd) String.prototype.trimEnd = function () { return String(this).replace(/\s+$/, ''); };
if (!String.prototype.trimLeft) String.prototype.trimLeft = String.prototype.trimStart;
if (!String.prototype.trimRight) String.prototype.trimRight = String.prototype.trimEnd;
if (!String.prototype.includes) {
  String.prototype.includes = function (sub, pos) {
    return String(this).indexOf(String(sub), pos || 0) !== -1;
  };
}
if (!String.prototype.startsWith) {
  String.prototype.startsWith = function (pre, pos) {
    var s = String(this);
    pos = Math.max(0, Math.min(Number(pos) || 0, s.length));
    return s.substring(pos, pos + String(pre).length) === String(pre);
  };
}
if (!String.prototype.endsWith) {
  String.prototype.endsWith = function (suf, len) {
    var s = String(this);
    len = len == null ? s.length : Math.min(Number(len) || 0, s.length);
    return s.substring(len - String(suf).length, len) === String(suf);
  };
}

// ----- Array : includes / find / findIndex / Array.from (ES2015-2017)
if (!Array.prototype.includes) {
  Array.prototype.includes = function (v, from) {
    for (var i = (from || 0); i < this.length; i++) {
      if (this[i] === v || (this[i] !== this[i] && v !== v)) return true;
    }
    return false;
  };
}
if (!Array.prototype.find) {
  Array.prototype.find = function (fn, thisArg) {
    for (var i = 0; i < this.length; i++) {
      if (i in this && fn.call(thisArg, this[i], i, this)) return this[i];
    }
    return undefined;
  };
}
if (!Array.prototype.findIndex) {
  Array.prototype.findIndex = function (fn, thisArg) {
    for (var i = 0; i < this.length; i++) {
      if (i in this && fn.call(thisArg, this[i], i, this)) return i;
    }
    return -1;
  };
}
if (!Array.from) {
  Array.from = function (obj, mapFn, thisArg) {
    var out = [];
    if (obj) {
      if (typeof obj.length === 'number') {
        for (var i = 0; i < obj.length; i++) {
          if (!(i in obj)) continue;
          out.push(mapFn ? mapFn.call(thisArg, obj[i], i) : obj[i]);
        }
      } else {
        for (var k in obj) {
          if (Object.prototype.hasOwnProperty.call(obj, k)) {
            out.push(mapFn ? mapFn.call(thisArg, obj[k], k) : obj[k]);
          }
        }
      }
    }
    return out;
  };
}

// ----- Object.assign / Number.isInteger / Number.isNaN / Number.isFinite
if (!Object.assign) {
  Object.assign = function (target) {
    for (var i = 1; i < arguments.length; i++) {
      var src = arguments[i];
      if (src == null) continue;
      for (var k in src) {
        if (Object.prototype.hasOwnProperty.call(src, k)) target[k] = src[k];
      }
    }
    return target;
  };
}
if (!Number.isInteger) {
  Number.isInteger = function (v) {
    return typeof v === 'number' && isFinite(v) && Math.floor(v) === v;
  };
}
if (!Number.isNaN) Number.isNaN = function (v) { return typeof v === 'number' && isNaN(v); };
if (!Number.isFinite) Number.isFinite = function (v) { return typeof v === 'number' && isFinite(v); };

// ----- performance.now (Date.now en repli)
if (typeof performance !== 'object' || !performance || !performance.now) {
  var performance = { now: function () { return Date.now(); } };
}

// ----- Réponse fetch
function __makeResponse(status, text) {
  return {
    ok: status >= 200 && status < 300,
    status: status,
    statusText: '',
    url: '',
    headers: { get: function () { return null; } },
    text: function () { return Promise.resolve(String(text == null ? '' : text)); },
    json: function () { return Promise.resolve(JSON.parse(String(text == null ? '' : text))); },
    arrayBuffer: function () { return Promise.resolve({}); }
  };
}

// ----- Base64
var __B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function __b64EncodeLocal(input) {
  var output = '';
  for (var i = 0; i < input.length;) {
    var c1 = input.charCodeAt(i++) & 0xff;
    var c2 = i < input.length ? input.charCodeAt(i++) & 0xff : NaN;
    var c3 = i < input.length ? input.charCodeAt(i++) & 0xff : NaN;
    var e1 = c1 >> 2;
    var e2 = ((c1 & 3) << 4) | (isNaN(c2) ? 0 : (c2 >> 4));
    var e3 = isNaN(c2) ? 64 : (((c2 & 15) << 2) | (isNaN(c3) ? 0 : (c3 >> 6)));
    var e4 = isNaN(c3) ? 64 : (c3 & 63);
    output += __B64.charAt(e1) + __B64.charAt(e2) + (e3 === 64 ? '=' : __B64.charAt(e3)) + (e4 === 64 ? '=' : __B64.charAt(e4));
  }
  return output;
}
function atob(s) { return __b64Decode(String(s)); }
function btoa(s) { return __b64Encode(String(s)); }

// ----- Buffer utilitaire
function Buffer(data, enc) { this._d = data; this._enc = enc; }
Buffer.from = function (data, enc) {
  var b = { _d: data, _enc: enc };
  b.toString = function (e) {
    var s = String(this._d);
    if (this._enc === 'base64' || e === 'utf8') return __b64Decode(s);
    return s;
  };
  return b;
};
Buffer.isBuffer = function () { return false; };
Buffer.alloc = function (n) { return { toString: function () { return ''; } }; };

// ----- URL
function URL(input, base) {
  var parts = __parseUrl(String(input), base);
  this.href = parts[0]; this.protocol = parts[1]; this.host = parts[2];
  this.hostname = parts[2].split(':')[0]; this.port = parts[2].split(':')[1] || '';
  this.origin = parts[1] + '//' + parts[2]; this.pathname = parts[3];
  this.search = parts[4]; this.hash = parts[5];
  this.toString = function () { return this.href; };
  this.resolve = function (rel) { return new URL(rel, this.href); };
}
function __parseUrl(input, base) {
  var url = String(input);
  if (url.indexOf('//') === 0) url = 'https:' + url;
  if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(url) && base) {
    var m = /^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\/?#]*)/.exec(String(base));
    if (m) {
      var host = m[1];
      if (url.charAt(0) === '/') { url = host + url; }
      else {
        var rest = String(base).substring(m[1].length);
        var dir = rest.substring(0, rest.lastIndexOf('/') + 1);
        url = host + dir + url;
      }
    }
  }
  var mm = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^\/?#]*)([/?#][^]*)?$/.exec(url);
  var protocol = mm ? mm[1] + ':' : '';
  var host = mm ? mm[2] : '';
  var rest = mm && mm[3] ? mm[3] : '';
  var path = rest.split('?')[0].split('#')[0];
  var search = rest.indexOf('?') >= 0 ? '?' + rest.substring(rest.indexOf('?') + 1).split('#')[0] : '';
  var hash = rest.indexOf('#') >= 0 ? rest.substring(rest.indexOf('#')) : '';
  return [url, protocol, host, path, search, hash];
}

// ----- TextEncoder / TextDecoder
function TextEncoder() {}
TextEncoder.prototype.encode = function (s) {
  var str = String(s == null ? '' : s); var out = [];
  try { str = unescape(encodeURIComponent(str)); } catch (e) {}
  for (var i = 0; i < str.length; i++) out.push(str.charCodeAt(i) & 0xff);
  out.toString = function () { return str; }; return out;
};
function TextDecoder(encoding) { this.encoding = encoding || 'utf-8'; }
TextDecoder.prototype.decode = function (bytes) {
  if (!bytes) return '';
  if (typeof bytes === 'string') return bytes;
  var arr = Array.isArray(bytes) ? bytes : (bytes._d || bytes);
  if (arr && typeof arr.length === 'number') {
    var s = '';
    for (var i = 0; i < arr.length; i++) s += String.fromCharCode(arr[i] & 0xff);
    try { return decodeURIComponent(escape(s)); } catch (e) { return s; }
  }
  return String(bytes || '');
};

// ----- Object.fromEntries / Object.values / Object.entries
if (!Object.fromEntries) {
  Object.fromEntries = function (entries) {
    if (!entries) return {};
    var out = {};
    for (var i = 0; i < entries.length; i++) {
      var pair = entries[i];
      if (pair && pair.length >= 2) out[pair[0]] = pair[1];
    }
    return out;
  };
}
if (!Object.values) {
  Object.values = function (o) {
    var out = [];
    if (!o) return out;
    for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) out.push(o[k]);
    return out;
  };
}
if (!Object.entries) {
  Object.entries = function (o) {
    var out = [];
    if (!o) return out;
    for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) out.push([k, o[k]]);
    return out;
  };
}

// ----- String.prototype.replaceAll
if (!String.prototype.replaceAll) {
  String.prototype.replaceAll = function (search, replacement) {
    if (search instanceof RegExp) {
      var flags = search.flags.indexOf('g') >= 0 ? search.flags : search.flags + 'g';
      return this.replace(new RegExp(search.source, flags), replacement);
    }
    return this.split(String(search)).join(String(replacement));
  };
}

// ----- require ("util", "https", …)
function require(name) {
  if (name === 'util') {
    return {
      inspect: function (o) { try { return String(o); } catch (e) { return '[object]'; } },
      format: function (f) { return f; },
      isArray: Array.isArray,
      types: { isBuffer: function () { return false; } }
    };
  }
  throw new Error('Module not supported: ' + name);
}

// ----- AbortController (signaux d'annulation fetch)
function AbortSignal() {}
AbortSignal.prototype.addEventListener = function (t, fn) {
  if (t === 'abort') (this._listeners = this._listeners || []).push(fn);
};
AbortSignal.prototype.removeEventListener = function (t, fn) {
  if (t === 'abort' && this._listeners) {
    var i = this._listeners.indexOf(fn);
    if (i >= 0) this._listeners.splice(i, 1);
  }
};
AbortSignal.timeout = function (ms) {
  var s = new AbortSignal();
  s.aborted = false;
  if (typeof setTimeout === 'function') {
    setTimeout(function () { s.aborted = true; }, Number(ms) || 0);
  }
  return s;
};
AbortSignal.abort = function (reason) {
  var s = new AbortSignal();
  s.aborted = true;
  s.reason = reason;
  return s;
};
function AbortController() {
  this.signal = new AbortSignal();
  this.signal.aborted = false;
}
AbortController.prototype.abort = function (reason) {
  var s = this.signal;
  if (s.aborted) return;
  s.aborted = true; s.reason = reason;
  var ls = s._listeners || [];
  s._listeners = [];
  for (var i = 0; i < ls.length; i++) { try { ls[i](); } catch (e) {} }
};

// ----- URLSearchParams
function URLSearchParams(init) {
  this._m = {};
  if (typeof init === 'string') {
    var parts = init.replace(/^\?/, '').split('&');
    for (var i = 0; i < parts.length; i++) {
      if (!parts[i]) continue;
      var kv = parts[i].split('=');
      this._m[decodeURIComponent(kv[0].replace(/\+/g, ' '))] =
        decodeURIComponent((kv[1] || '').replace(/\+/g, ' '));
    }
  }
}
URLSearchParams.prototype.get = function (k) { return this._m.hasOwnProperty(k) ? this._m[k] : null; };
URLSearchParams.prototype.set = function (k, v) { this._m[k] = String(v); };
URLSearchParams.prototype.append = function (k, v) {
  var cur = this._m[k];
  this._m[k] = cur == null ? String(v) : cur + ',' + v;
};
URLSearchParams.prototype.has = function (k) { return this._m.hasOwnProperty(k); };
URLSearchParams.prototype.toString = function () {
  var out = [];
  for (var k in this._m) {
    if (this._m.hasOwnProperty(k)) out.push(encodeURIComponent(k) + '=' + encodeURIComponent(this._m[k]));
  }
  return out.join('&');
};

// ----- Headers (objet simple, accepte objets et tableaux)
function Headers(init) {
  this._h = {};
  if (init) {
    var self = this;
    if (typeof init.forEach === 'function') { init.forEach(function (v, k) { self._h[String(k).toLowerCase()] = String(v); }); }
    else if (Array.isArray(init)) { for (var i = 0; i < init.length; i++) this._h[String(init[i][0]).toLowerCase()] = String(init[i][1]); }
    else { for (var k in init) if (init.hasOwnProperty(k)) this._h[k.toLowerCase()] = String(init[k]); }
  }
}
Headers.prototype.get = function (k) { return this._h.hasOwnProperty(k.toLowerCase()) ? this._h[k.toLowerCase()] : null; };
Headers.prototype.set = function (k, v) { this._h[k.toLowerCase()] = String(v); };
Headers.prototype.has = function (k) { return this._h.hasOwnProperty(k.toLowerCase()); };
Headers.prototype.append = function (k, v) {
  var key = k.toLowerCase();
  this._h[key] = this._h.hasOwnProperty(key) ? this._h[key] + ', ' + v : String(v);
};
Headers.prototype.forEach = function (fn) { for (var k in this._h) if (this._h.hasOwnProperty(k)) fn(this._h[k], k); };

// ----- queueMicrotask (exécution immédiate suffit avec nos promesses synchrones)
function queueMicrotask(fn) { try { fn(); } catch (e) {} }

// ----- divers
var crypto = {
  getRandomValues: function (arr) { return arr; },
  randomUUID: function () {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = Math.random() * 16 | 0, v = c === 'x' ? r : ((r & 0x3) | 0x8);
      return v.toString(16);
    });
  }
};
    """.trimIndent()
}

package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Catalogue, fiches et épisodes exposés par les addons Stremio configurés. */
object StremioCatalog {
    private const val MANIFEST_CACHE_MS = 6 * 60 * 60 * 1000L
    private const val META_CACHE_MS = 30 * 60 * 1000L

    data class Extra(
        val name: String,
        val required: Boolean,
        val options: List<String>,
    )

    data class Catalog(
        val addonBase: String,
        val addonName: String,
        val type: String,
        val id: String,
        val name: String,
        val pageSize: Int,
        val extras: List<Extra>,
    ) {
        val key: String get() = Ref(addonBase, type, id).serialize()
        val supportsSearch: Boolean get() = extras.any { it.name.equals("search", true) }
        val supportsSkip: Boolean get() = extras.any { it.name.equals("skip", true) }
        val label: String
            get() = "$addonName · ${typeLabel(type)} · $name"

        fun requiredDefaults(): Map<String, String> = extras.mapNotNull { extra ->
            if (!extra.required || extra.name.equals("search", true) || extra.name.equals("skip", true)) {
                null
            } else {
                extra.options.firstOrNull()?.let { extra.name to it }
            }
        }.toMap()
    }

    data class ResourceRule(
        val name: String,
        val types: Set<String>,
        val idPrefixes: List<String>,
    ) {
        fun supports(type: String?, id: String?): Boolean {
            if (type != null && types.isNotEmpty() && type.lowercase() !in types) return false
            if (id != null && idPrefixes.isNotEmpty() && idPrefixes.none { id.startsWith(it, true) }) return false
            return true
        }
    }

    data class Addon(
        val base: String,
        val name: String,
        val resourceRules: List<ResourceRule>,
        val catalogs: List<Catalog>,
    ) {
        val resources: Set<String> get() = resourceRules.map(ResourceRule::name).toSet()

        fun supports(resource: String, type: String? = null, id: String? = null): Boolean =
            resourceRules.any { it.name == resource && it.supports(type, id) }
    }

    data class StreamTarget(val type: String, val id: String)

    data class StreamAddon(
        val base: String,
        val name: String,
        val targets: List<StreamTarget>,
    )

    data class Ref(val addonBase: String, val type: String, val id: String) {
        fun serialize(): String {
            val value = JSONObject().apply {
                put("addon", addonBase)
                put("type", type)
                put("id", id)
            }.toString()
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        }

        companion object {
            fun parse(value: String): Ref? = runCatching {
                val json = JSONObject(String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8))
                Ref(
                    addonBase = json.getString("addon"),
                    type = json.getString("type"),
                    id = json.getString("id"),
                )
            }.getOrNull()
        }
    }

    private val manifestCache = ConcurrentHashMap<String, Pair<Long, Addon?>>()
    private val metaCache = ConcurrentHashMap<String, Pair<Long, JSONObject?>>()

    @Volatile
    private var catalogSnapshot: List<Catalog> = emptyList()

    /**
     * Tous les éléments `catalogs[]` restent disponibles séparément dans les
     * filtres Aniyomi, y compris avant le prochain rafraîchissement réseau.
     */
    fun cachedCatalogs(): List<Catalog> {
        if (catalogSnapshot.isEmpty()) {
            catalogSnapshot = decodeCatalogCache(FrSettings.stremioCatalogCache)
        }
        val active = FrSettings.stremioUrls
            .map(StremioClient::base)
            .filter(FrSettings::isStremioEnabled)
            .toSet()
        return catalogSnapshot.filter { it.addonBase in active }.distinctBy(Catalog::key)
    }

    @Synchronized
    internal fun rememberCatalogs(addonBase: String, catalogs: List<Catalog>) {
        val base = StremioClient.base(addonBase)
        val addonOrder = FrSettings.stremioUrls.map(StremioClient::base)
        catalogSnapshot = (cachedCatalogsRaw().filterNot { it.addonBase == base } + catalogs)
            .distinctBy(Catalog::key)
            .sortedBy { catalog ->
                addonOrder.indexOf(catalog.addonBase).let { if (it < 0) Int.MAX_VALUE else it }
            }
        val encoded = encodeCatalogCache(catalogSnapshot)
        if (FrSettings.stremioCatalogCache != encoded) {
            FrSettings.saveStremioCatalogCache(encoded)
        }
    }

    private fun cachedCatalogsRaw(): List<Catalog> {
        if (catalogSnapshot.isEmpty()) {
            catalogSnapshot = decodeCatalogCache(FrSettings.stremioCatalogCache)
        }
        return catalogSnapshot
    }

    private fun encodeCatalogCache(catalogs: List<Catalog>): String = JSONArray().apply {
        catalogs.forEach { catalog ->
            put(
                JSONObject().apply {
                    put("base", catalog.addonBase)
                    put("addon", catalog.addonName)
                    put("type", catalog.type)
                    put("id", catalog.id)
                    put("name", catalog.name)
                    put("pageSize", catalog.pageSize)
                    put(
                        "extras",
                        JSONArray().apply {
                            catalog.extras.forEach { extra ->
                                put(
                                    JSONObject().apply {
                                        put("name", extra.name)
                                        put("required", extra.required)
                                        put("options", JSONArray(extra.options))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }.toString()

    private fun decodeCatalogCache(value: String): List<Catalog> = runCatching {
        val array = JSONArray(value)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val base = item.optString("base").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = item.optString("type").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val extras = item.optJSONArray("extras")?.let { entries ->
                (0 until entries.length()).mapNotNull { extraIndex ->
                    val extra = entries.optJSONObject(extraIndex) ?: return@mapNotNull null
                    extra.optString("name").takeIf(String::isNotBlank)?.let { name ->
                        Extra(
                            name = name,
                            required = extra.optBoolean("required", false),
                            options = stringArray(extra.optJSONArray("options")),
                        )
                    }
                }
            }.orEmpty()
            Catalog(
                addonBase = StremioClient.base(base),
                addonName = item.optString("addon").ifBlank { base.substringAfter("://").substringBefore('/') },
                type = type,
                id = id,
                name = item.optString("name").ifBlank { id },
                pageSize = item.optInt("pageSize", 20).coerceIn(1, 200),
                extras = extras,
            )
        }
    }.getOrDefault(emptyList())

    private fun stringArray(array: JSONArray?): List<String> =
        if (array == null) {
            emptyList()
        } else {
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }

    private fun resourceRules(root: JSONObject): List<ResourceRule> {
        val array = root.optJSONArray("resources") ?: return emptyList()
        val globalTypes = stringArray(root.optJSONArray("types")).map(String::lowercase).toSet()
        val globalPrefixes = stringArray(root.optJSONArray("idPrefixes"))
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index)
            val name = entry?.optString("name")?.takeIf(String::isNotBlank)
                ?: array.optString(index).takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            ResourceRule(
                name = name.lowercase(),
                types = entry?.optJSONArray("types")?.let(::stringArray)
                    ?.map(String::lowercase)?.toSet()?.takeIf(Set<String>::isNotEmpty)
                    ?: globalTypes,
                idPrefixes = entry?.optJSONArray("idPrefixes")?.let(::stringArray)
                    ?.takeIf(List<String>::isNotEmpty)
                    ?: globalPrefixes,
            )
        }
    }

    internal fun parseManifest(root: JSONObject, rawBase: String): Addon {
        val base = StremioClient.base(rawBase)
        val name = root.optString("name").ifBlank { base.substringAfter("://").substringBefore('/') }
        val catalogs = root.optJSONArray("catalogs")?.let { entries ->
            (0 until entries.length()).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                val type = entry.optString("type").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val id = entry.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val extras = entry.optJSONArray("extra")?.let { extraArray ->
                    (0 until extraArray.length()).mapNotNull { extraIndex ->
                        val extra = extraArray.optJSONObject(extraIndex) ?: return@mapNotNull null
                        val extraName = extra.optString("name").takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        Extra(
                            name = extraName,
                            required = extra.optBoolean("isRequired", false),
                            options = stringArray(extra.optJSONArray("options")),
                        )
                    }
                }.orEmpty()
                Catalog(
                    addonBase = base,
                    addonName = name,
                    type = type,
                    id = id,
                    name = entry.optString("name").ifBlank { id },
                    pageSize = entry.optInt("pageSize", 20).coerceIn(1, 200),
                    extras = extras,
                )
            }
        }.orEmpty()
        return Addon(
            base = base,
            name = name,
            resourceRules = resourceRules(root),
            catalogs = catalogs,
        )
    }

    private suspend fun loadAddon(rawBase: String): Addon? {
        val base = StremioClient.base(rawBase)
        val now = System.currentTimeMillis()
        manifestCache[base]?.let { (expires, addon) -> if (expires > now) return addon }
        val root = runCatching { FrRuntime.getJson("$base/manifest.json") }.getOrNull()
        if (root == null) {
            manifestCache[base] = (now + 5 * 60 * 1000L) to null
            return null
        }
        val addon = parseManifest(root, base)
        manifestCache[base] = (now + MANIFEST_CACHE_MS) to addon
        rememberCatalogs(base, addon.catalogs)
        return addon
    }

    suspend fun addons(): List<Addon> = coroutineScope {
        FrSettings.stremioUrls
            .filter(FrSettings::isStremioEnabled)
            .distinct()
            .map { base -> async { withTimeoutOrNull(10_000L) { loadAddon(base) } } }
            .awaitAll()
            .filterNotNull()
    }

    suspend fun catalogs(): List<Catalog> {
        val loaded = addons()
            .filter { "catalog" in it.resources }
            .flatMap(Addon::catalogs)
        return (loaded + cachedCatalogs()).distinctBy(Catalog::key)
    }

    suspend fun streamAddons(targets: List<StreamTarget>): List<StreamAddon> {
        val candidates = targets.distinctBy { "${it.type.lowercase()}|${it.id}" }
        if (candidates.isEmpty()) return emptyList()
        val loaded = addons()
        val matched = loaded.mapNotNull { addon ->
            val supported = candidates.filter { addon.supports("stream", it.type, it.id) }
            supported.takeIf { it.isNotEmpty() }?.let {
                StreamAddon(addon.base, addon.name, supported)
            }
        }
        // Un manifest temporairement indisponible ne doit pas neutraliser un addon de lecture.
        val unavailable = FrSettings.stremioUrls.filter(FrSettings::isStremioEnabled)
            .map(StremioClient::base)
            .filterNot { candidate -> loaded.any { it.base == candidate } }
            .filterNot { it.contains("tmdb.elfhosted.com", true) || it.contains("cinemeta", true) }
            .map { base ->
                StreamAddon(
                    base = base,
                    name = base.substringAfter("://").substringBefore('/'),
                    targets = candidates,
                )
            }
        return (matched + unavailable).distinctBy(StreamAddon::base)
    }

    suspend fun streamAddonBases(type: String, id: String): List<String> =
        streamAddons(listOf(StreamTarget(type, id))).map(StreamAddon::base)

    suspend fun selectedCatalog(catalogKey: String = FrSettings.stremioCatalogKey): Catalog? {
        val values = catalogs()
        if (values.isEmpty()) return null
        val selectedRef = Ref.parse(catalogKey)
        if (selectedRef != null) {
            values.firstOrNull {
                it.addonBase == selectedRef.addonBase && it.type == selectedRef.type && it.id == selectedRef.id
            }?.let { return it }
            // La langue du chemin TMDB peut changer sans perdre le catalogue choisi.
            values.firstOrNull {
                it.type == selectedRef.type &&
                    it.id == selectedRef.id &&
                    it.addonBase.substringAfter("://").substringBefore('/') ==
                    selectedRef.addonBase.substringAfter("://").substringBefore('/')
            }?.let { return it }
        }
        return values.firstOrNull {
            it.id.contains("top", true) && it.type.equals("movie", true) && it.requiredDefaults().isEmpty()
        } ?: values.firstOrNull { it.requiredDefaults().isEmpty() }
            ?: values.first()
    }

    suspend fun browse(
        page: Int,
        query: String = "",
        catalogKey: String = FrSettings.stremioCatalogKey,
        selectedExtras: Map<String, String> = emptyMap(),
    ): List<CatalogItem> = coroutineScope {
        val selected = selectedCatalog(catalogKey) ?: return@coroutineScope emptyList()
        val all = catalogs()
        val targets = if (query.isBlank()) {
            listOf(selected)
        } else {
            val sameAddon = all.filter {
                it.addonBase == selected.addonBase &&
                    it.supportsSearch &&
                    (it.id == selected.id || it.id.contains("search", true))
            }
            val byType = sameAddon.distinctBy { it.type.lowercase() }
            when {
                byType.isNotEmpty() -> byType
                selected.supportsSearch -> listOf(selected)
                else -> all.filter(Catalog::supportsSearch).distinctBy { "${it.addonBase}|${it.type}" }.take(4)
            }
        }
        targets.map { catalog ->
            async {
                runCatching { requestCatalog(catalog, page, query, selectedExtras) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.id.serialize() }
    }

    private suspend fun requestCatalog(
        catalog: Catalog,
        page: Int,
        query: String,
        selectedExtras: Map<String, String>,
    ): List<CatalogItem> {
        val extras = linkedMapOf<String, String>()
        extras.putAll(catalog.requiredDefaults())
        selectedExtras.forEach { (name, value) ->
            val manifestName = catalog.extras.firstOrNull { it.name.equals(name, true) }?.name
                ?: return@forEach
            if (value.isBlank()) extras.remove(manifestName) else extras[manifestName] = value
        }
        if (query.isNotBlank() && catalog.supportsSearch) extras["search"] = query
        if (page > 1 && catalog.supportsSkip) extras["skip"] = ((page - 1) * catalog.pageSize).toString()

        val extraPath = extras.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.takeIf(String::isNotBlank)?.let { "/$it" }.orEmpty()
        val url = "${catalog.addonBase}/catalog/${encode(catalog.type)}/${encode(catalog.id)}$extraPath.json"
        val root = FrRuntime.getJson(url)
        val metas = root.optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { index ->
            metas.optJSONObject(index)?.let { metaToItem(it, catalog.addonBase) }
        }
    }

    private fun metaToItem(meta: JSONObject, addonBase: String): CatalogItem? {
        val id = meta.optString("id").takeIf(String::isNotBlank) ?: return null
        val type = meta.optString("type").ifBlank { "movie" }
        val name = meta.optString("name").ifBlank { meta.optString("title") }.takeIf(String::isNotBlank)
            ?: return null
        val release = meta.optString("releaseInfo")
            .ifBlank { meta.optString("released") }
            .ifBlank { meta.optString("year") }
        return CatalogItem(
            id = CatalogId("stremio", type, Ref(addonBase, type, id).serialize()),
            title = name,
            year = Regex("(?:19|20)\\d{2}").find(release)?.value?.toIntOrNull(),
            posterUrl = meta.optString("poster").takeIf { it.startsWith("http") },
            backdropUrl = meta.optString("background").takeIf { it.startsWith("http") },
            overview = meta.optString("description").takeIf { it.isNotBlank() && it != "null" },
            rating10 = meta.optDouble("imdbRating").takeIf { !it.isNaN() && it > 0 },
            format = type,
        )
    }

    suspend fun meta(ref: Ref): JSONObject? {
        val cacheKey = ref.serialize()
        val now = System.currentTimeMillis()
        metaCache[cacheKey]?.let { (expires, value) -> if (expires > now) return value }

        val preferred = listOf(ref.addonBase) +
            addons()
                .filter { it.supports("meta", ref.type, ref.id) }
                .map(Addon::base)
        val value = preferred.distinct().firstNotNullOfOrNull { addon ->
            runCatching {
                FrRuntime.getJson("$addon/meta/${encode(ref.type)}/${encode(ref.id)}.json")
                    .optJSONObject("meta")
            }.getOrNull()
        }
        metaCache[cacheKey] = (now + META_CACHE_MS) to value
        return value
    }

    fun itemFromMeta(meta: JSONObject, ref: Ref): CatalogItem? {
        val item = metaToItem(meta, ref.addonBase) ?: return null
        return item.copy(id = CatalogId("stremio", ref.type, ref.serialize()))
    }

    fun videos(meta: JSONObject): List<JSONObject> {
        val values = meta.optJSONArray("videos") ?: return emptyList()
        return (0 until values.length()).mapNotNull(values::optJSONObject)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun typeLabel(type: String): String = when (type.lowercase()) {
        "movie" -> "Films"
        "series" -> "Séries"
        "anime" -> "Animés"
        "tv" -> "TV"
        else -> type.replaceFirstChar(Char::uppercase)
    }
}

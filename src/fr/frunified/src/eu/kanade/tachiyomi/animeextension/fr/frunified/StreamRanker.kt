package eu.kanade.tachiyomi.animeextension.fr.frunified

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video

/**
 * Classement des flux d'après l'ordre des critères choisi avec les flèches
 * ([FrSettings.streamOrder] : langues et qualités mélangées, du plus au moins souhaité).
 *
 * Un flux est représenté par les positions des critères qu'il satisfait, triées : le
 * premier critère satisfait décide, le suivant départage. Avec l'ordre par défaut
 * `VF, VFF, VFQ, MULTI, VOSTFR, VO, 1080p, 4K, …`, une VF 720p passe donc avant une
 * VOSTFR 1080p, et une VF 1080p avant une VF 720p.
 */
object StreamRanker {
    /** Positions (croissantes) des critères satisfaits ; `[taille]` lorsqu'aucun ne l'est. */
    fun ranks(title: String, resolution: Int?, order: List<String> = FrSettings.streamOrder): List<Int> {
        val label = StreamLabel.parse(title)
        val language = if (label != null) label.language else StreamLabel.languageOf(title)
        val quality = if (label != null) label.quality ?: resolution else resolution ?: StreamLabel.qualityOf(title)
        val matched = listOfNotNull(language, quality?.let(StreamLabel::qualityText))
            .map(order::indexOf)
            .filter { it >= 0 }
            .sorted()
        return matched.ifEmpty { listOf(order.size) }
    }

    fun ranks(video: Video): List<Int> = ranks(video.videoTitle, video.resolution)

    /** Vrai lorsque le flux satisfait au moins un critère choisi (lecture automatique Aniyomi). */
    fun isPreferred(title: String, resolution: Int?): Boolean =
        ranks(title, resolution).first() < FrSettings.streamOrder.size

    /** Comparaison lexicographique de deux vecteurs de positions (le plus court est complété). */
    fun compareRanks(a: List<Int>, b: List<Int>): Int {
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { Int.MAX_VALUE }
            val y = b.getOrElse(index) { Int.MAX_VALUE }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun videoComparator(): Comparator<Video> {
        val order = FrSettings.streamOrder
        return Comparator<Video> { a, b ->
            compareRanks(ranks(a.videoTitle, a.resolution, order), ranks(b.videoTitle, b.resolution, order))
        }
            .thenByDescending { it.preferred }
            .thenByDescending { it.resolution ?: 0 }
            .thenBy { it.videoTitle.lowercase() }
    }

    /** Meilleur vecteur d'un serveur : ses flux connus, sinon les langues annoncées par son nom. */
    fun ranks(hoster: Hoster, order: List<String> = FrSettings.streamOrder): List<Int> {
        val videos = hoster.videoList.orEmpty()
        if (videos.isNotEmpty()) {
            return videos.map { ranks(it.videoTitle, it.resolution, order) }
                .minWithOrNull(::compareRanks) ?: listOf(order.size)
        }
        val fromName = StreamLabel.hosterLanguages(hoster.hosterName)
            .map(order::indexOf)
            .filter { it >= 0 }
            .sorted()
        return fromName.ifEmpty { listOf(order.size) }
    }

    fun hosterComparator(stremioFirst: Boolean): Comparator<Hoster> {
        val order = FrSettings.streamOrder
        return compareBy<Hoster> { hoster ->
            val stremio = hoster.hosterName.startsWith(StreamLabel.ENGINE_STREMIO + StreamLabel.SEPARATOR)
            if (stremioFirst == stremio) 0 else 1
        }
            .thenComparator { a, b -> compareRanks(ranks(a, order), ranks(b, order)) }
            .thenBy { it.hosterName.lowercase() }
    }

    /**
     * Trie les flux selon [videoComparator] puis ne marque **préféré** que le premier
     * qui satisfait un critère : Aniyomi lance alors exactement le flux classé n° 1,
     * et non un flux « préféré » pris au hasard parmi plusieurs.
     */
    fun sorted(videos: List<Video>): List<Video> {
        if (videos.isEmpty()) return videos
        val ordered = videos.sortedWith(videoComparator())
        val winnerIndex = ordered.indexOfFirst { isPreferred(it.videoTitle, it.resolution) }
        return ordered.mapIndexed { index, video ->
            val preferred = index == winnerIndex
            if (video.preferred == preferred) video else video.copy(preferred = preferred)
        }
    }
}

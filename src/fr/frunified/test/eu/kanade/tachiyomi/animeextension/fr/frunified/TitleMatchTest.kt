package eu.kanade.tachiyomi.animeextension.fr.frunified

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatchTest {
    @Test
    fun matchesWistoriaAcrossFrenchAndJapaneseVariants() {
        val anilistTitles = listOf(
            "Tsue to Tsurugi no Wistoria",
            "Wistoria: Wand and Sword",
            "杖と剣のウィストリア",
        )
        val tmdbFrenchTitle = "Wistoria : Wand and Sword"
        val tmdbOriginalTitle = "杖と剣のウィストリア"

        val scoreFrench = TitleMatch.score(anilistTitles, tmdbFrenchTitle, 2024, 2024)
        assertTrue("Wistoria French score too low: $scoreFrench", scoreFrench >= 0.80)

        val scoreOriginal = TitleMatch.score(anilistTitles, tmdbOriginalTitle, 2024, 2024)
        assertTrue("Wistoria native score too low: $scoreOriginal", scoreOriginal >= 0.80)
    }

    @Test
    fun matchesOnePieceAccurately() {
        val titles = listOf("One Piece", "ワンピース")
        val score = TitleMatch.score(titles, "One Piece", 1999, 1999)
        assertTrue("One Piece score too low: $score", score >= 0.90)
    }

    @Test
    fun resolvesEpisodeTargetsForSingleSeasonAnime() = runBlocking {
        val payload = PlayPayload(
            kind = "anime",
            titles = listOf("Wistoria: Wand and Sword"),
            year = 2024,
            season = 1,
            episode = 12,
            absoluteEpisode = 12,
            tmdbId = 245923,
        )
        val (tmdbTarget, absTarget) = NuvioClient.resolveEpisodeTargets(245923, 1, 12, payload)
        assertEquals(1 to 12, tmdbTarget)
        assertEquals(1 to 12, absTarget)
    }

    @Test
    fun resolvesEpisodeTargetsForLongRunningAnimeFromAbsoluteNumber() = runBlocking {
        val payload = PlayPayload(
            kind = "anime",
            titles = listOf("One Piece"),
            year = 1999,
            season = 1,
            episode = 1120,
            absoluteEpisode = 1120,
            tmdbId = 37854,
        )
        val (tmdbTarget, absTarget) = NuvioClient.resolveEpisodeTargets(37854, 1, 1120, payload)
        assertEquals(1 to 1120, absTarget)
        assertTrue(tmdbTarget.first >= 1 && tmdbTarget.second >= 1)
    }
}

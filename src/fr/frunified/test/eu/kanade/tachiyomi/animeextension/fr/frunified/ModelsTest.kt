package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun stremioPlaybackPayloadRoundTripsWithoutLosingItsOrigin() {
        val expected = PlayPayload(
            kind = "anime",
            titles = listOf("One Piece", "ワンピース"),
            year = 1999,
            season = 22,
            episode = 33,
            absoluteEpisode = 1168,
            tmdbId = 37854,
            imdbId = "tt0388629",
            anilistId = 21,
            malId = 21,
            stremioType = "series",
            stremioId = "tt0388629:22:33",
            stremioAddon = "https://tmdb.elfhosted.com/fr-FR",
            stremioMetaId = "tmdb:37854",
        )

        assertEquals(expected, PlayPayload.parse(expected.serialize()))
    }

    @Test
    fun catalogIdRoundTripsSeasonZero() {
        val expected = CatalogId("stremio", "series", "opaque-reference", season = 0)
        assertEquals(expected, CatalogId.parse(expected.serialize()))
    }
}

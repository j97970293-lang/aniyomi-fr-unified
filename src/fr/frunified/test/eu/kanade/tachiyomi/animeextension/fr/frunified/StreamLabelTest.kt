package eu.kanade.tachiyomi.animeextension.fr.frunified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamLabelTest {
    @Test
    fun rendersLanguageQualitySourceEngineInThatOrder() {
        val label = StreamLabel("VF", 1080, "flemmix", StreamLabel.ENGINE_NUVIO, "Uqload")
        assertEquals("(VF) 1080p · flemmix · Nuvio · Uqload", label.render())
        assertEquals(label, StreamLabel.parse(label.render()))

        assertEquals("(VOSTFR) 4K · anime-sama · Nuvio", StreamLabel("VOSTFR", 2160, "anime-sama", "Nuvio").render())
        assertEquals("720p · Frenchstream · Stremio", StreamLabel(null, 720, "Frenchstream", "Stremio").render())
        assertEquals(
            "Test Streams · Stremio · Torrent",
            StreamLabel(null, null, "Test Streams", "Stremio", "Torrent").render(),
        )
    }

    @Test
    fun parseRecognisesOnlyTitlesProducedByRender() {
        assertEquals(
            StreamLabel(null, 720, "Frenchstream", "Stremio"),
            StreamLabel.parse("720p · Frenchstream · Stremio"),
        )
        assertEquals(
            StreamLabel("VF", null, "x", "Nuvio", "a · b"),
            StreamLabel.parse("(VF) · x · Nuvio · a · b"),
        )
        assertNull(StreamLabel.parse("Stremio · addon • label"))
        assertNull(StreamLabel.parse("Frenchstream • [VF] UQLOAD • 1080p"))
        assertNull(StreamLabel.parse("just a title"))
    }

    @Test
    fun detectsLanguagesAndQualitiesInFreeText() {
        assertEquals("VF", StreamLabel.languageOf("[VF] UQLOAD"))
        assertEquals("VF", StreamLabel.languageOf("Film.TRUEFRENCH.1080p"))
        assertEquals("VF", StreamLabel.languageOf("Film FRENCH 720p"))
        assertEquals("VFQ", StreamLabel.languageOf("VFQ 720p"))
        assertEquals("VOSTFR", StreamLabel.languageOf("one piece vostfr"))
        assertEquals("MULTI", StreamLabel.languageOf("Multi 4K HDR"))
        assertEquals("VO", StreamLabel.languageOf("Dune VO 1080p"))
        assertNull(StreamLabel.languageOf("Türkçe 1080p"))
        assertNull(StreamLabel.languageOf("Voici un film"))

        assertEquals(1080, StreamLabel.qualityOf("Full HD"))
        assertEquals(720, StreamLabel.qualityOf("VF HD"))
        assertEquals(720, StreamLabel.qualityOf("HDLight 720p"))
        assertEquals(2160, StreamLabel.qualityOf("4K HDR"))
        assertEquals(480, StreamLabel.qualityOf("SD"))
        assertNull(StreamLabel.qualityOf("uqload"))

        assertEquals("VF", StreamLabel.languageFromCode("fr"))
        assertEquals("EN", StreamLabel.languageFromCode("en-US"))
        assertNull(StreamLabel.languageFromCode("something"))
        assertEquals("TR", StreamLabel.languageFromProvider(listOf("tr")))
        assertNull("Un site français sert VF et VOSTFR", StreamLabel.languageFromProvider(listOf("fr")))
        assertNull(StreamLabel.languageFromProvider(listOf("fr", "en")))
    }

    @Test
    fun detailDropsWhatIsAlreadyDisplayed() {
        assertEquals("UQLOAD", StreamLabel.detail(listOf("Frenchstream", "[VF] UQLOAD"), "Frenchstream"))
        assertNull(StreamLabel.detail(listOf("flemmix", "VF 1080p"), "flemmix"))
        assertEquals("Torrent", StreamLabel.detail(listOf("VF 1080p"), "x", prefix = "Torrent"))
        assertEquals(
            "WEB x264-GRP",
            StreamLabel.detail(listOf("One Piece S01E01 MULTI 1080p WEB x264-GRP"), "src", noise = listOf("One Piece")),
        )
        assertEquals(48, StreamLabel.detail(listOf("a".repeat(80)), "x")?.length)
    }

    @Test
    fun hosterNameListsLanguagesInPreferenceOrder() {
        val titles = listOf(
            "(VOSTFR) 720p · flemmix · Nuvio",
            "(VF) 1080p · flemmix · Nuvio",
            "(VF) 720p · flemmix · Nuvio",
        )
        assertEquals("Nuvio · flemmix : VF, VOSTFR", StreamLabel.hosterName("Nuvio", "flemmix", titles))
        assertEquals("Stremio · Test Streams", StreamLabel.hosterName("Stremio", "Test Streams", emptyList()))
        assertEquals(
            "Stremio · French Streaming Providers",
            StreamLabel.hosterName(
                "Stremio",
                "French Streaming Providers",
                listOf("French Streaming Providers · Stremio · Addon Lecteur Dood"),
            ),
        )
        assertEquals(listOf("VF", "VOSTFR"), StreamLabel.hosterLanguages("Nuvio · flemmix : VF, VOSTFR"))
        assertEquals(emptyList<String>(), StreamLabel.hosterLanguages("Stremio · Test Streams"))
    }
    @Test
    fun customAndUltraHighResolutionsAreRecognized() {
        assertEquals(540, StreamLabel.qualityValue("540p"))
        assertEquals(4320, StreamLabel.qualityValue("8K"))
        assertEquals(540, StreamLabel.qualityOf("WEB 540p VF"))
        assertEquals("8K", StreamLabel.qualityText(4320))
    }

}

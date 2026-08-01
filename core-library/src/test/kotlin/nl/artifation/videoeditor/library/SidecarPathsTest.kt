package nl.artifation.videoeditor.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SidecarPathsTest {

    @Test
    fun `elke analyse krijgt een vast bestand in de map van het asset`() {
        assertEquals(".cache/abc/transcript.json", SidecarPaths.of("abc", AnalysisKind.TRANSCRIPT))
        assertEquals(".cache/abc/silences.json", SidecarPaths.of("abc", AnalysisKind.SILENCES))
        assertEquals(".cache/abc/scenes.json", SidecarPaths.of("abc", AnalysisKind.SCENES))
        assertEquals(".cache/abc/subjects.json", SidecarPaths.of("abc", AnalysisKind.SUBJECTS))
    }

    @Test
    fun `maskvideo's zijn genummerd per onderwerp`() {
        assertEquals(".cache/abc/masks_0.mp4", SidecarPaths.mask("abc", 0))
        assertEquals(".cache/abc/masks_3.mp4", SidecarPaths.mask("abc", 3))
    }

    @Test
    fun `alle analysepaden staan in dezelfde map`() {
        val paden = SidecarPaths.all("abc")

        assertEquals(AnalysisKind.entries.size, paden.size, "gevonden: $paden")
        assertEquals(setOf(".cache/abc"), paden.values.map { it.substringBeforeLast('/') }.toSet())
    }

    @Test
    fun `paden zijn terug te lezen naar asset en analyse`() {
        val pad = SidecarPaths.of("abc", AnalysisKind.SCENES)

        assertEquals("abc", SidecarPaths.assetIdOf(pad))
        assertEquals(AnalysisKind.SCENES, SidecarPaths.kindOf(pad))
        assertNull(SidecarPaths.maskIndexOf(pad), "een json is geen maskvideo")
    }

    @Test
    fun `een maskpad is terug te lezen naar zijn index`() {
        assertEquals(2, SidecarPaths.maskIndexOf(SidecarPaths.mask("abc", 2)))
        assertNull(SidecarPaths.kindOf(SidecarPaths.mask("abc", 2)), "een maskvideo heeft geen eigen soort")
    }

    @Test
    fun `paden buiten de cache horen bij geen enkel asset`() {
        assertNull(SidecarPaths.assetIdOf("content://media/1"))
        assertNull(SidecarPaths.assetIdOf(".cache/abc/diep/transcript.json"))
        assertNull(SidecarPaths.assetIdOf("cache/abc/transcript.json"))
    }

    @Test
    fun `een onbekende bestandsnaam levert geen analyse op`() {
        assertNull(SidecarPaths.kindOf(".cache/abc/waypoints.json"))
        assertNull(SidecarPaths.maskIndexOf(".cache/abc/masks_x.mp4"))
    }

    @Test
    fun `een id met padtekens wordt geweigerd`() {
        // Anders schrijft een gemanipuleerd id buiten de cachemap.
        assertFailsWith<IllegalArgumentException> { SidecarPaths.directory("../etc") }
        assertFailsWith<IllegalArgumentException> { SidecarPaths.of("a/b", AnalysisKind.SCENES) }
        assertFailsWith<IllegalArgumentException> { SidecarPaths.directory("  ") }
    }

    @Test
    fun `een negatieve mask-index wordt geweigerd`() {
        assertFailsWith<IllegalArgumentException> { SidecarPaths.mask("abc", -1) }
    }
}

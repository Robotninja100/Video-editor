package nl.artifation.videoeditor.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Histogram met alle massa rond één bin — staat voor "een shot dat er zo uitziet". */
private fun look(peakBin: Int, bins: Int = 32, noise: Float = 0.01f): FloatArray =
    FloatArray(bins) { if (it == peakBin) 1f else noise }

private fun frames(vararg looks: Int, stepUs: Long = 500_000L) =
    looks.mapIndexed { index, peak -> FrameHistogram(index * stepUs, look(peak)) }

class SceneDistanceTest {

    @Test
    fun `identieke histogrammen hebben afstand nul`() {
        assertEquals(0f, SceneDetector.distance(look(4), look(4)), 1e-5f)
    }

    @Test
    fun `disjuncte histogrammen liggen ver uit elkaar`() {
        val distance = SceneDetector.distance(
            FloatArray(4) { if (it == 0) 1f else 0f },
            FloatArray(4) { if (it == 3) 1f else 0f },
        )
        assertEquals(1f, distance, 1e-5f)
    }

    @Test
    fun `schaal doet er niet toe, alleen de verdeling`() {
        val single = floatArrayOf(1f, 2f, 3f)
        val doubled = floatArrayOf(2f, 4f, 6f)
        assertEquals(0f, SceneDetector.distance(single, doubled), 1e-5f)
    }
}

class SceneDetectTest {

    @Test
    fun `een harde cut wordt gevonden`() {
        // Drie frames van shot A, dan drie van shot B.
        val cuts = SceneDetector.detect(frames(2, 2, 2, 20, 20, 20))

        assertEquals(1, cuts.size, "gevonden: $cuts")
        assertEquals(1_500_000L, cuts.single().atUs, "de cut ligt op het eerste frame van shot B")
    }

    @Test
    fun `materiaal zonder cut levert niets op`() {
        assertEquals(emptyList(), SceneDetector.detect(frames(5, 5, 5, 5, 5)))
    }

    @Test
    fun `meerdere cuts worden allemaal gevonden`() {
        val cuts = SceneDetector.detect(frames(1, 1, 15, 15, 30, 30))
        assertEquals(2, cuts.size, "gevonden: $cuts")
    }

    @Test
    fun `te korte shots worden niet apart geteld`() {
        val cuts = SceneDetector.detect(
            frames(1, 20, 1, 20, stepUs = 100_000L),
            SceneConfig(minShotDurationUs = 400_000L),
        )
        assertTrue(cuts.size <= 1, "flikkering hoort niet elke keer een cut te zijn: $cuts")
    }

    @Test
    fun `te weinig frames levert niets op`() {
        assertEquals(emptyList(), SceneDetector.detect(emptyList()))
        assertEquals(emptyList(), SceneDetector.detect(frames(1)))
    }

    @Test
    fun `de score wordt meegegeven om drempels te kunnen tunen`() {
        val cut = SceneDetector.detect(frames(2, 2, 2, 20, 20, 20)).single()
        assertTrue(cut.score > 0.35f, "score was ${cut.score}")
    }

    @Test
    fun `volgorde van de frames maakt niet uit`() {
        val shuffled = frames(2, 2, 2, 20, 20, 20).reversed()
        assertEquals(1, SceneDetector.detect(shuffled).size)
    }
}

/**
 * De adaptieve drempel gaat uit van cuts als minderheid. Bij een snelle montage
 * of ver uit elkaar bemonsterde frames klopt die aanname niet, en tilde de
 * mediaan de drempel boven de hoogst haalbare afstand uit — waarna er nul cuts
 * uitkwamen, hoe overduidelijk ze ook waren.
 */
class SceneDichteMontageTest {

    @Test
    fun `afwisselende shots leveren wel cuts op`() {
        val cuts = SceneDetector.detect(frames(1, 20, 1))

        assertEquals(2, cuts.size, "beide cuts hoorden gevonden te worden: $cuts")
    }

    @Test
    fun `materiaal waarin elk frame een ander shot is levert overal cuts op`() {
        val cuts = SceneDetector.detect(frames(1, 8, 15, 22, 29))

        assertEquals(4, cuts.size, "gevonden: $cuts")
    }

    @Test
    fun `twee totaal verschillende frames leveren een cut op`() {
        assertEquals(1, SceneDetector.detect(frames(1, 25)).size)
    }

    @Test
    fun `rustig materiaal levert nog steeds niets op`() {
        assertEquals(
            emptyList(),
            SceneDetector.detect(frames(5, 5, 5, 5)),
            "de vaste ondergrens moet materiaal zonder cuts blijven beschermen",
        )
    }
}

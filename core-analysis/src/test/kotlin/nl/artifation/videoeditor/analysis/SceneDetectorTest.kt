package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.US_PER_MS
import nl.artifation.videoeditor.model.Us
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BINS = 8

/** Histogram met alle massa in één bin — het simpelste "totaal ander beeld". */
private fun peaked(atUs: Us, bin: Int) =
    FrameSignature(atUs, List(BINS) { if (it == bin) 1f else 0f })

private fun frames(vararg bins: Int, stepMs: Int = 200) =
    bins.mapIndexed { index, bin -> peaked(index.toLong() * stepMs * US_PER_MS, bin) }

class SceneDetectorTest {

    @Test
    fun `een stilstaand beeld levert geen cuts op`() {
        assertEquals(emptyList(), SceneDetector.detect(frames(3, 3, 3, 3, 3)))
    }

    @Test
    fun `een volledig ander beeld is een cut`() {
        val cuts = SceneDetector.detect(frames(1, 1, 1, 6, 6, 6))

        assertEquals(listOf(600L * US_PER_MS), cuts, "de cut hoort bij het eerste frame van de nieuwe scene")
    }

    @Test
    fun `beweging binnen het beeld is geen cut`() {
        // Massa die langzaam van bin 2 naar bin 3 schuift: elk paar verschilt maar 10 %.
        val gradual = (0..9).map { step ->
            val shift = step * 0.1f
            FrameSignature(
                atUs = step.toLong() * 200 * US_PER_MS,
                histogram = List(BINS) {
                    when (it) {
                        2 -> 1f - shift
                        3 -> shift
                        else -> 0f
                    }
                },
            )
        }

        assertEquals(emptyList(), SceneDetector.detect(gradual))
    }

    @Test
    fun `een tweede cut te snel na de eerste wordt genegeerd`() {
        // Een flits: één frame wit, dan weer terug. Dat is geen nieuwe scene.
        val cuts = SceneDetector.detect(frames(1, 1, 1, 7, 1, 1, 1), SceneConfig(minSceneMs = 400))

        assertEquals(1, cuts.size, "de terugkeer valt binnen de minimale scenelengte")
        assertEquals(600L * US_PER_MS, cuts.single())
    }

    @Test
    fun `de drempel bepaalt de gevoeligheid`() {
        val halfChanged = listOf(
            FrameSignature(0L, listOf(1f, 0f)),
            FrameSignature(500L * US_PER_MS, listOf(0.5f, 0.5f)),
        )

        assertEquals(0.5f, SceneDetector.distance(halfChanged[0], halfChanged[1]), 1e-6f)
        assertEquals(emptyList(), SceneDetector.detect(halfChanged, SceneConfig(threshold = 0.6f)))
        assertEquals(1, SceneDetector.detect(halfChanged, SceneConfig(threshold = 0.4f)).size)
    }

    @Test
    fun `minder dan twee frames levert niets op`() {
        assertEquals(emptyList(), SceneDetector.detect(emptyList()))
        assertEquals(emptyList(), SceneDetector.detect(frames(1)))
    }

    @Test
    fun `frames moeten oplopen in tijd`() {
        val reversed = listOf(peaked(1_000L, 1), peaked(0L, 2))

        assertFailsWith<IllegalArgumentException> { SceneDetector.detect(reversed) }
    }
}

class FrameSignatureTest {

    @Test
    fun `een egaal vlak valt in één bin`() {
        val gray = ByteArray(100) { 128.toByte() }
        val signature = FrameSignature.fromLuma(0L, gray, bins = 4)

        assertEquals(listOf(0f, 0f, 1f, 0f), signature.histogram)
    }

    @Test
    fun `zwart en wit hebben geen enkele overlap`() {
        val black = FrameSignature.fromLuma(0L, ByteArray(100) { 0 })
        val white = FrameSignature.fromLuma(0L, ByteArray(100) { 255.toByte() })

        assertEquals(1f, SceneDetector.distance(black, white), 1e-6f)
    }

    @Test
    fun `het histogram is genormaliseerd`() {
        val mixed = ByteArray(256) { it.toByte() }
        val signature = FrameSignature.fromLuma(0L, mixed)

        assertEquals(1f, signature.histogram.sum(), 1e-5f)
        assertTrue(signature.histogram.all { it > 0f }, "een volledige grijstrap vult elke bin")
    }

    @Test
    fun `een leeg histogram wordt geweigerd`() {
        assertFailsWith<IllegalArgumentException> { FrameSignature(0L, emptyList()) }
    }
}

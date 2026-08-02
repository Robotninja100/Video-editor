package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.NormRect
import nl.artifation.videoeditor.model.OutputSpec
import nl.artifation.videoeditor.model.US_PER_MS
import nl.artifation.videoeditor.model.Us
import nl.artifation.videoeditor.model.interpolateAt
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val LANDSCAPE = 16f / 9f
private const val PORTRAIT = 9f / 16f

/** 5 detecties per seconde, zoals de reframe-sampling uit het bouwplan. */
private fun samples(vararg centerX: Float, centerY: Float = 0.5f) =
    centerX.mapIndexed { index, x ->
        SubjectSample(atUs = index.toLong() * 200 * US_PER_MS, centerX = x, centerY = centerY)
    }

private fun List<SubjectSample>.smoothed(
    sceneCutsUs: List<Us> = emptyList(),
    config: ReframeConfig = ReframeConfig(decimationTolerance = 0f),
) = PathSmoother.smooth(this, PORTRAIT, LANDSCAPE, sceneCutsUs, config)

class CropSizeTest {

    @Test
    fun `9 op 16 uit 16 op 9 is een smalle strook`() {
        val (width, height) = PathSmoother.cropSize(PORTRAIT, LANDSCAPE)

        assertEquals(0.31640625f, width, 1e-6f)
        assertEquals(1f, height)
    }

    @Test
    fun `een bredere uitvoer dan de bron snijdt van boven en onder`() {
        val (width, height) = PathSmoother.cropSize(outputAspect = 2f, sourceAspect = 1f)

        assertEquals(1f, width)
        assertEquals(0.5f, height)
    }

    @Test
    fun `gelijke verhoudingen gebruiken het hele frame`() {
        val (width, height) = PathSmoother.cropSize(LANDSCAPE, LANDSCAPE)

        assertEquals(1f, width)
        assertEquals(1f, height)
    }

    @Test
    fun `verhoudingen moeten positief zijn`() {
        assertFailsWith<IllegalArgumentException> { PathSmoother.cropSize(0f, 1f) }
    }
}

class SmoothingTest {

    @Test
    fun `de crop schiet niet door zijn doel heen`() {
        val path = samples(0.3f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f, 0.7f).smoothed()
        val centers = path.map { it.value.centerX }

        assertTrue(
            centers.zipWithNext().all { (a, b) -> b >= a - 1e-6f },
            "de beweging hoort monotoon te zijn, was $centers",
        )
        assertTrue(
            centers.all { it <= 0.7f + 1e-6f },
            "kritisch gedempt betekent: nooit voorbij het doel, was $centers",
        )
    }

    @Test
    fun `de crop haalt zijn doel uiteindelijk in`() {
        val path = List(20) { index ->
            SubjectSample(
                atUs = index.toLong() * 200 * US_PER_MS,
                centerX = if (index == 0) 0.3f else 0.7f,
                centerY = 0.5f,
            )
        }.smoothed()

        assertEquals(0.7f, path.last().value.centerX, 1e-3f)
    }

    @Test
    fun `jitter binnen de deadzone laat de crop stilstaan`() {
        val jitter = samples(0.5f, 0.505f, 0.495f, 0.51f, 0.49f, 0.5f)
            .smoothed(config = ReframeConfig(deadzoneFrac = 0.02f, decimationTolerance = 0f))

        assertEquals(
            1, jitter.map { it.value }.distinct().size,
            "een pratend hoofd hoort de crop niet te laten ademen",
        )
    }

    @Test
    fun `beweging voorbij de deadzone wordt wel gevolgd`() {
        val moved = samples(0.5f, 0.5f, 0.6f, 0.6f, 0.6f, 0.6f)
            .smoothed(config = ReframeConfig(deadzoneFrac = 0.02f, decimationTolerance = 0f))

        assertTrue(moved.last().value.centerX > 0.55f, "0,1 is ruim buiten de deadzone")
    }

    @Test
    fun `op een scenegrens springt de crop hard`() {
        val cutUs = 400L * US_PER_MS
        val path = samples(0.3f, 0.3f, 0.7f, 0.7f).smoothed(sceneCutsUs = listOf(cutUs))

        val atCut = path.single { it.atUs == cutUs }
        assertEquals(0.7f, atCut.value.centerX, 1e-6f, "over een cut heen glijden ziet er verkeerd uit")
    }

    @Test
    fun `zonder scenegrens glijdt de crop wel`() {
        val path = samples(0.3f, 0.3f, 0.7f, 0.7f).smoothed()
        val atSameMoment = path.single { it.atUs == 400L * US_PER_MS }

        assertTrue(atSameMoment.value.centerX < 0.6f, "zonder cut hoort de crop achter te lopen")
    }

    @Test
    fun `de crop blijft binnen het frame`() {
        val path = samples(0.02f, 0.5f, 0.98f, 0.98f, 0.98f, 0.98f).smoothed()

        assertTrue(path.all { it.value.left >= -1e-6f }, "links buiten beeld")
        assertTrue(path.all { it.value.right <= 1f + 1e-6f }, "rechts buiten beeld")
        assertTrue(path.all { it.value.top >= -1e-6f && it.value.bottom <= 1f + 1e-6f })
    }

    @Test
    fun `de vensterafmeting verandert nooit`() {
        val path = samples(0.2f, 0.8f, 0.4f, 0.6f, 0.5f).smoothed()
        val widths = path.map { it.value.width }

        assertTrue(
            widths.all { abs(it - 0.31640625f) < 1e-6f },
            "auto-reframe verschuift, het zoomt niet — was $widths",
        )
    }

    @Test
    fun `zonder detecties is er geen pad`() {
        assertEquals(emptyList(), PathSmoother.smooth(emptyList(), PORTRAIT, LANDSCAPE))
    }

    @Test
    fun `samples moeten oplopen in tijd`() {
        val reversed = listOf(
            SubjectSample(1_000L, 0.5f, 0.5f),
            SubjectSample(0L, 0.5f, 0.5f),
        )

        assertFailsWith<IllegalArgumentException> {
            PathSmoother.smooth(reversed, PORTRAIT, LANDSCAPE)
        }
    }

    @Test
    fun `de outputspec-overload rekent dezelfde verhouding uit`() {
        val input = samples(0.3f, 0.7f, 0.7f)
        val viaAspect = input.smoothed()
        val viaSpec = PathSmoother.smooth(
            samples = input,
            outputSpec = OutputSpec(width = 1080, height = 1920),
            sourceWidth = 1920,
            sourceHeight = 1080,
            config = ReframeConfig(decimationTolerance = 0f),
        )

        assertEquals(viaAspect, viaSpec)
    }
}

class DecimationTest {

    private val moving = List(60) { index ->
        SubjectSample(
            atUs = index.toLong() * 200 * US_PER_MS,
            centerX = 0.3f + 0.4f * (index / 59f),
            centerY = 0.5f,
        )
    }

    /**
     * Zonder deadzone, want die is hier niet wat we meten. Het onderwerp beweegt in
     * dit pad ruim 0,006 per stap, oftewel binnen de standaard deadzone van 0,02:
     * die zou de beweging in trapjes hakken en juist méér keyframes opleveren. Dat
     * is correct gedrag, maar het maskeert wat het uitdunnen zelf doet.
     */
    private fun path(tolerance: Float) = PathSmoother.smooth(
        moving, PORTRAIT, LANDSCAPE,
        config = ReframeConfig(deadzoneFrac = 0f, decimationTolerance = tolerance),
    )

    @Test
    fun `een stilstaand pad houdt alleen begin en eind over`() {
        val still = List(30) { SubjectSample(it.toLong() * 200 * US_PER_MS, 0.5f, 0.5f) }
        val path = PathSmoother.smooth(still, PORTRAIT, LANDSCAPE)

        assertEquals(2, path.size)
    }

    @Test
    fun `uitdunnen scheelt fors op een rustig pad`() {
        val dense = path(tolerance = 0f)
        val sparse = path(tolerance = 0.002f)

        assertEquals(60, dense.size)
        assertTrue(sparse.size < dense.size / 2, "hield ${sparse.size} van de ${dense.size} keyframes over")
    }

    @Test
    fun `de deadzone houdt de crop stil in plaats van te decimeren`() {
        // Beweging binnen de deadzone geeft een trapje in plaats van een vloeiende
        // lijn. Dat kost keyframes, en dat is de bedoeling: stilstaan is belangrijker.
        val withDeadzone = PathSmoother.smooth(moving, PORTRAIT, LANDSCAPE)

        assertTrue(
            withDeadzone.size > path(tolerance = 0.002f).size,
            "een trapjespad is nu eenmaal minder goed uit te dunnen",
        )
    }

    @Test
    fun `het uitgedunde pad blijft binnen de tolerantie`() {
        val tolerance = 0.002f
        val dense = path(tolerance = 0f)
        val sparse = path(tolerance = tolerance)

        val largestError = dense.maxOf { keyframe ->
            val approximated = sparse.interpolateAt(keyframe.atUs) ?: NormRect.FULL
            abs(approximated.centerX - keyframe.value.centerX)
        }

        assertTrue(largestError <= tolerance + 1e-6f, "grootste afwijking was $largestError")
    }

    @Test
    fun `begin en eind blijven altijd staan`() {
        val sparse = PathSmoother.smooth(moving, PORTRAIT, LANDSCAPE)

        assertEquals(moving.first().atUs, sparse.first().atUs)
        assertEquals(moving.last().atUs, sparse.last().atUs)
    }
}

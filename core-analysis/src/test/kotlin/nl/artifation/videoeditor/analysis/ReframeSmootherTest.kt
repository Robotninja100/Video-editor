package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.NormRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SOURCE_16_9 = 16f / 9f

private fun box(centerX: Float, centerY: Float = 0.5f, size: Float = 0.2f) = NormRect(
    left = centerX - size / 2f,
    top = centerY - size / 2f,
    right = centerX + size / 2f,
    bottom = centerY + size / 2f,
)

/** Detecties op vaste intervallen, met het onderwerp op de opgegeven x-posities. */
private fun track(vararg centersX: Float, stepUs: Long = 200_000L) =
    centersX.mapIndexed { index, x -> Detection(index * stepUs, listOf(box(x))) }

class ReframeCropSizeTest {

    @Test
    fun `9 op 16 uit 16 op 9 is een smalle strook over de volle hoogte`() {
        val path = ReframeSmoother.solve(track(0.5f), sourceAspect = SOURCE_16_9)
        val rect = path.single().value

        assertEquals(1f, rect.height, 1e-4f, "volle hoogte")
        assertEquals((9f / 16f) / SOURCE_16_9, rect.width, 1e-4f)
    }

    @Test
    fun `bredere doelverhouding dan de bron beperkt de hoogte`() {
        val path = ReframeSmoother.solve(
            track(0.5f),
            sourceAspect = 1f,
            config = ReframeConfig(targetAspect = 16f / 9f),
        )
        val rect = path.single().value

        assertEquals(1f, rect.width, 1e-4f)
        assertEquals(1f / (16f / 9f), rect.height, 1e-4f)
    }
}

class ReframeSmoothingTest {

    @Test
    fun `de crop loopt het onderwerp achterna en haalt het in`() {
        // Onderwerp springt naar rechts en blijft daar staan.
        val path = ReframeSmoother.solve(
            track(0.5f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f),
            sourceAspect = SOURCE_16_9,
        )

        val first = path.first().value.centerX
        val last = path.last().value.centerX
        assertTrue(last > first, "crop is meegelopen: $first -> $last")
        assertTrue(abs(last - 0.8f) < 0.05f, "crop heeft het doel bijna bereikt: $last")
    }

    @Test
    fun `critically damped betekent geen overshoot`() {
        val path = ReframeSmoother.solve(
            track(0.5f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f),
            sourceAspect = SOURCE_16_9,
        )

        val overshoot = path.map { it.value.centerX }.filter { it > 0.8f + 1e-3f }
        assertTrue(overshoot.isEmpty(), "crop schoot voorbij het doel: $overshoot")
    }

    @Test
    fun `de crop beweegt monotoon naar het doel`() {
        val centers = ReframeSmoother.solve(
            track(0.5f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f),
            sourceAspect = SOURCE_16_9,
        ).map { it.value.centerX }

        centers.zipWithNext { a, b ->
            assertTrue(b >= a - 1e-4f, "crop ging terug: $a -> $b in $centers")
        }
    }
}

class ReframeDeadzoneTest {

    @Test
    fun `kleine trillingen worden genegeerd`() {
        // Jitter van ±2% rond het midden, ruim binnen de deadzone van 5%.
        val jitter = ReframeSmoother.solve(
            track(0.50f, 0.52f, 0.48f, 0.51f, 0.49f, 0.52f),
            sourceAspect = SOURCE_16_9,
        )

        val spread = jitter.map { it.value.centerX }.let { it.max() - it.min() }
        assertTrue(spread < 1e-3f, "crop bewoog door jitter, spreiding was $spread")
    }

    @Test
    fun `beweging buiten de deadzone wordt wel gevolgd`() {
        val moved = ReframeSmoother.solve(
            track(0.5f, 0.5f, 0.9f, 0.9f, 0.9f, 0.9f),
            sourceAspect = SOURCE_16_9,
        )

        val spread = moved.map { it.value.centerX }.let { it.max() - it.min() }
        assertTrue(spread > 0.05f, "crop had moeten bewegen, spreiding was $spread")
    }
}

class ReframeSceneCutTest {

    @Test
    fun `op een shot-grens springt de crop meteen`() {
        val detections = track(0.2f, 0.2f, 0.8f, 0.8f)
        // Cut precies bij het derde sample, waar het onderwerp verspringt.
        val path = ReframeSmoother.solve(
            detections,
            sourceAspect = SOURCE_16_9,
            sceneCutsUs = listOf(400_000L),
        )

        val beforeCut = path[1].value.centerX
        val atCut = path[2].value.centerX
        assertTrue(abs(beforeCut - 0.2f) < 0.02f, "vóór de cut nog links: $beforeCut")
        assertTrue(abs(atCut - 0.8f) < 0.02f, "op de cut direct rechts: $atCut")
    }

    @Test
    fun `zonder shot-grens glijdt dezelfde beweging geleidelijk`() {
        val path = ReframeSmoother.solve(track(0.2f, 0.2f, 0.8f, 0.8f), sourceAspect = SOURCE_16_9)

        val atSameSample = path[2].value.centerX
        assertTrue(atSameSample < 0.5f, "zonder cut mag het niet springen: $atSameSample")
    }
}

class ReframeClampTest {

    @Test
    fun `de crop blijft binnen het frame`() {
        val path = ReframeSmoother.solve(
            track(0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f),
            sourceAspect = SOURCE_16_9,
        )

        for (keyframe in path) {
            val rect = keyframe.value
            assertTrue(rect.left >= -1e-4f, "buiten links: $rect")
            assertTrue(rect.right <= 1f + 1e-4f, "buiten rechts: $rect")
            assertTrue(rect.top >= -1e-4f, "buiten boven: $rect")
            assertTrue(rect.bottom <= 1f + 1e-4f, "buiten onder: $rect")
        }
    }
}

class ReframeDetectionTest {

    @Test
    fun `het grootste onderwerp weegt het zwaarst`() {
        val detections = listOf(
            Detection(0L, listOf(box(0.1f, size = 0.05f), box(0.9f, size = 0.4f))),
        )
        val center = ReframeSmoother.solve(detections, SOURCE_16_9).single().value.centerX

        assertTrue(center > 0.7f, "de grote box hoort te winnen, kreeg $center")
    }

    @Test
    fun `frames zonder detectie houden de vorige positie vast`() {
        val detections = listOf(
            Detection(0L, listOf(box(0.8f))),
            Detection(200_000L, emptyList()),
            Detection(400_000L, emptyList()),
        )
        val path = ReframeSmoother.solve(detections, SOURCE_16_9)

        val spread = path.map { it.value.centerX }.let { it.max() - it.min() }
        assertTrue(spread < 1e-3f, "crop dreef weg zonder detecties: $spread")
    }

    @Test
    fun `zonder detecties komt er geen pad`() {
        assertEquals(emptyList(), ReframeSmoother.solve(emptyList(), SOURCE_16_9))
    }
}

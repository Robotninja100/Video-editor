package nl.artifation.videoeditor.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun clip(id: String, durationUs: Us) =
    Clip(id = id, sourceUri = "file:///$id.mp4", inPointUs = 0L, outPointUs = durationUs)

/** 100 px per seconde: één seconde is 100 px, makkelijk te controleren. */
private fun geometry(pxPerSecond: Float = 100f, scrollPx: Float = 0f) =
    TimelineGeometry(pxPerSecond = pxPerSecond, scrollPx = scrollPx)

class TimeToPixelTest {

    @Test
    fun `tijd en pixels zijn elkaars omgekeerde`() {
        val g = geometry()

        assertEquals(100f, g.xAt(1_000_000L), 1e-3f)
        assertEquals(1_000_000L, g.timeAt(100f))
    }

    @Test
    fun `scrollen verschuift de tijdlijn`() {
        val g = geometry(scrollPx = 50f)

        assertEquals(50f, g.xAt(1_000_000L), 1e-3f)
        assertEquals(1_000_000L, g.timeAt(50f))
    }

    @Test
    fun `tijd wordt nooit negatief`() {
        assertEquals(0L, geometry().timeAt(-500f))
    }

    @Test
    fun `breedte schaalt met de duur`() {
        val g = geometry()
        assertEquals(250f, g.widthOf(2_500_000L), 1e-3f)
    }
}

class ZoomTest {

    @Test
    fun `het punt onder de vinger blijft staan bij inzoomen`() {
        val g = geometry(scrollPx = 200f)
        val anchorX = 300f
        val before = g.timeAt(anchorX)

        val zoomed = g.zoomedBy(2f, anchorX)

        assertTrue(
            abs(zoomed.timeAt(anchorX) - before) < 20_000L,
            "punt verschoof van $before naar ${zoomed.timeAt(anchorX)}",
        )
    }

    @Test
    fun `zoom blijft binnen de grenzen`() {
        val g = geometry()

        assertEquals(TimelineGeometry.MAX_PX_PER_SECOND, g.zoomedBy(1000f, 0f).pxPerSecond)
        assertEquals(TimelineGeometry.MIN_PX_PER_SECOND, g.zoomedBy(0.0001f, 0f).pxPerSecond)
    }

    @Test
    fun `scroll wordt nooit negatief`() {
        assertTrue(geometry().zoomedBy(0.1f, 500f).scrollPx >= 0f)
    }
}

class HitTestTest {

    private val sequences = listOf(
        Sequence("video", listOf(clip("a", 2_000_000L), Gap(1_000_000L), clip("b", 2_000_000L))),
        Sequence("audio", listOf(clip("c", 5_000_000L))),
    )

    @Test
    fun `een tik op een clip vindt de juiste clip`() {
        val g = geometry()
        val hit = g.hitTest(sequences, x = 50f, y = g.trackTop(0) + 10f)

        assertEquals("a", hit?.clipId)
        assertEquals(0, hit?.itemIndex)
        assertEquals(0L, hit?.startUs)
    }

    @Test
    fun `een tik voorbij een gat vindt de clip erna`() {
        val g = geometry()
        // Clip b begint op 3 s, dus x = 300.
        val hit = g.hitTest(sequences, x = 350f, y = g.trackTop(0) + 10f)

        assertEquals("b", hit?.clipId)
        assertEquals(3_000_000L, hit?.startUs)
    }

    @Test
    fun `een tik op een gat raakt niets`() {
        val g = geometry()
        assertNull(g.hitTest(sequences, x = 250f, y = g.trackTop(0) + 10f))
    }

    @Test
    fun `de tweede track wordt geraakt op zijn eigen hoogte`() {
        val g = geometry()
        val hit = g.hitTest(sequences, x = 100f, y = g.trackTop(1) + 10f)

        assertEquals("c", hit?.clipId)
        assertEquals(1, hit?.sequenceIndex)
    }

    @Test
    fun `de liniaal is geen track`() {
        assertNull(geometry().hitTest(sequences, x = 50f, y = 5f))
    }

    @Test
    fun `de ruimte tussen twee tracks raakt niets`() {
        val g = geometry()
        val between = g.trackTop(0) + g.trackHeightPx + 2f

        assertNull(g.hitTest(sequences, x = 50f, y = between))
    }

    @Test
    fun `buiten de tijdlijn raakt niets`() {
        val g = geometry()
        assertNull(g.hitTest(sequences, x = 5_000f, y = g.trackTop(0) + 10f))
        assertNull(g.hitTest(sequences, x = 50f, y = 10_000f))
    }
}

class SnapTest {

    @Test
    fun `zonder grenzen wordt er op het raster geklikt`() {
        val g = geometry(pxPerSecond = 100f)

        assertEquals(1_200_000L, g.snap(1_234_567L))
        assertEquals(1_200_000L, g.snap(1_180_000L))
    }

    @Test
    fun `een clipgrens wint van het raster`() {
        val g = geometry(pxPerSecond = 100f)
        val edges = listOf(1_234_567L)

        // 1_240_000 ligt binnen 12 px van de grens, dus de grens wint.
        assertEquals(1_234_567L, g.snap(1_240_000L, edges))
    }

    @Test
    fun `een verre clipgrens wint niet`() {
        val g = geometry(pxPerSecond = 100f)
        val edges = listOf(5_000_000L)

        assertEquals(1_200_000L, g.snap(1_234_567L, edges))
    }

    @Test
    fun `grenzen worden uit alle sequences verzameld`() {
        val sequences = listOf(
            Sequence("video", listOf(clip("a", 1_000_000L), clip("b", 2_000_000L))),
            Sequence("audio", listOf(clip("c", 1_500_000L))),
        )

        val edges = geometry().edgesOf(sequences)

        assertEquals(listOf(0L, 1_000_000L, 1_500_000L, 3_000_000L), edges)
    }
}

class RulerTest {

    @Test
    fun `streepjes staan bij elke zoom ongeveer even ver uit elkaar`() {
        for (pxPerSecond in listOf(5f, 20f, 100f, 400f, 800f)) {
            val g = geometry(pxPerSecond = pxPerSecond)
            val spacing = g.widthOf(g.chooseInterval())

            assertTrue(
                spacing in 60f..600f,
                "bij $pxPerSecond px/s staan streepjes ${spacing}px uit elkaar",
            )
        }
    }

    @Test
    fun `uitzoomen levert grovere intervallen op`() {
        val ingezoomd = geometry(pxPerSecond = 400f).chooseInterval()
        val uitgezoomd = geometry(pxPerSecond = 10f).chooseInterval()

        assertTrue(uitgezoomd > ingezoomd, "$uitgezoomd hoort grover te zijn dan $ingezoomd")
    }

    @Test
    fun `streepjes vullen het zichtbare gebied`() {
        val g = geometry(pxPerSecond = 100f)
        val ticks = g.ticks(visibleWidthPx = 500f)

        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { g.xAt(it.atUs) <= 501f }, "streepje buiten beeld")
        assertTrue(ticks.any { it.major }, "geen enkel hoofdstreepje")
    }

    @Test
    fun `een lege breedte levert geen streepjes op`() {
        assertEquals(emptyList(), geometry().ticks(0f))
    }
}

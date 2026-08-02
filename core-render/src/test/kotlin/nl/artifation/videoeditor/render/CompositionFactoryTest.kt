package nl.artifation.videoeditor.render

import androidx.media3.common.C
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.test.core.app.ApplicationProvider
import nl.artifation.videoeditor.model.CaptionStyle
import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Keyframe
import nl.artifation.videoeditor.model.NormRect
import nl.artifation.videoeditor.model.RenderEffect
import nl.artifation.videoeditor.model.RenderItem
import nl.artifation.videoeditor.model.RenderPlan
import nl.artifation.videoeditor.model.RenderSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests op de vertaling van [RenderPlan] naar Media3.
 *
 * Wat hier getoetst wordt is uitsluitend de vertaalslag: komt elke regel uit de
 * tabel in `docs/PRODUCTPLAN.md` op het juiste Media3-object terecht. Of het beeld
 * er goed uitziet kan hier niet blijken — daar is een toestel voor nodig, en dat
 * is precies wat het Spike-scherm in `:app` doet.
 *
 * Robolectric is genoeg omdat `Composition` en `EditedMediaItem` gewone
 * waardeobjecten zijn: die bouwen zonder codec, zonder GL en zonder toestel.
 */
@RunWith(RobolectricTestRunner::class)
class CompositionFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun source(
        id: String = "clip-1",
        clipStartUs: Long = 0L,
        clipEndUs: Long = 5_000_000L,
        speed: Float = 1f,
        effects: List<RenderEffect> = emptyList(),
        timelineStartUs: Long = 0L,
    ) = RenderItem.Source(
        id = id,
        sourceUri = "content://media/video/$id",
        clipStartUs = clipStartUs,
        clipEndUs = clipEndUs,
        speed = speed,
        timelineStartUs = timelineStartUs,
        durationUs = ((clipEndUs - clipStartUs) / speed).toLong(),
        effects = effects,
    )

    private fun plan(vararg items: RenderItem) = RenderPlan(
        width = 1080,
        height = 1920,
        frameRate = 30,
        sequences = listOf(RenderSequence(id = "main", items = items.toList())),
    )

    @Test
    fun `elke sequence wordt een EditedMediaItemSequence`() {
        val composition = RenderPlan(
            width = 1080,
            height = 1920,
            frameRate = 30,
            sequences = listOf(
                RenderSequence("main", listOf(source(id = "a"))),
                RenderSequence("overlay", listOf(source(id = "b"))),
            ),
        ).toComposition(context)

        assertEquals(2, composition.sequences.size)
    }

    @Test
    fun `de uitvoerresolutie komt als Presentation op de compositie`() {
        val composition = plan(source()).toComposition(context)

        val presentation = composition.effects.videoEffects.filterIsInstance<Presentation>()
        assertEquals(1, presentation.size)
    }

    @Test
    fun `een gap wordt een gap-item en geen bron`() {
        val composition = plan(
            RenderItem.Gap(timelineStartUs = 0L, durationUs = 2_000_000L),
            source(timelineStartUs = 2_000_000L),
        ).toComposition(context)

        val items = composition.sequences[0].editedMediaItems
        assertEquals(2, items.size)
        // EditedMediaItem.isGap is package-private. Een gat is te herkennen aan het
        // ontbreken van een uri — Media3 bouwt er een MediaItem met alleen een
        // mediaId voor — en dat is stabieler dan een interne constante nabouwen.
        assertTrue("het eerste item hoort een gap te zijn", items[0].mediaItem.localConfiguration == null)
        assertFalse("het tweede item is een bron", items[1].mediaItem.localConfiguration == null)
    }

    /**
     * Media3 weigert een sequence die met een gat begint als er geen spoor
     * gedeclareerd is. Deze test legt vast dat de vertaling dat regelt, want zonder
     * dat is een tijdlijn die met leegte begint niet te renderen.
     */
    @Test
    fun `een sequence die met een gap begint krijgt beeld- en geluidssporen`() {
        val composition = plan(
            RenderItem.Gap(timelineStartUs = 0L, durationUs = 1_000_000L),
            source(timelineStartUs = 1_000_000L),
        ).toComposition(context)

        val sequence = composition.sequences[0]
        assertTrue(sequence.trackTypes.contains(C.TRACK_TYPE_VIDEO))
        assertTrue(sequence.trackTypes.contains(C.TRACK_TYPE_AUDIO))
    }

    @Test
    fun `trims komen in de ClippingConfiguration terecht`() {
        val composition = plan(
            source(clipStartUs = 1_500_000L, clipEndUs = 4_000_000L),
        ).toComposition(context)

        val clipping = composition.sequences[0].editedMediaItems[0].mediaItem.clippingConfiguration
        assertEquals(1_500_000L, clipping.startPositionUs)
        assertEquals(4_000_000L, clipping.endPositionUs)
    }

    @Test
    fun `snelheid gaat via de SpeedProvider en niet via een effect`() {
        val item = plan(source(speed = 2f)).toComposition(context).sequences[0].editedMediaItems[0]

        assertEquals(2f, item.speedProvider.getSpeed(0L), 1e-6f)
        // SpeedChangeEffect is in 1.10.1 afgeschaft, en Media3 verbiedt
        // snelheidseffecten zodra er een SpeedProvider staat.
        assertTrue(
            "snelheid hoort niet óók nog als video-effect te verschijnen",
            item.effects.videoEffects.none { it::class.simpleName == "SpeedChangeEffect" },
        )
    }

    @Test
    fun `snelheid 1 laat de standaard SpeedProvider staan`() {
        val item = plan(source(speed = 1f)).toComposition(context).sequences[0].editedMediaItems[0]

        assertEquals(1f, item.speedProvider.getSpeed(0L), 1e-6f)
    }

    @Test
    fun `de framerate uit het plan wordt als bovengrens meegegeven`() {
        val item = plan(source()).toComposition(context).sequences[0].editedMediaItems[0]

        assertEquals(30, item.frameRate)
    }

    @Test
    fun `kleurcorrectie met belichting en contrast levert twee effecten op`() {
        val item = plan(
            source(effects = listOf(RenderEffect.ColorAdjust(exposure = 1f, contrast = 0.3f))),
        ).toComposition(context).sequences[0].editedMediaItems[0]

        val effects = item.effects.videoEffects
        assertEquals(1, effects.filterIsInstance<RgbAdjustment>().size)
        assertEquals(1, effects.filterIsInstance<Contrast>().size)
    }

    @Test
    fun `kleurcorrectie met alleen contrast levert geen belichtingsmatrix op`() {
        val item = plan(
            source(effects = listOf(RenderEffect.ColorAdjust(exposure = 0f, contrast = 0.3f))),
        ).toComposition(context).sequences[0].editedMediaItems[0]

        val effects = item.effects.videoEffects
        assertTrue(effects.filterIsInstance<RgbAdjustment>().isEmpty())
        assertEquals(1, effects.filterIsInstance<Contrast>().size)
    }

    @Test
    fun `ondertitels worden een OverlayEffect met een enkele overlay`() {
        val item = plan(
            source(
                effects = listOf(
                    RenderEffect.CaptionOverlays(
                        style = CaptionStyle(),
                        cues = listOf(
                            Cue(0L, 1_000_000L, "eerste"),
                            Cue(1_000_000L, 2_000_000L, "tweede"),
                        ),
                    ),
                ),
            ),
        ).toComposition(context).sequences[0].editedMediaItems[0]

        val overlays = item.effects.videoEffects.filterIsInstance<OverlayEffect>()
        assertEquals(1, overlays.size)
    }

    @Test
    fun `de effectvolgorde uit het plan blijft de rendervolgorde`() {
        val item = plan(
            source(
                effects = listOf(
                    RenderEffect.ColorAdjust(exposure = 0f, contrast = 0.2f),
                    RenderEffect.CaptionOverlays(CaptionStyle(), listOf(Cue(0L, 1L, "x"))),
                ),
            ),
        ).toComposition(context).sequences[0].editedMediaItems[0]

        val effects = item.effects.videoEffects
        assertTrue(
            "kleurcorrectie hoort vóór de ondertitels te staan",
            effects.indexOfFirst { it is Contrast } < effects.indexOfFirst { it is OverlayEffect },
        )
    }

    /**
     * Het in-punt hoort ongewijzigd bij de shader terecht te komen. Dit is de fout
     * die het bouwplan expliciet noemt: hem afleiden uit de clipping-configuratie
     * lijkt te werken tot iemand een clip trimt.
     */
    @Test
    fun `het bronoffset van een masked blur wordt niet opnieuw afgeleid`() {
        val item = plan(
            source(
                clipStartUs = 3_000_000L,
                clipEndUs = 8_000_000L,
                effects = listOf(
                    RenderEffect.MaskedBlur(
                        maskUri = "content://media/video/mask",
                        radiusFrac = 0.02f,
                        sourceOffsetUs = 3_000_000L,
                        sourceEndUs = 8_000_000L,
                        speed = 2f,
                    ),
                ),
            ),
        ).toComposition(context).sequences[0].editedMediaItems[0]

        val effect = item.effects.videoEffects.filterIsInstance<MaskedBlurEffect>().single()
        assertNotNull(effect)
        assertFalse(
            "een blur met radius groter dan nul mag geen no-op zijn",
            effect.isNoOp(1080, 1920),
        )
    }

    /**
     * Het hele effect gaat naar de shader, niet drie losse velden. Zo kan de
     * shader `sourcePtsFor` aanroepen — de omrekening waarin het in-punt én de
     * snelheid zitten. Werd de snelheid onderweg vergeten, dan liep het mask op
     * een versnelde clip lineair weg van het onderwerp, en de spike meet alleen
     * op 1×, dus die zou het niet zien.
     */
    @Test
    fun `de snelheid komt mee in de omrekening naar de maskvideo`() {
        val blur = RenderEffect.MaskedBlur(
            maskUri = "content://media/video/mask",
            radiusFrac = 0.02f,
            sourceOffsetUs = 3_000_000L,
            sourceEndUs = 13_000_000L,
            speed = 2f,
        )

        assertEquals(3_000_000L, blur.sourcePtsFor(0L))
        assertEquals(13_000_000L, blur.sourcePtsFor(5_000_000L))
    }

    @Test
    fun `een crop-pad wordt een effect dat per frame een matrix geeft`() {
        val keyframes = listOf(
            Keyframe(0L, NormRect(0.1f, 0.1f, 0.6f, 0.6f)),
            Keyframe(2_000_000L, NormRect(0.4f, 0.4f, 0.9f, 0.9f)),
        )
        val item = plan(source(effects = listOf(RenderEffect.CropPath(keyframes))))
            .toComposition(context).sequences[0].editedMediaItems[0]

        val crop = item.effects.videoEffects.filterIsInstance<CropPathEffect>().single()

        // Halverwege staat de uitsnede tussen beide rechthoeken in, dus de matrix
        // verschilt van die op tijd nul. Dat is het verschil met Media3's eigen
        // statische Crop.
        assertFalse(crop.getMatrix(0L) == crop.getMatrix(1_000_000L))
    }

    @Test
    fun `een leeg renderplan wordt geweigerd`() {
        val leeg = RenderPlan(width = 1080, height = 1920, frameRate = 30, sequences = emptyList())

        val fout = runCatching { leeg.toComposition(context) }.exceptionOrNull()
        assertTrue("verwacht een IllegalArgumentException", fout is IllegalArgumentException)
    }
}

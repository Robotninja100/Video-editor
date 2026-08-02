package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SECOND = US_PER_SECOND

private fun clip(
    id: String,
    inPointUs: Us,
    outPointUs: Us,
    speed: Float = 1f,
    effects: List<EffectSpec> = emptyList(),
) = Clip(
    id = id,
    sourceUri = "file:///media/$id.mp4",
    inPointUs = inPointUs,
    outPointUs = outPointUs,
    speed = speed,
    effects = effects,
)

private fun projectOf(vararg items: TimelineItem) =
    Project(id = "p", sequences = listOf(Sequence(id = "main", items = items.toList())))

private fun RenderPlan.firstSource(): RenderItem.Source =
    assertIs<RenderItem.Source>(sequences.first().items.first())

class RenderPlanStructureTest {

    @Test
    fun `gaten blijven staan en krijgen hun starttijd`() {
        val plan = projectOf(
            clip("a", 0L, 2 * SECOND),
            Gap(SECOND),
            clip("b", 0L, 3 * SECOND),
        ).toRenderPlan()

        val items = plan.sequences.single().items
        assertEquals(3, items.size)

        val gap = assertIs<RenderItem.Gap>(items[1])
        assertEquals(2 * SECOND, gap.timelineStartUs)
        assertEquals(SECOND, gap.durationUs)
        assertEquals(3 * SECOND, items[2].timelineStartUs)
    }

    @Test
    fun `de planduur is gelijk aan de projectduur`() {
        val project = projectOf(
            clip("a", 0L, 2 * SECOND),
            Gap(SECOND),
            clip("b", 4 * SECOND, 10 * SECOND, speed = 2f),
        )

        assertEquals(project.durationUs, project.toRenderPlan().durationUs)
    }

    @Test
    fun `outputspec komt in het plan terecht`() {
        val project = projectOf(clip("a", 0L, SECOND))
            .copy(outputSpec = OutputSpec(width = 720, height = 1280, frameRate = 60))
        val plan = project.toRenderPlan()

        assertEquals(720, plan.width)
        assertEquals(1280, plan.height)
        assertEquals(60, plan.frameRate)
    }

    @Test
    fun `trim en snelheid worden gescheiden gerapporteerd`() {
        val plan = projectOf(clip("a", 4 * SECOND, 6 * SECOND, speed = 2f)).toRenderPlan()
        val source = plan.firstSource()

        assertEquals(4 * SECOND, source.clipStartUs, "clipping is in brontijd")
        assertEquals(6 * SECOND, source.clipEndUs)
        assertEquals(2f, source.speed)
        assertEquals(SECOND, source.durationUs, "op 2x neemt 2 s bron 1 s tijdlijn in")
    }

    @Test
    fun `meerdere sequences blijven gescheiden`() {
        val project = Project(
            id = "p",
            sequences = listOf(
                Sequence("main", listOf(clip("a", 0L, 2 * SECOND))),
                Sequence("overlay", listOf(Gap(SECOND), clip("b", 0L, SECOND))),
            ),
        )
        val plan = project.toRenderPlan()

        assertEquals(listOf("main", "overlay"), plan.sequences.map { it.id })
        assertEquals(2 * SECOND, plan.durationUs)
    }
}

class RenderEffectMappingTest {

    @Test
    fun `effecten zonder werking verdwijnen uit het plan`() {
        val plan = projectOf(
            clip(
                "a", 0L, SECOND,
                effects = listOf(
                    EffectSpec.ColorAdjust(exposure = 0f, contrast = 0f),
                    EffectSpec.Crop(path = emptyList()),
                    EffectSpec.Captions(cues = emptyList()),
                    EffectSpec.MaskedBlur(maskUri = "file:///m.mp4", radiusFrac = 0f),
                ),
            ),
        ).toRenderPlan()

        assertEquals(emptyList(), plan.firstSource().effects)
    }

    @Test
    fun `de effectvolgorde blijft behouden`() {
        val plan = projectOf(
            clip(
                "a", 0L, SECOND,
                effects = listOf(
                    EffectSpec.ColorAdjust(exposure = 0.2f, contrast = 0f),
                    EffectSpec.MaskedBlur(maskUri = "file:///m.mp4", radiusFrac = 0.02f),
                ),
            ),
        ).toRenderPlan()

        val effects = plan.firstSource().effects
        assertIs<RenderEffect.ColorAdjust>(effects[0])
        assertIs<RenderEffect.MaskedBlur>(effects[1])
    }

    @Test
    fun `sourceOffsetUs volgt het in-punt van de clip`() {
        val plan = projectOf(
            clip(
                "a", 4 * SECOND, 6 * SECOND,
                effects = listOf(EffectSpec.MaskedBlur("file:///m.mp4", radiusFrac = 0.02f)),
            ),
        ).toRenderPlan()

        val blur = assertIs<RenderEffect.MaskedBlur>(plan.firstSource().effects.single())
        assertEquals(4 * SECOND, blur.sourceOffsetUs)
    }

    @Test
    fun `sourceOffsetUs klopt nog na een split`() {
        val sequence = Sequence(
            id = "main",
            items = listOf(
                clip(
                    "a", 0L, 10 * SECOND,
                    effects = listOf(EffectSpec.MaskedBlur("file:///m.mp4", radiusFrac = 0.02f)),
                ),
            ),
        ).splitAt(4 * SECOND)

        val sources = sequence.toRenderSequence().items.map { assertIs<RenderItem.Source>(it) }
        val offsets = sources.map {
            assertIs<RenderEffect.MaskedBlur>(it.effects.single()).sourceOffsetUs
        }

        assertEquals(listOf(0L, 4 * SECOND), offsets, "de rechterhelft begint 4 s verderop in de bron")
    }
}

class RenderCueMappingTest {

    private fun cuesOf(vararg cues: Cue) = listOf(EffectSpec.Captions(cues = cues.toList()))

    private fun RenderPlan.cues(): List<Cue> =
        assertIs<RenderEffect.CaptionOverlays>(firstSource().effects.single()).cues

    @Test
    fun `cues verschuiven mee met het in-punt`() {
        val plan = projectOf(
            clip(
                "a", 4 * SECOND, 8 * SECOND,
                effects = cuesOf(Cue(5 * SECOND, 6 * SECOND, "hallo")),
            ),
        ).toRenderPlan()

        val cue = plan.cues().single()
        assertEquals(SECOND, cue.startUs)
        assertEquals(2 * SECOND, cue.endUs)
    }

    @Test
    fun `cues worden ingekort door de snelheid`() {
        val plan = projectOf(
            clip(
                "a", 4 * SECOND, 8 * SECOND, speed = 2f,
                effects = cuesOf(Cue(5 * SECOND, 6 * SECOND, "hallo")),
            ),
        ).toRenderPlan()

        val cue = plan.cues().single()
        assertEquals(SECOND / 2, cue.startUs)
        assertEquals(SECOND, cue.endUs)
    }

    @Test
    fun `cues buiten de clip verdwijnen`() {
        val plan = projectOf(
            clip(
                "a", 4 * SECOND, 8 * SECOND,
                effects = cuesOf(
                    Cue(SECOND, 2 * SECOND, "ervoor"),
                    Cue(5 * SECOND, 6 * SECOND, "erbinnen"),
                    Cue(9 * SECOND, 10 * SECOND, "erna"),
                ),
            ),
        ).toRenderPlan()

        assertEquals(listOf("erbinnen"), plan.cues().map { it.text })
    }

    @Test
    fun `cues over de rand worden afgekapt`() {
        val plan = projectOf(
            clip(
                "a", 4 * SECOND, 8 * SECOND,
                effects = cuesOf(
                    Cue(3 * SECOND, 5 * SECOND, "begin"),
                    Cue(7 * SECOND, 9 * SECOND, "eind"),
                ),
            ),
        ).toRenderPlan()

        val cues = plan.cues()
        assertEquals(0L, cues[0].startUs, "afgekapt op het in-punt")
        assertEquals(SECOND, cues[0].endUs)
        assertEquals(3 * SECOND, cues[1].startUs)
        assertEquals(4 * SECOND, cues[1].endUs, "afgekapt op het uit-punt")
    }

    @Test
    fun `woordtijden gaan door dezelfde afbeelding`() {
        val plan = projectOf(
            clip(
                "a", 4 * SECOND, 8 * SECOND,
                effects = cuesOf(
                    Cue(
                        startUs = 5 * SECOND,
                        endUs = 6 * SECOND,
                        text = "hallo daar",
                        words = listOf(
                            Cue.Word(3 * SECOND, 5 * SECOND, "voor"),
                            Cue.Word(5 * SECOND, 5 * SECOND + SECOND / 2, "hallo"),
                            Cue.Word(5 * SECOND + SECOND / 2, 6 * SECOND, "daar"),
                        ),
                    ),
                ),
            ),
        ).toRenderPlan()

        val words = plan.cues().single().words
        assertEquals(
            listOf("hallo", "daar"), words.map { it.text },
            "een woord dat volledig vóór de cue ligt hoort er niet meer bij",
        )
        assertEquals(SECOND, words[0].startUs)
        assertEquals(2 * SECOND, words[1].endUs)
    }

    @Test
    fun `een clip zonder overlappende cues houdt geen leeg captions-effect over`() {
        val plan = projectOf(
            clip("a", 4 * SECOND, 8 * SECOND, effects = cuesOf(Cue(SECOND, 2 * SECOND, "ervoor"))),
        ).toRenderPlan()

        assertEquals(emptyList(), plan.firstSource().effects)
    }
}

class RenderCropPathTest {

    private val path = listOf(
        Keyframe(0L, NormRect(0f, 0f, 0.5f, 0.5f)),
        Keyframe(10 * SECOND, NormRect(0.5f, 0.5f, 1f, 1f)),
    )

    @Test
    fun `het pad wordt bijgesneden op het clipvenster`() {
        val plan = projectOf(
            clip("a", 2500_000L, 7500_000L, effects = listOf(EffectSpec.Crop(path))),
        ).toRenderPlan()

        val crop = assertIs<RenderEffect.CropPath>(plan.firstSource().effects.single())
        assertEquals(listOf(0L, 5 * SECOND), crop.keyframes.map { it.atUs })
    }

    @Test
    fun `de randen worden geinterpoleerd en niet weggegooid`() {
        val plan = projectOf(
            clip("a", 2500_000L, 7500_000L, effects = listOf(EffectSpec.Crop(path))),
        ).toRenderPlan()

        val crop = assertIs<RenderEffect.CropPath>(plan.firstSource().effects.single())
        assertEquals(0.125f, crop.keyframes.first().value.left, 1e-5f)
        assertEquals(0.375f, crop.keyframes.last().value.left, 1e-5f)
    }

    @Test
    fun `keyframes binnen het venster blijven behouden`() {
        val withMiddle = path.toMutableList().apply {
            add(1, Keyframe(5 * SECOND, NormRect(0.4f, 0.4f, 0.9f, 0.9f)))
        }
        val plan = projectOf(
            clip("a", 2500_000L, 7500_000L, effects = listOf(EffectSpec.Crop(withMiddle))),
        ).toRenderPlan()

        val crop = assertIs<RenderEffect.CropPath>(plan.firstSource().effects.single())
        assertEquals(listOf(0L, 2500_000L, 5 * SECOND), crop.keyframes.map { it.atUs })
    }
}

class RenderPlanSerializationTest {

    @Test
    fun `het plan overleeft een JSON-roundtrip`() {
        val plan = projectOf(
            clip("a", 0L, 2 * SECOND),
            Gap(SECOND),
            clip(
                "b", 4 * SECOND, 8 * SECOND, speed = 2f,
                effects = listOf(
                    EffectSpec.MaskedBlur("file:///m.mp4", radiusFrac = 0.03f),
                    EffectSpec.Captions(cues = listOf(Cue(5 * SECOND, 6 * SECOND, "hallo"))),
                    EffectSpec.ColorAdjust(exposure = 0.1f, contrast = -0.1f),
                ),
            ),
        ).toRenderPlan()

        val text = ProjectJson.format.encodeToString(plan)
        assertEquals(plan, ProjectJson.format.decodeFromString<RenderPlan>(text))
        assertTrue(text.contains("maskedBlur"), "de discriminator moet leesbaar blijven")
    }
}

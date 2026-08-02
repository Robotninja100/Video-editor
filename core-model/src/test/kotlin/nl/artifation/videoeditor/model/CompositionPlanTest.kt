package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun clip(id: String, durationUs: Us, effects: List<EffectSpec> = emptyList()) = Clip(
    id = id,
    sourceUri = "file:///media/$id.mp4",
    inPointUs = 0L,
    outPointUs = durationUs,
    effects = effects,
)

class NdcConversionTest {

    @Test
    fun `het volledige frame wordt het volledige NDC-vierkant`() {
        val crop = NormRect.FULL.toNdcCrop()

        assertEquals(-1f, crop.left, 1e-5f)
        assertEquals(1f, crop.right, 1e-5f)
        assertEquals(1f, crop.top, 1e-5f)
        assertEquals(-1f, crop.bottom, 1e-5f)
    }

    @Test
    fun `de y-as klapt om`() {
        // Bovenste helft in modelcoördinaten (y omlaag, 0 = boven).
        val crop = NormRect(left = 0f, top = 0f, right = 1f, bottom = 0.5f).toNdcCrop()

        assertEquals(1f, crop.top, 1e-5f, "boven blijft boven in NDC")
        assertEquals(0f, crop.bottom, 1e-5f, "de onderkant ligt op het midden")
        assertTrue(crop.top > crop.bottom, "in NDC wijst y omhoog")
    }

    @Test
    fun `linksboven kwadrant komt goed uit`() {
        val crop = NormRect(0f, 0f, 0.5f, 0.5f).toNdcCrop()

        assertEquals(-1f, crop.left, 1e-5f)
        assertEquals(0f, crop.right, 1e-5f)
        assertEquals(1f, crop.top, 1e-5f)
        assertEquals(0f, crop.bottom, 1e-5f)
    }

    @Test
    fun `een gecentreerde 9 op 16 strook blijft gecentreerd`() {
        val width = (9f / 16f) / (16f / 9f)
        val crop = NormRect(
            left = 0.5f - width / 2f,
            top = 0f,
            right = 0.5f + width / 2f,
            bottom = 1f,
        ).toNdcCrop()

        assertEquals(0f, (crop.left + crop.right) / 2f, 1e-5f, "horizontaal gecentreerd")
        assertEquals(0f, (crop.top + crop.bottom) / 2f, 1e-5f, "verticaal gecentreerd")
    }
}

class CompositionPlanTest {

    @Test
    fun `gaten en clips blijven op volgorde staan`() {
        val project = Project(
            id = "p",
            sequences = listOf(
                Sequence("main", listOf(clip("a", 1_000L), Gap(500L), clip("b", 1_000L))),
            ),
        )

        val plan = project.toCompositionPlan()
        val items = plan.sequences.single().items

        assertIs<PlannedClip>(items[0])
        assertIs<PlannedGap>(items[1])
        assertIs<PlannedClip>(items[2])
        assertEquals(2_500L, plan.durationUs)
    }

    @Test
    fun `snelheid is al verrekend in de plandurr`() {
        val fast = Clip("a", "file:///a.mp4", 0L, 2_000L, speed = 2f)
        val plan = Project("p", listOf(Sequence("main", listOf(fast)))).toCompositionPlan()

        val planned = assertIs<PlannedClip>(plan.sequences.single().items.single())
        assertEquals(1_000L, planned.durationUs, "de renderlaag hoeft niet meer te rekenen")
        assertEquals(2_000L, planned.outPointUs, "bronpunten blijven ongewijzigd")
    }

    @Test
    fun `een leeg cropppad wordt het volledige frame`() {
        val project = Project(
            id = "p",
            sequences = listOf(Sequence("main", listOf(clip("a", 1_000L, listOf(EffectSpec.Crop()))))),
        )

        val planned = assertIs<PlannedClip>(project.toCompositionPlan().sequences.single().items.single())
        val crop = assertIs<PlannedEffect.Crop>(planned.effects.single())
        assertEquals(-1f, crop.left, 1e-5f)
        assertEquals(1f, crop.right, 1e-5f)
    }

    @Test
    fun `een crop met één keyframe wordt statisch`() {
        val effect = EffectSpec.Crop(listOf(Keyframe(0L, NormRect(0f, 0f, 0.5f, 1f))))
        val project = Project("p", listOf(Sequence("main", listOf(clip("a", 1_000L, listOf(effect))))))

        val planned = assertIs<PlannedClip>(project.toCompositionPlan().sequences.single().items.single())
        assertIs<PlannedEffect.Crop>(planned.effects.single())
    }

    @Test
    fun `een crop met meerdere keyframes blijft geanimeerd`() {
        val effect = EffectSpec.Crop(
            listOf(
                Keyframe(0L, NormRect(0f, 0f, 0.5f, 1f)),
                Keyframe(1_000L, NormRect(0.5f, 0f, 1f, 1f)),
            ),
        )
        val project = Project("p", listOf(Sequence("main", listOf(clip("a", 1_000L, listOf(effect))))))

        val planned = assertIs<PlannedClip>(project.toCompositionPlan().sequences.single().items.single())
        val animated = assertIs<PlannedEffect.AnimatedCrop>(planned.effects.single())
        assertEquals(2, animated.path.size)
    }

    @Test
    fun `effectvolgorde blijft behouden`() {
        val effects = listOf(
            EffectSpec.ColorAdjust(exposure = 0.1f),
            EffectSpec.MaskedBlur("file:///masks.mp4"),
        )
        val project = Project("p", listOf(Sequence("main", listOf(clip("a", 1_000L, effects)))))

        val planned = assertIs<PlannedClip>(project.toCompositionPlan().sequences.single().items.single())
        assertIs<PlannedEffect.ColorAdjust>(planned.effects[0])
        assertIs<PlannedEffect.MaskedBlur>(planned.effects[1])
    }

    @Test
    fun `de langste sequence bepaalt de duur`() {
        val project = Project(
            id = "p",
            sequences = listOf(
                Sequence("main", listOf(clip("a", 1_000L))),
                Sequence("overlay", listOf(clip("b", 3_000L))),
            ),
        )
        assertEquals(3_000L, project.toCompositionPlan().durationUs)
    }
}

class UndoStackTest {

    private fun project(id: String) = Project(id, listOf(Sequence("main")))

    @Test
    fun `undo en redo lopen door de geschiedenis`() {
        val stack = UndoStack(project("v1"))
        stack.push(project("v2"))
        stack.push(project("v3"))

        assertEquals("v2", stack.undo().id)
        assertEquals("v1", stack.undo().id)
        assertEquals("v2", stack.redo().id)
        assertEquals("v3", stack.redo().id)
    }

    @Test
    fun `een bewerking die niets verandert wordt genegeerd`() {
        val stack = UndoStack(project("v1"))
        stack.push(project("v1"))

        assertEquals(0, stack.undoDepth, "identieke toestand hoort geen stap te zijn")
        assertTrue(!stack.canUndo)
    }

    @Test
    fun `een nieuwe bewerking wist de redo-tak`() {
        val stack = UndoStack(project("v1"))
        stack.push(project("v2"))
        stack.undo()
        stack.push(project("v3"))

        assertTrue(!stack.canRedo, "redo hoort weg te zijn na een nieuwe bewerking")
        assertEquals("v3", stack.current.id)
    }

    @Test
    fun `undo op een lege geschiedenis doet niets`() {
        val stack = UndoStack(project("v1"))

        assertEquals("v1", stack.undo().id)
        assertEquals("v1", stack.redo().id)
    }

    @Test
    fun `de geschiedenis blijft binnen de limiet`() {
        val stack = UndoStack(project("v0"), limit = 3)
        repeat(10) { stack.push(project("v${it + 1}")) }

        assertEquals(3, stack.undoDepth)
    }

    @Test
    fun `edit past een transformatie toe`() {
        val stack = UndoStack(project("v1"))
        stack.edit { it.copy(id = "v2") }

        assertEquals("v2", stack.current.id)
        assertTrue(stack.canUndo)
    }
}

/**
 * De masktrack moet synchroon meelopen met het bronmateriaal. Twee dingen konden
 * daarbij misgaan, en gingen ook mis: de in-point van een getrimde clip, en de
 * snelheid. Die laatste ontbrak volledig, waardoor de blur bij 2× lineair
 * wegliep van het onderwerp.
 */
class BronPositieTest {

    private fun planned(
        inPointUs: Us = 0L,
        outPointUs: Us = 60_000_000L,
        speed: Float = 1f,
    ) = PlannedClip(
        sourceUri = "file:///a.mp4",
        inPointUs = inPointUs,
        outPointUs = outPointUs,
        speed = speed,
        effects = emptyList(),
        durationUs = ((outPointUs - inPointUs) / speed).toLong(),
    )

    @Test
    fun `op normale snelheid loopt de bron gelijk op met de uitvoer`() {
        val clip = planned()

        assertEquals(0L, clip.sourcePtsFor(0L))
        assertEquals(5_000_000L, clip.sourcePtsFor(5_000_000L))
    }

    @Test
    fun `op dubbele snelheid ligt uitvoer van vijf seconden op bron tien`() {
        val clip = planned(speed = 2f)

        assertEquals(
            10_000_000L,
            clip.sourcePtsFor(5_000_000L),
            "zonder deze factor loopt de blur vijf seconden achter",
        )
    }

    @Test
    fun `op halve snelheid loopt de bron half zo snel`() {
        assertEquals(2_500_000L, planned(speed = 0.5f).sourcePtsFor(5_000_000L))
    }

    @Test
    fun `een getrimde clip telt zijn in-point erbij`() {
        val clip = planned(inPointUs = 5_000_000L)

        assertEquals(
            5_000_000L,
            clip.sourcePtsFor(0L),
            "op uitvoertijd nul hoort de mask van het in-point",
        )
        assertEquals(7_000_000L, clip.sourcePtsFor(2_000_000L))
    }

    @Test
    fun `in-point en snelheid werken samen`() {
        val clip = planned(inPointUs = 5_000_000L, speed = 2f)

        assertEquals(15_000_000L, clip.sourcePtsFor(5_000_000L))
    }

    @Test
    fun `de bronpositie gaat nooit voorbij het einde van de clip`() {
        val clip = planned(inPointUs = 1_000_000L, outPointUs = 3_000_000L)

        assertEquals(
            3_000_000L,
            clip.sourcePtsFor(99_000_000L),
            "voorbij het einde staat geen mask; de decoder zou doorspoelen",
        )
    }

    @Test
    fun `een negatieve uitvoertijd levert het in-point op`() {
        assertEquals(1_000_000L, planned(inPointUs = 1_000_000L).sourcePtsFor(-5_000L))
    }
}

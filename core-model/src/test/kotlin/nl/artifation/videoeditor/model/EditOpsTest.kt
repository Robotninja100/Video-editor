package nl.artifation.videoeditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun clip(
    id: String,
    durationUs: Us,
    inPointUs: Us = 0L,
    speed: Float = 1f,
) = Clip(
    id = id,
    sourceUri = "file:///media/$id.mp4",
    inPointUs = inPointUs,
    outPointUs = inPointUs + (durationUs * speed).toLong(),
    speed = speed,
)

private fun sequenceOf(vararg items: TimelineItem) = Sequence(id = "main", items = items.toList())

class ClipDurationTest {

    @Test
    fun `snelheid verkort de tijdlijnduur`() {
        val normal = clip("a", durationUs = 1_000L)
        assertEquals(1_000L, normal.durationUs)

        val fast = clip("a", durationUs = 1_000L, speed = 2f)
        assertEquals(2_000L, fast.sourceDurationUs, "bronmateriaal blijft even lang")
        assertEquals(1_000L, fast.durationUs, "op 2x neemt de clip de helft van de tijdlijn in")
    }
}

class SplitTest {

    @Test
    fun `splitsen behoudt de totale duur`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 1_000L))
        val split = sequence.splitAt(500L)

        assertEquals(3, split.items.size)
        assertEquals(sequence.durationUs, split.durationUs)
        assertEquals(listOf(500L, 500L, 1_000L), split.items.map { it.durationUs })
    }

    @Test
    fun `splitsen verdeelt het bronmateriaal op het juiste punt`() {
        val sequence = sequenceOf(clip("a", 1_000L, inPointUs = 4_000L))
        val split = sequence.splitAt(250L)

        val left = assertIs<Clip>(split.items[0])
        val right = assertIs<Clip>(split.items[1])
        assertEquals(4_000L..4_250L, left.inPointUs..left.outPointUs)
        assertEquals(4_250L..5_000L, right.inPointUs..right.outPointUs)
    }

    @Test
    fun `splitsen rekent de snelheid mee bij het bronpunt`() {
        val sequence = sequenceOf(clip("a", durationUs = 1_000L, speed = 2f))
        val split = sequence.splitAt(500L)

        val left = assertIs<Clip>(split.items[0])
        assertEquals(1_000L, left.outPointUs, "500us tijdlijn op 2x is 1000us bronmateriaal")
        assertEquals(1_000L, split.durationUs)
    }

    @Test
    fun `splitsen op een grens doet niets`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 1_000L))

        assertEquals(sequence, sequence.splitAt(0L))
        assertEquals(sequence, sequence.splitAt(1_000L))
        assertEquals(sequence, sequence.splitAt(2_000L), "voorbij het einde")
    }

    @Test
    fun `splitsen levert deterministische ids op`() {
        val sequence = sequenceOf(clip("a", 1_000L))
        assertEquals(
            sequence.splitAt(500L).items.map { (it as Clip).id },
            sequence.splitAt(500L).items.map { (it as Clip).id },
        )
    }

    @Test
    fun `een gat splitsen levert twee gaten op`() {
        val split = sequenceOf(Gap(1_000L)).splitAt(400L)
        assertEquals(listOf<TimelineItem>(Gap(400L), Gap(600L)), split.items)
    }
}

class RemoveAndLiftTest {

    @Test
    fun `ripple delete verkort de tijdlijn`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 500L))
        val result = sequence.rippleDelete(0)

        assertEquals(500L, result.durationUs)
        assertEquals(listOf("b"), result.items.map { (it as Clip).id })
    }

    @Test
    fun `lift laat een gat achter en behoudt de duur`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 500L))
        val result = sequence.lift(0)

        assertEquals(sequence.durationUs, result.durationUs)
        assertEquals(Gap(1_000L), result.items[0])
        assertEquals(1_000L, result.itemStartsUs()[1], "b blijft op dezelfde plek staan")
    }
}

class OverwriteAndMoveTest {

    @Test
    fun `overschrijven splitst op beide grenzen`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 1_000L))
        val result = sequence.overwriteAt(500L, clip("c", 1_000L))

        assertEquals(2_000L, result.durationUs, "totale duur verandert niet")
        assertEquals(listOf(500L, 1_000L, 500L), result.items.map { it.durationUs })
        assertEquals(
            listOf("a", "c", "b@500"),
            result.items.map { (it as Clip).id },
            "de rest van b overleeft als afgeleide clip",
        )
    }

    @Test
    fun `overschrijven voorbij het einde vult een gat aan`() {
        val sequence = sequenceOf(clip("a", 1_000L))
        val result = sequence.overwriteAt(3_000L, clip("b", 500L))

        assertEquals(listOf<Long>(1_000L, 2_000L, 500L), result.items.map { it.durationUs })
        assertIs<Gap>(result.items[1])
        assertEquals(3_500L, result.durationUs)
    }

    @Test
    fun `clip naar het einde verplaatsen laat een gat achter`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 1_000L))
        val result = sequence.moveClipTo(0, 2_000L)

        assertEquals(listOf<TimelineItem>(Gap(1_000L)), result.items.take(1))
        assertEquals(listOf("b", "a"), result.items.drop(1).map { (it as Clip).id })
        assertEquals(3_000L, result.durationUs)
    }

    @Test
    fun `moveItem herordent zonder de duur te veranderen`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 500L), clip("c", 250L))
        val result = sequence.moveItem(fromIndex = 0, toIndex = 2)

        assertEquals(listOf("b", "c", "a"), result.items.map { (it as Clip).id })
        assertEquals(sequence.durationUs, result.durationUs)
    }
}

class NormalizeTest {

    @Test
    fun `aangrenzende gaten worden samengevoegd`() {
        val sequence = sequenceOf(Gap(100L), Gap(200L), clip("a", 500L), Gap(50L))
        val result = sequence.normalized()

        assertEquals(listOf<Long>(300L, 500L, 50L), result.items.map { it.durationUs })
        assertEquals(sequence.durationUs, result.durationUs, "duur blijft gelijk")
    }

    @Test
    fun `items zonder duur verdwijnen`() {
        val result = sequenceOf(Gap(0L), clip("a", 500L)).normalized()
        assertEquals(1, result.items.size)
    }
}

class LocateTest {

    @Test
    fun `locate geeft item en offset`() {
        val sequence = sequenceOf(clip("a", 1_000L), clip("b", 1_000L))

        val located = sequence.locate(1_250L)
        assertEquals(1, located?.index)
        assertEquals(250L, located?.offsetUs)
    }

    @Test
    fun `locate buiten de tijdlijn geeft null`() {
        val sequence = sequenceOf(clip("a", 1_000L))
        assertNull(sequence.locate(-1L))
        assertNull(sequence.locate(1_000L), "einde is exclusief")
    }
}

class InterpolateTest {

    @Test
    fun `crop-pad interpoleert lineair`() {
        val path = listOf(
            Keyframe(0L, NormRect(0f, 0f, 0.5f, 1f)),
            Keyframe(1_000L, NormRect(0.5f, 0f, 1f, 1f)),
        )

        val mid = path.interpolateAt(500L)!!
        assertEquals(0.25f, mid.left, 1e-5f)
        assertEquals(0.75f, mid.right, 1e-5f)
    }

    @Test
    fun `buiten het pad wordt het randkeyframe vastgehouden`() {
        val path = listOf(
            Keyframe(1_000L, NormRect(0f, 0f, 0.5f, 1f)),
            Keyframe(2_000L, NormRect(0.5f, 0f, 1f, 1f)),
        )

        assertEquals(path.first().value, path.interpolateAt(0L))
        assertEquals(path.last().value, path.interpolateAt(9_000L))
        assertNull(emptyList<Keyframe<NormRect>>().interpolateAt(0L))
    }
}

class ValidationTest {

    @Test
    fun `een gezond project heeft geen problemen`() {
        val project = Project(id = "p1", sequences = listOf(sequenceOf(clip("a", 1_000L))))
        assertEquals(emptyList(), project.validate())
    }

    @Test
    fun `omgekeerde in- en uitpunten worden gemeld`() {
        val broken = Clip(id = "a", sourceUri = "file:///a.mp4", inPointUs = 500L, outPointUs = 100L)
        val problems = Project(id = "p1", sequences = listOf(sequenceOf(broken))).validate()

        assertTrue(problems.any { it.path.endsWith("outPointUs") }, "gevonden: $problems")
    }

    @Test
    fun `oneven exportafmetingen worden gemeld`() {
        val problems = OutputSpec(width = 1081, height = 1920).validate()
        assertEquals(1, problems.size)
        assertTrue(problems.single().path.endsWith("width"))
    }

    @Test
    fun `dubbele sequence-ids worden gemeld`() {
        val project = Project(id = "p1", sequences = listOf(sequenceOf(), sequenceOf()))
        assertTrue(project.validate().any { "dubbele" in it.message })
    }
}

class SerializationTest {

    @Test
    fun `project overleeft een json-roundtrip`() {
        val project = Project(
            id = "p1",
            sequences = listOf(
                Sequence(
                    id = "main",
                    items = listOf(
                        Clip(
                            id = "a",
                            sourceUri = "file:///media/a.mp4",
                            inPointUs = 0L,
                            outPointUs = 5_000_000L,
                            speed = 1.5f,
                            effects = listOf(
                                EffectSpec.MaskedBlur(maskUri = "file:///cache/a/masks_0.mp4"),
                                EffectSpec.Crop(
                                    path = listOf(Keyframe(0L, NormRect(0f, 0f, 0.5f, 1f))),
                                ),
                                EffectSpec.Captions(
                                    cues = listOf(
                                        Cue(
                                            startUs = 0L,
                                            endUs = 1_000_000L,
                                            text = "hallo",
                                            words = listOf(Cue.Word(0L, 500_000L, "hallo")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        Gap(250_000L),
                    ),
                ),
            ),
            outputSpec = OutputSpec(width = 1080, height = 1920, frameRate = 30),
        )

        val decoded = ProjectJson.decode(ProjectJson.encode(project))
        assertEquals(project, decoded)
    }

    @Test
    fun `onbekende velden blokkeren het laden niet`() {
        val json = """
            {
              "id": "p1",
              "sequences": [{ "id": "main", "items": [], "toekomstigVeld": 42 }]
            }
        """.trimIndent()

        assertEquals("p1", ProjectJson.decode(json).id)
    }
}

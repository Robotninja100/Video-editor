package nl.artifation.videoeditor.remote

import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Transcript
import nl.artifation.videoeditor.model.TranscriptSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptionParseTest {

    private val body = """
        {
          "text": "hallo daar wereld",
          "segments": [
            {"id": 7, "start": 0.0,  "end": 1.5, "text": " hallo daar"},
            {"id": 9, "start": 1.5,  "end": 3.0, "text": " wereld"}
          ],
          "words": [
            {"word": "hallo",  "start": 0.0, "end": 0.6},
            {"word": "daar",   "start": 0.7, "end": 1.4},
            {"word": "wereld", "start": 1.6, "end": 2.9}
          ]
        }
    """.trimIndent()

    @Test
    fun `segmenten krijgen tijden in microseconden`() {
        val transcript = Transcription.parse(body)

        assertEquals(2, transcript.segments.size)
        assertEquals(0L, transcript.segments[0].startUs)
        assertEquals(1_500_000L, transcript.segments[0].endUs)
        assertEquals(3_000_000L, transcript.durationUs)
    }

    @Test
    fun `segmenten worden hernummerd vanaf nul`() {
        // De API geeft hier 7 en 9; auto-edit rekent op aaneengesloten indices.
        assertEquals(listOf(0, 1), Transcription.parse(body).segments.map { it.index })
    }

    @Test
    fun `woorden worden aan het juiste segment toegewezen`() {
        val transcript = Transcription.parse(body)

        assertEquals(listOf("hallo", "daar"), transcript.segments[0].words.map { it.text })
        assertEquals(listOf("wereld"), transcript.segments[1].words.map { it.text })
    }

    @Test
    fun `tekst wordt getrimd`() {
        assertEquals("hallo daar", Transcription.parse(body).segments[0].text)
    }

    @Test
    fun `een antwoord met alleen woorden levert één segment op`() {
        val onlyWords = """
            {"text": "een twee", "words": [
              {"word": "een",  "start": 0.0, "end": 0.5},
              {"word": "twee", "start": 0.5, "end": 1.0}
            ]}
        """.trimIndent()

        val transcript = Transcription.parse(onlyWords)
        assertEquals(1, transcript.segments.size)
        assertEquals(2, transcript.segments.single().words.size)
    }

    @Test
    fun `een leeg antwoord levert een leeg transcript op`() {
        assertEquals(Transcript(), Transcription.parse("""{"text": ""}"""))
    }

    @Test
    fun `onbekende velden worden genegeerd`() {
        val withExtras = """{"text": "x", "task": "transcribe", "duration": 3.0, "segments": []}"""
        assertEquals(Transcript(), Transcription.parse(withExtras))
    }

    @Test
    fun `de request bevat de velden die de API nodig heeft`() {
        val fields = Transcription.Request(language = "nl").asFormFields()

        assertEquals("whisper-large-v3-turbo", fields["model"])
        assertEquals("verbose_json", fields["response_format"])
        assertEquals("nl", fields["language"])
        assertEquals("segment", fields["timestamp_granularities[0]"])
        assertEquals("word", fields["timestamp_granularities[1]"])
    }

    @Test
    fun `zonder taal blijft het veld weg zodat de API detecteert`() {
        assertTrue("language" !in Transcription.Request().asFormFields())
    }
}

class TimestampAlignmentTest {

    @Test
    fun `een woord dat in een stilte begint schuift naar het einde ervan`() {
        val transcript = Transcript(
            listOf(
                TranscriptSegment(
                    index = 0,
                    startUs = 0L,
                    endUs = 3_000_000L,
                    text = "a b",
                    words = listOf(
                        Cue.Word(0L, 500_000L, "a"),
                        Cue.Word(1_200_000L, 2_000_000L, "b"),
                    ),
                ),
            ),
        )
        val silences = listOf(TimestampAlignment.Silence(1_000_000L, 1_500_000L))

        val aligned = TimestampAlignment.align(transcript, silences)
        val words = aligned.segments.single().words

        assertEquals(0L, words[0].startUs, "buiten de stilte blijft ongemoeid")
        assertEquals(1_500_000L, words[1].startUs, "geschoven naar het einde van de stilte")
        assertEquals(2_000_000L, words[1].endUs)
    }

    @Test
    fun `het einde schuift mee als het anders vóór het begin zou liggen`() {
        val transcript = Transcript(
            listOf(
                TranscriptSegment(0, 0L, 2_000_000L, "a", listOf(Cue.Word(1_000_000L, 1_100_000L, "a"))),
            ),
        )
        val aligned = TimestampAlignment.align(
            transcript,
            listOf(TimestampAlignment.Silence(900_000L, 1_800_000L)),
        )

        val word = aligned.segments.single().words.single()
        assertTrue(word.endUs >= word.startUs, "woord met negatieve duur: $word")
    }

    @Test
    fun `zonder stiltes verandert er niets`() {
        val transcript = Transcript(listOf(TranscriptSegment(0, 0L, 1L, "a")))
        assertEquals(transcript, TimestampAlignment.align(transcript, emptyList()))
    }
}

class RleDecodeTest {

    @Test
    fun `de reeks begint bij achtergrond`() {
        // 2 achtergrond, 3 voorgrond, 1 achtergrond
        val mask = Segmentation.decodeRle(listOf(2, 3, 1), totalPixels = 6)

        assertEquals(listOf(0, 0, 255, 255, 255, 0), mask.map { it.toInt() and 0xFF })
    }

    @Test
    fun `een lege reeks levert een lege mask op`() {
        assertTrue(Segmentation.decodeRle(emptyList(), 4).all { it == 0.toByte() })
    }

    @Test
    fun `een run voorbij het einde wordt afgekapt in plaats van te crashen`() {
        val mask = Segmentation.decodeRle(listOf(0, 100), totalPixels = 3)
        assertEquals(listOf(255, 255, 255), mask.map { it.toInt() and 0xFF })
    }

    @Test
    fun `alles voorgrond`() {
        val mask = Segmentation.decodeRle(listOf(0, 4), totalPixels = 4)
        assertTrue(mask.all { (it.toInt() and 0xFF) == 255 })
    }

    @Test
    fun `negatieve runs worden als nul behandeld`() {
        val mask = Segmentation.decodeRle(listOf(-5, 2), totalPixels = 2)
        assertEquals(listOf(255, 255), mask.map { it.toInt() and 0xFF })
    }
}

class SegmentationTest {

    @Test
    fun `masks worden geparseerd naar bytemasks`() {
        val body = """
            {"masks": [
              {"frame": 0, "width": 2, "height": 2, "counts": [1, 2, 1]},
              {"frame": 1, "width": 2, "height": 2, "counts": [0, 4]}
            ]}
        """.trimIndent()

        val masks = Segmentation.parse(body)

        assertEquals(2, masks.size)
        assertEquals(listOf(0, 255, 255, 0), masks[0].pixels.map { it.toInt() and 0xFF })
        assertTrue(masks[1].pixels.all { (it.toInt() and 0xFF) == 255 })
    }

    @Test
    fun `de kostenschatting schaalt met framerate en duur`() {
        val at30 = Segmentation.Request("https://x/v.mp4", emptyList(), trackingFps = 30)
        val at10 = Segmentation.Request("https://x/v.mp4", emptyList(), trackingFps = 10)

        assertEquals(0.5625, at30.estimatedUsd(durationSeconds = 60.0), 1e-6)
        assertEquals(at30.estimatedUsd(60.0) / 3.0, at10.estimatedUsd(60.0), 1e-9)
    }
}

class MaskInterpolationTest {

    private fun mask(index: Int) = Segmentation.MaskFrame(index, 1, 1, byteArrayOf(index.toByte()))

    @Test
    fun `tien fps wordt uitgevouwen naar dertig`() {
        val tracked = listOf(mask(0), mask(1), mask(2))

        val expanded = MaskInterpolation.expand(tracked, targetFrameCount = 9, trackedFps = 10, targetFps = 30)

        assertEquals(9, expanded.size)
        assertEquals((0 until 9).toList(), expanded.map { it.frameIndex })
        // Elke getrackte mask wordt drie doelframes lang vastgehouden.
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2), expanded.map { it.pixels[0].toInt() })
    }

    @Test
    fun `ontbrekende bronframes vallen terug op het dichtstbijzijnde`() {
        val sparse = listOf(mask(0), mask(5))

        val expanded = MaskInterpolation.expand(sparse, targetFrameCount = 6, trackedFps = 1, targetFps = 1)

        assertEquals(6, expanded.size)
        assertTrue(expanded.all { it.pixels.isNotEmpty() })
    }

    @Test
    fun `zonder masks komt er niets uit`() {
        assertEquals(emptyList(), MaskInterpolation.expand(emptyList(), 10, 10, 30))
    }
}

class AutoEditPromptTest {

    private val transcript = Transcript(
        listOf(
            TranscriptSegment(0, 0L, 1_000_000L, "eerste punt"),
            TranscriptSegment(1, 1_000_000L, 2_000_000L, "uitweiding"),
        ),
    )

    @Test
    fun `de prompt bevat het genummerde transcript`() {
        val prompt = AutoEdit.buildPrompt(transcript)

        assertTrue("[0] eerste punt" in prompt)
        assertTrue("[1] uitweiding" in prompt)
    }

    @Test
    fun `de prompt verbiedt expliciet het verzinnen van tijden`() {
        val prompt = AutoEdit.buildPrompt(transcript)
        assertTrue("Verzin geen tijden" in prompt)
    }

    @Test
    fun `een doelduur wordt meegegeven als die er is`() {
        assertTrue("45 seconden" in AutoEdit.buildPrompt(transcript, AutoEdit.Options(targetDurationSeconds = 45)))
        assertTrue("seconden totale speelduur" !in AutoEdit.buildPrompt(transcript))
    }
}

class AutoEditParseTest {

    @Test
    fun `kale json wordt geparseerd`() {
        val selection = AutoEdit.parse("""{"segments": [{"index": 0, "reason": "kern"}]}""")

        assertEquals(listOf(0), selection.indices)
        assertEquals("kern", selection.reasons[0])
    }

    @Test
    fun `json in een codeblok wordt eruit gehaald`() {
        val body = """
            Hier is mijn selectie:
            ```json
            {"segments": [{"index": 2}, {"index": 5}]}
            ```
            Ik heb de herhaling weggelaten.
        """.trimIndent()

        assertEquals(listOf(2, 5), AutoEdit.parse(body).indices)
    }

    @Test
    fun `accolades in strings verstoren het uitsnijden niet`() {
        val body = """{"segments": [{"index": 1, "reason": "hij zei \"{\" hardop"}]}"""
        assertEquals(listOf(1), AutoEdit.parse(body).indices)
    }

    @Test
    fun `onzin levert een lege selectie op in plaats van een gok`() {
        assertEquals(emptyList(), AutoEdit.parse("sorry, dat kan ik niet").indices)
        assertEquals(emptyList(), AutoEdit.parse("{niet eens json").indices)
        assertEquals(emptyList(), AutoEdit.parse("").indices)
    }

    @Test
    fun `een verkeerd getypeerd antwoord levert een lege selectie op`() {
        assertEquals(emptyList(), AutoEdit.parse("""{"segments": "niet een lijst"}""").indices)
    }

    @Test
    fun `het buitenste object wordt genomen bij geneste objecten`() {
        val body = """{"segments": [{"index": 3, "reason": "a"}], "meta": {"model": "x"}}"""
        assertEquals(listOf(3), AutoEdit.parse(body).indices)
    }
}

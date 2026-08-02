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
        assertEquals(2_300_000L, words[1].endUs, "het woord schuift, het krimpt niet")
    }

    @Test
    fun `een woord houdt zijn duur, ook als de correctie groot is`() {
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
        assertEquals(1_800_000L, word.startUs)
        assertEquals(
            100_000L,
            word.endUs - word.startUs,
            "een woord van nul lengte is niet te highlighten en niet aan te wijzen",
        )
    }

    @Test
    fun `twee woorden in dezelfde stilte belanden niet op hetzelfde tijdstip`() {
        val transcript = Transcript(
            listOf(
                TranscriptSegment(
                    index = 0,
                    startUs = 0L,
                    endUs = 2_000_000L,
                    text = "een twee drie",
                    words = listOf(
                        Cue.Word(1_000_000L, 1_100_000L, "een"),
                        Cue.Word(1_200_000L, 1_500_000L, "twee"),
                        Cue.Word(1_900_000L, 2_000_000L, "drie"),
                    ),
                ),
            ),
        )

        val words = TimestampAlignment
            .align(transcript, listOf(TimestampAlignment.Silence(900_000L, 1_800_000L)))
            .segments.single().words

        assertEquals(words.map { it.startUs }.distinct().size, words.size, "woorden vallen samen: $words")
        assertTrue(words.all { it.endUs > it.startUs }, "woord met nul duur: $words")
        assertTrue(
            words.zipWithNext().all { (a, b) -> a.endUs <= b.startUs },
            "volgorde omgegooid: $words",
        )
    }

    @Test
    fun `een verzet woord blijft binnen de grenzen van zijn eigen segment`() {
        val transcript = Transcript(
            listOf(
                TranscriptSegment(0, 0L, 2_000_000L, "laatste", listOf(Cue.Word(1_500_000L, 1_900_000L, "laatste"))),
                TranscriptSegment(
                    index = 1,
                    startUs = 2_000_000L,
                    endUs = 4_000_000L,
                    text = "volgende",
                    words = listOf(Cue.Word(2_100_000L, 2_500_000L, "volgende")),
                ),
            ),
        )

        val aligned = TimestampAlignment.align(
            transcript,
            listOf(TimestampAlignment.Silence(1_400_000L, 3_000_000L)),
        )

        for (segment in aligned.segments) {
            for (word in segment.words) {
                assertTrue(
                    word.startUs >= segment.startUs && word.endUs <= segment.endUs,
                    "woord $word ligt buiten segment ${segment.startUs}..${segment.endUs}",
                )
            }
        }
        val alle = aligned.segments.flatMap { it.words }
        assertTrue(
            alle.zipWithNext().all { (a, b) -> a.endUs <= b.startUs },
            "woorden uit twee segmenten vallen samen: $alle",
        )
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

/**
 * Whisper legt zijn segmentgrenzen op de pauzes. Er zit dus per definitie een gat
 * tussen twee segmenten, en het eerste woord begint routineus een fractie vóór
 * het segment. Die woorden moeten ergens terechtkomen.
 */
class WordAssignmentTest {

    private val body = """
        {
          "text": "dus dat werkt eh prima",
          "segments": [
            {"id": 0, "start": 0.5, "end": 2.0, "text": "dus dat werkt"},
            {"id": 1, "start": 2.6, "end": 4.0, "text": "prima"}
          ],
          "words": [
            {"word": "dus",   "start": 0.48, "end": 0.8},
            {"word": "dat",   "start": 0.9,  "end": 1.2},
            {"word": "werkt", "start": 1.3,  "end": 1.9},
            {"word": "eh",    "start": 2.1,  "end": 2.3},
            {"word": "prima", "start": 2.7,  "end": 3.2}
          ]
        }
    """.trimIndent()

    @Test
    fun `geen enkel woord raakt zoek`() {
        val transcript = Transcription.parse(body)

        val toegewezen = transcript.segments.flatMap { it.words }.map { it.text }
        assertEquals(listOf("dus", "dat", "werkt", "eh", "prima"), toegewezen)
    }

    @Test
    fun `een woord net voor het segment hoort bij dat segment`() {
        val transcript = Transcription.parse(body)

        assertEquals(listOf("dus", "dat", "werkt", "eh"), transcript.segments[0].words.map { it.text })
        assertEquals(listOf("prima"), transcript.segments[1].words.map { it.text })
    }

    @Test
    fun `een woord in het gat gaat naar het dichtstbijzijnde segment`() {
        val gat = """
            {
              "segments": [
                {"id": 0, "start": 0.0, "end": 1.0, "text": "a"},
                {"id": 1, "start": 5.0, "end": 6.0, "text": "b"}
              ],
              "words": [{"word": "x", "start": 4.9, "end": 5.0}]
            }
        """.trimIndent()

        val transcript = Transcription.parse(gat)

        assertEquals(emptyList(), transcript.segments[0].words.map { it.text })
        assertEquals(listOf("x"), transcript.segments[1].words.map { it.text })
    }
}

class TranscriptionSanityTest {

    @Test
    fun `een omgedraaid tijdvak levert geen negatieve duur op`() {
        val transcript = Transcription.parse(
            """{"segments":[{"id":0,"start":5.0,"end":3.0,"text":"a"}]}""",
        )

        val segment = transcript.segments.single()
        assertTrue(segment.endUs >= segment.startUs, "negatieve duur: $segment")
    }

    @Test
    fun `negatieve tijden komen de tijdlijn niet in`() {
        val transcript = Transcription.parse(
            """{"words":[{"word":"a","start":-2.0,"end":0.4}]}""",
        )

        assertTrue(transcript.segments.single().startUs >= 0L)
    }

    @Test
    fun `met een bekende mediaduur wordt een absurd einde geklemd`() {
        val transcript = Transcription.parse(
            """{"segments":[{"id":0,"start":0.0,"end":999999.0,"text":"a"}]}""",
            mediaDurationUs = 60_000_000L,
        )

        assertEquals(60_000_000L, transcript.segments.single().endUs)
    }

    @Test
    fun `een omgedraaide woordenlijst geeft geen segment met negatieve duur`() {
        val transcript = Transcription.parse(
            """{"words":[{"word":"laat","start":3.0,"end":3.4},{"word":"vroeg","start":0.0,"end":0.4}]}""",
        )

        val segment = transcript.segments.single()
        assertEquals(0L, segment.startUs)
        assertEquals(3_400_000L, segment.endUs)
    }
}

class MaskDimensionTest {

    @Test
    fun `afmetingen mogen ook als size-paar komen`() {
        val frames = Segmentation.parse("""{"masks":[{"frame":0,"size":[4,6],"counts":[10,14]}]}""")

        val frame = frames.single()
        assertEquals(6, frame.width)
        assertEquals(4, frame.height)
        assertEquals(24, frame.pixels.size)
    }

    @Test
    fun `een frame met een onmogelijke afmeting valt af zonder de rest mee te nemen`() {
        val frames = Segmentation.parse(
            """{"masks":[
                {"frame":0,"width":-960,"height":540,"counts":[1]},
                {"frame":1,"width":4,"height":4,"counts":[8,8]}
            ]}""",
        )

        assertEquals(listOf(1), frames.map { it.frameIndex }, "één stuk frame nam de hele batch mee")
    }

    @Test
    fun `twee negatieve afmetingen geven geen positief product`() {
        val body = """{"masks":[{"frame":0,"width":-4,"height":-4,"counts":[8,8]}]}"""

        assertEquals(emptyList(), Segmentation.parse(body))
    }

    @Test
    fun `een afmeting die overloopt wordt geweigerd`() {
        val frames = Segmentation.parse("""{"masks":[{"frame":0,"width":65536,"height":65536,"counts":[1]}]}""")

        assertEquals(emptyList(), frames, "65536*65536 loopt over naar exact 0 en ziet er daarna geloofwaardig uit")
    }
}

class AutoEditProseTest {

    @Test
    fun `een accolade in het proza maakt de selectie niet leeg`() {
        val antwoord = """Ik heb "{" laten staan. Hier is het resultaat: {"segments":[{"index":1,"reason":"kern"}]}"""

        val selectie = AutoEdit.parse(antwoord)

        assertEquals(listOf(1), selectie.indices)
    }

    @Test
    fun `zonder bruikbare JSON blijft het leeg`() {
        assertEquals(emptyList(), AutoEdit.parse("Ik kon geen keuze maken { misschien later }").indices)
    }
}

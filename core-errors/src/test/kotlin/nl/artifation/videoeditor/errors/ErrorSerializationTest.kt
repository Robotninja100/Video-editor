package nl.artifation.videoeditor.errors

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = Json

/**
 * Een taak die mislukt blijft met fout en al in de wachtrij staan tot het toestel
 * weer wil. Dan moet die fout een herstart overleven.
 */
class ErrorSerializationTest {

    @Test
    fun `elke variant overleeft een rondje door json`() {
        for (error in ALL_ERRORS) {
            val tekst = json.encodeToString(EditorError.serializer(), error)
            assertEquals(error, json.decodeFromString(EditorError.serializer(), tekst), "voor $tekst")
        }
    }

    @Test
    fun `een geneste fout overleeft het ook`() {
        val error = EditorError.StageFailure(
            Stage.EXPORT,
            EditorError.Multiple(
                listOf(
                    EditorError.FileMissing(SAMPLE_PATH),
                    EditorError.RateLimited(RemoteService.AUTO_EDIT, retryAfterMs = 5_000L),
                ),
            ),
        )

        val tekst = json.encodeToString(EditorError.serializer(), error)

        assertEquals(error, json.decodeFromString(EditorError.serializer(), tekst))
    }

    @Test
    fun `de naam in json is de stabiele code en niet de klassenaam`() {
        val tekst = json.encodeToString(EditorError.serializer(), EditorError.FileMissing(SAMPLE_PATH))

        assertTrue("\"file_missing\"" in tekst, "hernoemen van de klasse mag opgeslagen taken niet breken: $tekst")
        assertTrue("FileMissing" !in tekst, tekst)
    }

    @Test
    fun `afgeleide eigenschappen worden niet opgeslagen`() {
        val tekst = json.encodeToString(
            EditorError.serializer(),
            EditorError.OutOfStorage(requiredBytes = 2L * GB, availableBytes = 0L),
        )

        assertTrue("userMessage" !in tekst, "de tekst hoort bij het scherm, niet bij de opslag: $tekst")
        assertTrue("retryable" !in tekst, tekst)
    }
}

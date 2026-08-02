package nl.artifation.videoeditor.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorLogTest {

    @Test
    fun `elke logregel begint met de code en het herstelvooruitzicht`() {
        for (error in ALL_ERRORS) {
            val regel = ErrorLog.line(error)
            assertTrue(
                regel.startsWith("code=${error.code} retryable=${error.retryable}"),
                "onverwachte regel: $regel",
            )
        }
    }

    @Test
    fun `een logregel is één regel`() {
        val error = EditorError.ServiceFailure(
            RemoteService.AUTO_EDIT,
            statusCode = 500,
            detail = "eerste regel\ntweede regel\n\tderde",
        )

        assertTrue("\n" !in ErrorLog.line(error), "regeleindes in ${ErrorLog.line(error)}")
    }

    @Test
    fun `een logregel bevat de bestandsnaam maar niet het pad`() {
        val regel = ErrorLog.line(EditorError.FileMissing(SAMPLE_PATH))

        assertTrue(SAMPLE_FILE_NAME in regel, regel)
        assertTrue("/storage" !in regel, "mapnamen horen niet in een log: $regel")
    }

    @Test
    fun `geen enkele logregel lekt een pad`() {
        for (error in ALL_ERRORS) {
            assertTrue("/storage" !in ErrorLog.line(error), "pad in ${ErrorLog.line(error)}")
        }
    }

    @Test
    fun `een omhulde fout logt zijn oorzaak mee`() {
        val error = EditorError.StageFailure(Stage.EXPORT, EditorError.Overheated(52))

        val regel = ErrorLog.line(error)

        assertTrue("stage=EXPORT" in regel, regel)
        assertTrue("cause=[code=overheated" in regel, regel)
        assertTrue("celsius=52" in regel, regel)
    }

    @Test
    fun `meerdere fouten worden geteld en uitgeschreven`() {
        val error = EditorError.Multiple(
            listOf(
                EditorError.FileMissing(SAMPLE_PATH),
                EditorError.NetworkUnavailable(RemoteService.AUTO_EDIT),
            ),
        )

        val regel = ErrorLog.line(error)

        assertTrue("count=2" in regel, regel)
        assertTrue("code=file_missing" in regel, regel)
        assertTrue("code=network_unavailable" in regel, regel)
    }

    @Test
    fun `lange details worden afgekapt`() {
        val error = EditorError.ServiceFailure(RemoteService.AUTO_EDIT, 500, detail = "a".repeat(500))

        val regel = ErrorLog.line(error)

        assertTrue(regel.endsWith("..."), "verwacht een afkapping: $regel")
        assertTrue(regel.length < 300, "regel is ${regel.length} tekens lang")
    }

    @Test
    fun `een exceptie draagt de logregel als melding`() {
        val error = EditorError.AuthenticationRejected(RemoteService.TRANSCRIPTION)

        assertEquals(ErrorLog.line(error), EditorException(error).message)
    }
}

class RedactTest {

    @Test
    fun `een bearer-sleutel wordt weggestreept`() {
        val geschoond = ErrorLog.redact("Authorization: Bearer gsk_ABCDEFGH12345678xyz")

        assertTrue("gsk_" !in geschoond, geschoond)
        assertTrue("ABCDEFGH" !in geschoond, geschoond)
        assertTrue("Bearer" in geschoond, "de vorm mag blijven, alleen de sleutel niet: $geschoond")
    }

    @Test
    fun `een sleutel in een parameter wordt weggestreept`() {
        val geschoond = ErrorLog.redact("api_key=abc123geheim&model=whisper")

        assertTrue("abc123geheim" !in geschoond, geschoond)
        assertTrue("whisper" in geschoond, "de rest van de melding blijft leesbaar: $geschoond")
    }

    @Test
    fun `een sleutel in een json-antwoord wordt weggestreept`() {
        val geschoond = ErrorLog.redact("""{"token": "abcdefgh12345678", "model": "whisper"}""")

        assertTrue("abcdefgh12345678" !in geschoond, geschoond)
    }

    @Test
    fun `een losse sleutel met herkenbaar voorvoegsel wordt weggestreept`() {
        val geschoond = ErrorLog.redact("mislukt met sleutel sk-01234567890abcdef in het verzoek")

        assertTrue("01234567890abcdef" !in geschoond, geschoond)
    }

    @Test
    fun `een sleutel in een adres wordt weggestreept`() {
        val geschoond = ErrorLog.redact("https://dienst.example/v1/audio?token=zeergeheim&taal=nl")

        assertTrue("zeergeheim" !in geschoond, geschoond)
        assertTrue("taal=nl" in geschoond, geschoond)
    }

    @Test
    fun `twee keer schonen verandert niets meer`() {
        val ruw = """Authorization: Bearer gsk_ABCDEFGH12345678, api_key=abc123geheim"""

        val eenmaal = ErrorLog.redact(ruw)

        assertEquals(eenmaal, ErrorLog.redact(eenmaal), "schonen hoort idempotent te zijn")
    }

    @Test
    fun `een gewone melding blijft ongemoeid`() {
        val melding = "kon fragment 3 niet lezen op 12,5 seconde"

        assertEquals(melding, ErrorLog.redact(melding))
    }
}

/**
 * De doc van [ErrorLog] belooft twee dingen: geen sleutels en geen volledige
 * paden. De bestaande dekkingstest loopt over [ALL_ERRORS], en geen enkele
 * `detail` daarin bevat een pad — daarom staat dit hier apart, met de tekst
 * zoals de JVM hem daadwerkelijk oplevert.
 */
class LogLeaksNothingTest {

    private val diepPad = "/storage/emulated/0/DCIM/Camera/klant-acme/vakantie.mp4"

    @Test
    fun `het detail van een bestandsfout draagt geen mapnamen`() {
        val error = ErrorMapper.fromThrowable(
            java.io.IOException("$diepPad: open failed: EACCES (Permission denied)"),
            path = diepPad,
        )

        val regel = ErrorLog.line(error!!)
        assertFalse("klant-acme" in regel, "mapnaam in het log: $regel")
        assertFalse(diepPad in regel, "volledig pad in het log: $regel")
        assertTrue("vakantie.mp4" in regel, "de bestandsnaam mag juist wel: $regel")
    }

    @Test
    fun `een stacktrace draagt hem evenmin`() {
        val error = ErrorMapper.fromThrowable(
            java.nio.file.AccessDeniedException(diepPad),
            path = diepPad,
        )

        val melding = EditorException(error!!).message.orEmpty()
        assertFalse("klant-acme" in melding, "mapnaam in de exceptie: $melding")
    }

    @Test
    fun `een sleutel met een underscore ervoor wordt ook weggestreept`() {
        val geredigeerd = ErrorLog.redact("""{"anthropic_api_key":"kaas1234567890"}""")

        assertFalse("kaas1234567890" in geredigeerd, "sleutel bleef staan: $geredigeerd")
    }

    @Test
    fun `de handtekening van een presigned url overleeft niet`() {
        val url = "https://bucket.s3.amazonaws.com/a.mp4" +
            "?X-Amz-Credential=AKIAIOSFODNN7EXAMPLE&X-Amz-Signature=b2c3d4e5f60718293a4b5c6d7e8f90a1"

        val geredigeerd = ErrorLog.redact(url)

        assertFalse("b2c3d4e5f60718293a4b5c6d7e8f90a1" in geredigeerd, "handtekening bleef staan: $geredigeerd")
        assertFalse("AKIAIOSFODNN7EXAMPLE" in geredigeerd, "sleutel-id bleef staan: $geredigeerd")
    }

    @Test
    fun `redigeren blijft idempotent`() {
        val ruw = """{"anthropic_api_key":"kaas1234567890"} en ?X-Amz-Signature=abcdef1234567890"""
        val een = ErrorLog.redact(ruw)

        assertEquals(een, ErrorLog.redact(een))
    }
}

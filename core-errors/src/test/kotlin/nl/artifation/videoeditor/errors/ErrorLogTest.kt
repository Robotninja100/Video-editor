package nl.artifation.videoeditor.errors

import kotlin.test.Test
import kotlin.test.assertEquals
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

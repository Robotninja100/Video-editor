package nl.artifation.videoeditor.errors

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val DIENST = RemoteService.TRANSCRIPTION

class HttpStatusMappingTest {

    @Test
    fun `een geslaagd antwoord is geen fout`() {
        assertNull(ErrorMapper.fromHttpStatus(200, DIENST))
        assertNull(ErrorMapper.fromHttpStatus(204, DIENST))
        assertNull(ErrorMapper.fromHttpStatus(302, DIENST))
    }

    @Test
    fun `te veel verzoeken mag later opnieuw`() {
        val error = ErrorMapper.fromHttpStatus(429, DIENST, retryAfterSeconds = 30L)

        assertEquals(EditorError.RateLimited(DIENST, retryAfterMs = 30_000L), error)
        assertTrue(error!!.retryable)
    }

    @Test
    fun `een dienst die tijdelijk plat ligt mag later opnieuw`() {
        for (status in listOf(500, 502, 503, 504)) {
            val error = ErrorMapper.fromHttpStatus(status, DIENST)
            assertTrue(error!!.retryable, "status $status hoort herhaalbaar te zijn, was $error")
        }
    }

    @Test
    fun `afgewezen toegang wordt niet opnieuw geprobeerd`() {
        for (status in listOf(401, 403)) {
            val error = ErrorMapper.fromHttpStatus(status, DIENST)
            assertEquals(EditorError.AuthenticationRejected(DIENST), error, "status $status")
            assertFalse(error!!.retryable)
        }
    }

    @Test
    fun `een verkeerd verzoek wordt niet opnieuw geprobeerd`() {
        for (status in listOf(400, 404, 422)) {
            val error = ErrorMapper.fromHttpStatus(status, DIENST)
            assertFalse(error!!.retryable, "status $status hoort definitief te zijn, was $error")
        }
    }

    @Test
    fun `een dienst die te lang wacht levert een wachtfout op`() {
        val error = ErrorMapper.fromHttpStatus(408, DIENST)

        assertEquals(EditorError.ServiceTimeout(DIENST), error)
        assertTrue(error!!.retryable)
    }

    @Test
    fun `te groot materiaal is een probleem met de invoer`() {
        val error = ErrorMapper.fromHttpStatus(413, DIENST)

        assertEquals(EditorError.InvalidInput(InputProblem.FILE_TOO_LARGE), error)
    }

    @Test
    fun `de statuscode blijft bewaard voor het log`() {
        val error = ErrorMapper.fromHttpStatus(451, DIENST) as EditorError.ServiceFailure

        assertEquals(451, error.statusCode)
    }

    @Test
    fun `een sleutel in het antwoord haalt de details niet`() {
        val error = ErrorMapper.fromHttpStatus(
            400,
            DIENST,
            body = """{"error":"invalid api_key=gsk_ABCDEFGH12345678"}""",
        ) as EditorError.ServiceFailure

        assertTrue("gsk_" !in error.detail!!, "sleutel in details: ${error.detail}")
    }

    @Test
    fun `een lang antwoord wordt ingekort`() {
        val error = ErrorMapper.fromHttpStatus(500, DIENST, body = "x".repeat(5_000)) as EditorError.ServiceFailure

        assertTrue(error.detail!!.length <= 200, "details zijn ${error.detail!!.length} tekens")
    }

    @Test
    fun `de opgegeven wachttijd wordt in seconden gelezen`() {
        assertEquals(120L, ErrorMapper.parseRetryAfterSeconds("120"))
        assertEquals(0L, ErrorMapper.parseRetryAfterSeconds(" 0 "))
    }

    @Test
    fun `een wachttijd die geen seconden is levert niets op`() {
        assertNull(ErrorMapper.parseRetryAfterSeconds(null))
        assertNull(ErrorMapper.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(ErrorMapper.parseRetryAfterSeconds("-5"))
    }
}

class ThrowableMappingTest {

    @Test
    fun `een verdwenen bestand is definitief`() {
        val error = ErrorMapper.fromThrowable(FileNotFoundException(SAMPLE_PATH), path = SAMPLE_PATH)

        assertEquals(EditorError.FileMissing(SAMPLE_PATH), error)
        assertFalse(error!!.retryable)
    }

    @Test
    fun `zonder pad wordt het pad uit de melding gehaald`() {
        val error = ErrorMapper.fromThrowable(
            FileNotFoundException("$SAMPLE_PATH (No such file or directory)"),
        ) as EditorError.FileMissing

        assertEquals(SAMPLE_PATH, error.path)
    }

    @Test
    fun `een dienst die niet antwoordt mag opnieuw`() {
        val error = ErrorMapper.fromThrowable(SocketTimeoutException("read timed out"), service = DIENST)

        assertEquals(EditorError.ServiceTimeout(DIENST), error)
        assertTrue(error!!.retryable)
    }

    @Test
    fun `een onbereikbare host is een netwerkstoring`() {
        for (throwable in listOf(UnknownHostException("api.groq.com"), ConnectException("connection refused"))) {
            val error = ErrorMapper.fromThrowable(throwable, service = DIENST)
            assertEquals(EditorError.NetworkUnavailable(DIENST), error, "voor $throwable")
            assertTrue(error!!.retryable)
        }
    }

    @Test
    fun `een volle schijf wordt herkend aan de melding van de kernel`() {
        val error = ErrorMapper.fromThrowable(IOException("write failed: ENOSPC (No space left on device)"), path = SAMPLE_PATH)

        assertEquals(EditorError.OutOfStorage(requiredBytes = 0L, availableBytes = 0L), error)
        assertFalse(error!!.retryable, "wachten maakt de schijf niet leger")
    }

    @Test
    fun `een afgebroken bewerking is geen storing`() {
        val error = ErrorMapper.fromThrowable(CancellationException("gebruiker stopte de export"))

        assertEquals(EditorError.Cancelled(), error)
        assertFalse(error!!.retryable)
    }

    @Test
    fun `een leesfout op een bestand is definitief`() {
        val error = ErrorMapper.fromThrowable(IOException("input/output failure"), path = SAMPLE_PATH)
            as? EditorError.FileUnreadable

        assertEquals(SAMPLE_PATH, error?.path, "verwacht een leesfout op het bestand")
        assertFalse(error!!.retryable)
    }

    @Test
    fun `een afgebroken stroom betekent iets anders per kant`() {
        assertTrue(
            ErrorMapper.fromThrowable(EOFException(), path = SAMPLE_PATH) is EditorError.FileUnreadable,
            "een half bestand is stuk",
        )
        assertTrue(
            ErrorMapper.fromThrowable(EOFException(), service = DIENST) is EditorError.NetworkUnavailable,
            "een half antwoord is een verbinding die wegviel",
        )
    }

    @Test
    fun `de oorzaak onder een omhullende exceptie telt`() {
        val diep = RuntimeException("kon niet transcriberen", IllegalStateException("mislukt", UnknownHostException("host")))

        assertEquals(EditorError.NetworkUnavailable(DIENST), ErrorMapper.fromThrowable(diep, service = DIENST))
    }

    @Test
    fun `een al vertaalde fout wordt niet opnieuw geraden`() {
        val origineel = EditorError.Overheated(51)
        val ingepakt = RuntimeException("wrapper", EditorException(origineel))

        assertEquals(origineel, ErrorMapper.fromThrowable(ingepakt))
    }

    @Test
    fun `een exceptie die niets zegt levert geen verzonnen fout op`() {
        assertNull(ErrorMapper.fromThrowable(IllegalStateException("boem")))
        assertNull(
            ErrorMapper.fromThrowable(IOException("iets met bestanden")),
            "zonder pad en zonder dienst valt er niets zinnigs over te zeggen",
        )
    }

    @Test
    fun `een kringetje in de oorzaken loopt niet oneindig door`() {
        val eerste = RuntimeException("eerste")
        val tweede = RuntimeException("tweede")
        eerste.initCause(tweede)
        tweede.initCause(eerste)

        assertNull(ErrorMapper.fromThrowable(eerste))
    }
}

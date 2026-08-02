package nl.artifation.videoeditor.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Woorden die niets te zoeken hebben in een tekst voor de gebruiker.
 *
 * De lijst is opzettelijk streng: hij is er om te voorkomen dat er later een
 * variant bijkomt met "Fout: IOException" erin.
 */
private val JARGON = listOf(
    "exception", "error", "stacktrace", "null", "http", "https", "url", "socket",
    "timeout", "codec", "api", "sdk", "token", "thread", "buffer", "crash", "debug",
    "json", "io", "byte", "bytes", "status", "statuscode", "foutcode", "server",
    "client", "cache", "sorry", "helaas", "excuus", "oeps", "misgegaan", "onbekend",
)

/** Elke tekst moet de gebruiker iets te dóen geven, niet alleen iets te lezen. */
private val ACTIONS = listOf(
    "probeer", "maak", "controleer", "kies", "wacht", "zet", "voeg", "kopieer",
    "start", "begin", "leg", "sleep", "knip", "haal", "gebruik", "open", "verwijder",
)

private fun jargonIn(message: String): List<String> =
    JARGON.filter { Regex("""(?i)\b${Regex.escape(it)}\b""").containsMatchIn(message) }

class UserMessageTest {

    @Test
    fun `elke fout heeft een tekst van betekenis`() {
        for (error in ALL_MESSAGE_CASES) {
            val message = error.userMessage
            assertTrue(
                message.length >= 30,
                "${error.code}: tekst is te kort om iets uit te leggen: \"$message\"",
            )
        }
    }

    @Test
    fun `geen enkele tekst bevat technische termen`() {
        for (error in ALL_MESSAGE_CASES) {
            val gevonden = jargonIn(error.userMessage)
            assertTrue(
                gevonden.isEmpty(),
                "${error.code}: jargon $gevonden in \"${error.userMessage}\"",
            )
        }
    }

    @Test
    fun `elke tekst zegt wat de gebruiker kan doen`() {
        for (error in ALL_MESSAGE_CASES) {
            val message = error.userMessage.lowercase()
            assertTrue(
                ACTIONS.any { it in message },
                "${error.code}: geen handeling voor de gebruiker in \"${error.userMessage}\"",
            )
        }
    }

    @Test
    fun `teksten zijn hele zinnen zonder uitroeptekens`() {
        for (error in ALL_MESSAGE_CASES) {
            val message = error.userMessage
            assertTrue(message.endsWith("."), "${error.code}: geen punt aan het eind: \"$message\"")
            // Een aantal vooraan ("2 onderdelen zijn niet gelukt") mag; een losse
            // bestandsnaam of een technische term vooraan niet.
            assertTrue(
                message.first().isUpperCase() || message.first().isDigit(),
                "${error.code}: begint niet als een zin: \"$message\"",
            )
            assertTrue("!" !in message, "${error.code}: uitroepteken in \"$message\"")
            assertTrue("  " !in message, "${error.code}: dubbele spatie in \"$message\"")
        }
    }

    @Test
    fun `een tekst noemt hooguit de bestandsnaam en nooit het pad`() {
        for (error in ALL_MESSAGE_CASES) {
            assertTrue(
                "/storage" !in error.userMessage,
                "${error.code}: volledig pad in \"${error.userMessage}\"",
            )
        }
    }

    @Test
    fun `een bestandsfout noemt om welk bestand het gaat`() {
        val bestandsfouten = listOf(
            EditorError.FileMissing(SAMPLE_PATH),
            EditorError.FileUnreadable(SAMPLE_PATH),
            EditorError.UnsupportedMedia(SAMPLE_PATH),
        )

        for (error in bestandsfouten) {
            assertTrue(
                SAMPLE_FILE_NAME in error.userMessage,
                "${error.code}: bestandsnaam ontbreekt in \"${error.userMessage}\"",
            )
        }
    }

    @Test
    fun `volle opslag vertelt hoeveel er vrij moet`() {
        val error = EditorError.OutOfStorage(requiredBytes = 3L * GB, availableBytes = 1L * GB)

        assertEquals(
            "Er is geen ruimte meer op je toestel. Maak ongeveer 2 GB vrij en probeer het opnieuw.",
            error.userMessage,
        )
    }

    @Test
    fun `een tekort onder een megabyte krijgt geen exacte belofte`() {
        val error = EditorError.OutOfStorage(requiredBytes = 512L, availableBytes = 0L)

        assertTrue(
            "wat ruimte" in error.userMessage,
            "verwacht een vaag advies, was \"${error.userMessage}\"",
        )
    }

    @Test
    fun `een opgegeven wachttijd komt in minuten in de tekst`() {
        val error = EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = 90_000L)

        assertTrue(
            "2 minuten" in error.userMessage,
            "90 seconden hoort naar boven af te ronden, was \"${error.userMessage}\"",
        )
    }

    @Test
    fun `zonder opgegeven wachttijd blijft de tekst vaag over de duur`() {
        val error = EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = null)

        assertTrue(
            "een paar minuten" in error.userMessage,
            "was \"${error.userMessage}\"",
        )
    }

    @Test
    fun `een omhulde fout vertelt eerst wat er stopte en daarna waarom`() {
        val error = EditorError.StageFailure(Stage.EXPORT, EditorError.Overheated(48))

        assertEquals(
            "Het exporteren van je video is gestopt. Je toestel is te warm geworden om door te werken. " +
                "Leg het even weg en ga over een paar minuten verder.",
            error.userMessage,
        )
    }

    @Test
    fun `bij meerdere fouten wijst de tekst aan waar de gebruiker moet beginnen`() {
        val netwerk = EditorError.NetworkUnavailable(RemoteService.AUTO_EDIT)
        val bestand = EditorError.FileMissing(SAMPLE_PATH)
        val error = EditorError.Multiple(listOf(netwerk, bestand))

        assertTrue(error.userMessage.startsWith("2 onderdelen zijn niet gelukt."), error.userMessage)
        assertTrue(
            bestand.userMessage in error.userMessage,
            "de fout die niet vanzelf overgaat hoort voorop: \"${error.userMessage}\"",
        )
    }

    @Test
    fun `een dienst wordt bij een naam genoemd die een gebruiker begrijpt`() {
        val error = EditorError.NetworkUnavailable(RemoteService.TRANSCRIPTION)

        assertTrue("de ondertiteldienst" in error.userMessage, error.userMessage)
    }
}

class EditorErrorCoverageTest {

    /**
     * Zonder deze test kan iemand een variant toevoegen en de teksttests
     * hierboven ongemerkt overslaan: die lopen immers over een handgeschreven
     * lijst voorbeelden.
     */
    @Test
    fun `van elke variant staat een voorbeeld in de testlijst`() {
        val varianten = EditorError::class.java.permittedSubclasses.orEmpty().map { it.simpleName }.toSet()
        val gedekt = ALL_ERRORS.map { it::class.java.simpleName }.toSet()

        assertTrue(varianten.isNotEmpty(), "geen varianten gevonden; is EditorError nog wel sealed?")
        assertEquals(
            emptySet(),
            varianten - gedekt,
            "deze varianten hebben geen voorbeeld in ALL_ERRORS",
        )
    }

    @Test
    fun `elke variant heeft een eigen code`() {
        val codes = ALL_ERRORS.map { it.code }

        assertEquals(codes.size, codes.toSet().size, "dubbele codes in $codes")
        assertTrue(codes.all { it.isNotBlank() }, "lege code in $codes")
    }
}

class WaitPhrasingTest {

    @Test
    fun `dertig seconden wachten is geen minuut`() {
        val error = ErrorMapper.fromHttpStatus(429, RemoteService.TRANSCRIPTION, retryAfterSeconds = 30L)

        val melding = error!!.userMessage
        assertTrue("30 seconden" in melding, melding)
        assertFalse("minuten" in melding, melding)
    }

    @Test
    fun `nooit 1 minuten`() {
        for (seconden in listOf(1L, 5L, 30L, 59L, 60L, 90L)) {
            val error = ErrorMapper.fromHttpStatus(429, RemoteService.TRANSCRIPTION, retryAfterSeconds = seconden)

            assertFalse("1 minuten" in error!!.userMessage, "bij $seconden s: ${error.userMessage}")
        }
    }
}

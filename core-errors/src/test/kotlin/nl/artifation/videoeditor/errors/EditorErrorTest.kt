package nl.artifation.videoeditor.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetryableTest {

    @Test
    fun `een storing die vanzelf overgaat mag opnieuw`() {
        val herhaalbaar = listOf(
            EditorError.NetworkUnavailable(RemoteService.TRANSCRIPTION),
            EditorError.ServiceTimeout(RemoteService.TRANSCRIPTION),
            EditorError.ServiceFailure(RemoteService.TRANSCRIPTION, statusCode = 503),
            EditorError.RateLimited(RemoteService.TRANSCRIPTION),
            EditorError.Overheated(),
        )

        for (error in herhaalbaar) {
            assertTrue(error.retryable, "${error.code} hoort herhaalbaar te zijn")
        }
    }

    @Test
    fun `een fout die niet vanzelf overgaat wordt niet opnieuw geprobeerd`() {
        val definitief = listOf(
            EditorError.OutOfStorage(1L, 0L),
            EditorError.FileMissing(SAMPLE_PATH),
            EditorError.FileUnreadable(SAMPLE_PATH),
            EditorError.UnsupportedMedia(SAMPLE_PATH),
            EditorError.CodecFailure(Stage.EXPORT),
            EditorError.ServiceFailure(RemoteService.TRANSCRIPTION, statusCode = 400),
            EditorError.AuthenticationRejected(RemoteService.TRANSCRIPTION),
            EditorError.InvalidInput(InputProblem.EMPTY_TIMELINE),
            EditorError.Cancelled(Stage.EXPORT),
        )

        for (error in definitief) {
            assertFalse(error.retryable, "${error.code} hoort niet herhaald te worden")
        }
    }

    @Test
    fun `een foutcode van vijfhonderd mag opnieuw en een van vierhonderd niet`() {
        assertTrue(EditorError.ServiceFailure(RemoteService.AUTO_EDIT, 500).retryable)
        assertTrue(EditorError.ServiceFailure(RemoteService.AUTO_EDIT, 502).retryable)
        assertFalse(EditorError.ServiceFailure(RemoteService.AUTO_EDIT, 404).retryable)
        assertFalse(EditorError.ServiceFailure(RemoteService.AUTO_EDIT, 422).retryable)
    }
}

class StageFailureTest {

    @Test
    fun `een omhulde fout erft of herhalen zin heeft`() {
        val netwerk = EditorError.StageFailure(Stage.TRANSCRIPTION, EditorError.NetworkUnavailable())
        val bestand = EditorError.StageFailure(Stage.EXPORT, EditorError.FileMissing(SAMPLE_PATH))

        assertTrue(netwerk.retryable, "netwerkstoring blijft herhaalbaar door de omhulling heen")
        assertFalse(bestand.retryable, "een verdwenen bestand blijft definitief")
    }

    @Test
    fun `rootCause pelt alle lagen eraf`() {
        val kern = EditorError.Overheated(50)
        val omhuld = EditorError.StageFailure(
            Stage.EXPORT,
            EditorError.StageFailure(Stage.EDIT, kern),
        )

        assertSame(kern, omhuld.rootCause())
    }

    @Test
    fun `een kale fout is zijn eigen oorzaak`() {
        val error = EditorError.Cancelled()

        assertSame(error, error.rootCause())
    }
}

class MultipleTest {

    private val netwerk = EditorError.NetworkUnavailable(RemoteService.AUTO_EDIT)
    private val bestand = EditorError.FileMissing(SAMPLE_PATH)

    @Test
    fun `meerdere fouten zijn alleen samen herhaalbaar`() {
        assertTrue(EditorError.Multiple(listOf(netwerk, EditorError.Overheated())).retryable)
        assertFalse(
            EditorError.Multiple(listOf(netwerk, bestand)).retryable,
            "één definitieve fout maakt de hele taak zinloos om te herhalen",
        )
    }

    @Test
    fun `de eerste definitieve fout is de fout om mee te beginnen`() {
        assertSame(bestand, EditorError.Multiple(listOf(netwerk, bestand)).primary)
    }

    @Test
    fun `zonder definitieve fout is de eerste de fout om mee te beginnen`() {
        val warm = EditorError.Overheated()

        assertSame(netwerk, EditorError.Multiple(listOf(netwerk, warm)).primary)
    }

    @Test
    fun `één fout is geen verzameling`() {
        assertFailsWith<IllegalArgumentException> { EditorError.Multiple(listOf(netwerk)) }
    }

    @Test
    fun `een verzameling in een verzameling wordt geweigerd`() {
        val binnen = EditorError.Multiple(listOf(netwerk, bestand))

        assertFailsWith<IllegalArgumentException> { EditorError.Multiple(listOf(binnen, netwerk)) }
    }
}

class CombineErrorsTest {

    private val netwerk = EditorError.NetworkUnavailable(RemoteService.AUTO_EDIT)
    private val bestand = EditorError.FileMissing(SAMPLE_PATH)

    @Test
    fun `niets levert geen fout op`() {
        assertNull(combineErrors(emptyList()))
    }

    @Test
    fun `één fout blijft die ene fout`() {
        assertEquals(netwerk, combineErrors(listOf(netwerk)))
    }

    @Test
    fun `twee fouten worden er één`() {
        assertEquals(EditorError.Multiple(listOf(netwerk, bestand)), combineErrors(listOf(netwerk, bestand)))
    }

    @Test
    fun `geneste verzamelingen worden afgevlakt`() {
        val warm = EditorError.Overheated()
        val binnen = EditorError.Multiple(listOf(netwerk, bestand))

        val samen = combineErrors(listOf(binnen, warm))

        assertEquals(EditorError.Multiple(listOf(netwerk, bestand, warm)), samen)
    }

    @Test
    fun `dezelfde fout twee keer levert één regel op`() {
        assertEquals(
            netwerk,
            combineErrors(listOf(netwerk, netwerk)),
            "tien clips met dezelfde storing horen niet tien keer op het scherm te komen",
        )
    }
}

class RetryAfterTest {

    @Test
    fun `de wachttijd van de dienst komt door de lagen heen`() {
        val error = EditorError.StageFailure(
            Stage.TRANSCRIPTION,
            EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = 12_000L),
        )

        assertEquals(12_000L, error.retryAfterMs())
    }

    @Test
    fun `bij meerdere wachttijden telt de langste`() {
        val error = EditorError.Multiple(
            listOf(
                EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = 5_000L),
                EditorError.ServiceFailure(RemoteService.AUTO_EDIT, 503, retryAfterMs = 20_000L),
            ),
        )

        assertEquals(20_000L, error.retryAfterMs(), "te vroeg terugkomen levert meteen weer een afwijzing op")
    }

    @Test
    fun `een fout zonder opgegeven wachttijd geeft er geen`() {
        assertNull(EditorError.NetworkUnavailable().retryAfterMs())
        assertNull(EditorError.RateLimited(RemoteService.TRANSCRIPTION).retryAfterMs())
    }
}

class OutOfStorageTest {

    @Test
    fun `het tekort is wat er bij moet`() {
        val error = EditorError.OutOfStorage(requiredBytes = 5L * GB, availableBytes = 2L * GB)

        assertEquals(3L * GB, error.shortfallBytes)
    }

    @Test
    fun `meer ruimte dan nodig levert geen negatief tekort op`() {
        val error = EditorError.OutOfStorage(requiredBytes = 1L * GB, availableBytes = 4L * GB)

        assertEquals(0L, error.shortfallBytes)
    }

    @Test
    fun `negatieve groottes worden geweigerd`() {
        assertFailsWith<IllegalArgumentException> { EditorError.OutOfStorage(-1L, 0L) }
        assertFailsWith<IllegalArgumentException> { EditorError.OutOfStorage(0L, -1L) }
    }
}

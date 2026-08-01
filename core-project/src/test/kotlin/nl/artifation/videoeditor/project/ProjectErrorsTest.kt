package nl.artifation.videoeditor.project

import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import nl.artifation.videoeditor.errors.EditorError
import nl.artifation.videoeditor.errors.RetryPolicy

/**
 * Eén voorbeeld van elke projectfout.
 *
 * De teksttests hieronder lopen over deze lijst; de dekkingstest bewaakt dat er
 * geen soort ontbreekt, zodat een vijfde fout niet stilletjes zonder
 * gebruikerstekst de app in glipt.
 */
private val ALLE_FOUTEN: List<ProjectException> = listOf(
    CorruptProjectException("projectbestand is leeg"),
    UnsupportedSchemaVersionException(fileVersion = 4, supportedVersion = 3),
    ProjectNotFoundException("vakantie"),
    ConcurrentWriteException(id = "vakantie", busyWithId = "kerstfilm"),
)

/** Woorden die niets te zoeken hebben in een tekst voor de gebruiker. */
private val JARGON = listOf(
    "exception", "error", "null", "json", "schema", "schemaversie", "id", "slot",
    "autosave", "decoderen", "parser", "stacktrace", "crash", "sorry", "helaas",
)

/** Elke tekst moet de gebruiker iets te dóen geven, niet alleen iets te lezen. */
private val HANDELINGEN = listOf(
    "probeer", "maak", "controleer", "kies", "wacht", "zet", "voeg", "kopieer",
    "start", "begin", "haal", "open", "verwijder",
)

private fun jargonIn(tekst: String): List<String> =
    JARGON.filter { Regex("""(?i)\b${Regex.escape(it)}\b""").containsMatchIn(tekst) }

class ProjectFoutTekstTest {

    @Test
    fun `elke projectfout heeft een tekst voor de gebruiker`() {
        for (fout in ALLE_FOUTEN) {
            val tekst = fout.userMessage
            assertTrue(
                tekst.length >= 30,
                "${fout.code}: tekst is te kort om iets uit te leggen: \"$tekst\"",
            )
            assertTrue(tekst.endsWith("."), "${fout.code}: geen punt aan het eind: \"$tekst\"")
            assertTrue(tekst.first().isUpperCase(), "${fout.code}: begint niet als een zin: \"$tekst\"")
            assertFalse("!" in tekst, "${fout.code}: uitroepteken in \"$tekst\"")
        }
    }

    @Test
    fun `geen enkele projectfout toont techniek aan de gebruiker`() {
        for (fout in ALLE_FOUTEN) {
            val gevonden = jargonIn(fout.userMessage)
            assertTrue(gevonden.isEmpty(), "${fout.code}: jargon $gevonden in \"${fout.userMessage}\"")
        }
    }

    @Test
    fun `elke projectfout zegt wat de gebruiker kan doen`() {
        for (fout in ALLE_FOUTEN) {
            val tekst = fout.userMessage.lowercase()
            assertTrue(
                HANDELINGEN.any { it in tekst },
                "${fout.code}: geen handeling voor de gebruiker in \"${fout.userMessage}\"",
            )
        }
    }

    @Test
    fun `de ontwikkelaarstekst komt nooit in de gebruikerstekst terecht`() {
        for (fout in ALLE_FOUTEN) {
            assertFalse(
                fout.message!! in fout.userMessage,
                "${fout.code}: ontwikkelaarstekst op het scherm: \"${fout.userMessage}\"",
            )
        }
    }

    @Test
    fun `een projectid is geen tekst voor de gebruiker`() {
        // Een id is een sleutel in de opslag, geen naam die de gebruiker herkent.
        val fouten = listOf(
            ProjectNotFoundException("vakantie"),
            ConcurrentWriteException(id = "vakantie", busyWithId = "kerstfilm"),
        )

        for (fout in fouten) {
            assertFalse(
                "vakantie" in fout.userMessage || "kerstfilm" in fout.userMessage,
                "${fout.code}: id in \"${fout.userMessage}\"",
            )
        }
    }

    @Test
    fun `de tekst noemt het project zonder een bestandsnaam te verzinnen`() {
        assertEquals(
            "Dit project kan niet geopend worden omdat het bestand beschadigd is. " +
                "Open een eerdere versie of begin een nieuw project.",
            CorruptProjectException("projectbestand is leeg").userMessage,
        )
    }
}

class ProjectFoutClassificatieTest {

    @Test
    fun `alleen een botsende schrijfactie is de moeite van opnieuw proberen waard`() {
        // De andere drie lopen bij elke herhaling op precies hetzelfde stuk;
        // een wachtrij die daarop blijft hameren, komt er nooit doorheen.
        val verwacht = mapOf(
            "project_damaged" to false,
            "outdated_app" to false,
            "file_missing" to false,
            "storage_busy" to true,
        )

        for (fout in ALLE_FOUTEN) {
            assertEquals(
                verwacht.getValue(fout.code),
                fout.retryable,
                "${fout.code} (${fout::class.simpleName}) is verkeerd geclassificeerd",
            )
        }
    }

    @Test
    fun `de wachtrij herhaalt een botsende schrijfactie en geeft de rest op`() {
        val beleid = RetryPolicy()

        assertTrue(
            beleid.shouldRetry(ConcurrentWriteException("a", "b").error, attemptsSoFar = 1),
            "een botsing is zo voorbij en hoort opnieuw geprobeerd te worden",
        )
        assertFalse(
            beleid.shouldRetry(UnsupportedSchemaVersionException(4, 3).error, attemptsSoFar = 1),
            "een te nieuw bestand wordt met wachten niet ouder",
        )
    }

    @Test
    fun `stuk en te nieuw houden elk een eigen code`() {
        val stuk = CorruptProjectException("projectbestand is leeg")
        val teNieuw = UnsupportedSchemaVersionException(fileVersion = 4, supportedVersion = 3)

        // Dit is waar deze module om draait: het verschil mag niet verdwijnen in
        // één verzamelfout, ook niet in de telemetrie.
        assertEquals("project_damaged", stuk.code, "code: ${stuk.code}")
        assertEquals("outdated_app", teNieuw.code, "code: ${teNieuw.code}")
    }

    @Test
    fun `elke soort projectfout heeft een voorbeeld in de lijst`() {
        val soorten = ProjectException::class.java.permittedSubclasses.orEmpty().map { it.simpleName }.toSet()
        val gedekt = ALLE_FOUTEN.map { it::class.java.simpleName }.toSet()

        assertTrue(soorten.isNotEmpty(), "geen soorten gevonden; is ProjectException nog wel sealed?")
        assertEquals(emptySet(), soorten - gedekt, "deze soorten hebben geen voorbeeld in ALLE_FOUTEN")
    }

    @Test
    fun `de specifieke velden blijven bestaan naast de fout`() {
        val teNieuw = UnsupportedSchemaVersionException(fileVersion = 4, supportedVersion = 3)
        val botsing = ConcurrentWriteException(id = "vakantie", busyWithId = "kerstfilm")

        assertEquals(4, teNieuw.fileVersion, "fout: ${teNieuw.message}")
        assertEquals(3, teNieuw.supportedVersion, "fout: ${teNieuw.message}")
        assertEquals("vakantie", botsing.id, "fout: ${botsing.message}")
        assertEquals("kerstfilm", botsing.busyWithId, "fout: ${botsing.message}")
        assertEquals("weg", ProjectNotFoundException("weg").id)
    }
}

class ProjectFoutLogTest {

    @Test
    fun `de logregel houdt de ontwikkelaarstekst vast`() {
        val fout = CorruptProjectException("projectbestand is leeg")

        assertEquals(
            "code=project_damaged retryable=false detail=projectbestand is leeg",
            fout.logLine,
        )
    }

    @Test
    fun `de logregel van een te nieuw bestand noemt beide versienummers`() {
        val fout = UnsupportedSchemaVersionException(fileVersion = 4, supportedVersion = 3)

        assertEquals(
            "code=outdated_app retryable=false fileVersion=4 supported=3",
            fout.logLine,
        )
    }

    @Test
    fun `de logregel noemt beide projecten bij een botsende schrijfactie`() {
        val fout = ConcurrentWriteException(id = "vakantie", busyWithId = "kerstfilm")

        assertEquals(
            "code=storage_busy retryable=true detail=bezig met kerstfilm id=vakantie busyWith=kerstfilm",
            fout.logLine,
        )
    }

    @Test
    fun `de logregel noemt om welk project het gaat als het er niet is`() {
        val fout = ProjectNotFoundException("weg")

        assertTrue("id=weg" in fout.logLine, "logregel: ${fout.logLine}")
    }

    @Test
    fun `een sleutel uit een parserfout haalt de logregel niet`() {
        // Een logregel wordt geplakt in een issue; wat er in het bestand stond,
        // hoort daar niet ongeschonden in terug te komen.
        val fout = CorruptProjectException("kop is stuk: token=abc123def456xyz")

        assertTrue("token=***" in fout.logLine, "logregel: ${fout.logLine}")
        assertFalse("abc123def456xyz" in fout.logLine, "logregel: ${fout.logLine}")
    }

    @Test
    fun `een uitgelopen ontwikkelaarstekst wordt afgekapt`() {
        val fout = CorruptProjectException("kop is stuk: " + "x".repeat(500))

        assertTrue(fout.logLine.endsWith("..."), "logregel: ${fout.logLine}")
        assertTrue(fout.logLine.length < 200, "logregel is een regel, geen dump: ${fout.logLine.length}")
    }
}

class EditorErrorBrugTest {

    @Test
    fun `een projectfout levert zijn eigen fout op`() {
        val fout = CorruptProjectException("projectbestand is leeg")

        assertSame(fout.error, editorErrorOf(fout))
    }

    @Test
    fun `een verpakte projectfout wordt alsnog gevonden`() {
        // Zo komt hij binnen bij een aanroeper die zelf ook nog iets omhult.
        val fout = ProjectNotFoundException("weg")

        assertSame(fout.error, editorErrorOf(IllegalStateException("openen mislukt", fout)))
    }

    @Test
    fun `een gewone bestandsfout gaat naar de vertaler van core-errors`() {
        val vertaald = editorErrorOf(FileNotFoundException("/opslag/projecten/p1.json"))

        assertIs<EditorError.FileMissing>(vertaald, "vertaald: $vertaald")
    }

    @Test
    fun `een fout die niemand kan duiden krijgt geen verzonnen tekst`() {
        assertNull(
            editorErrorOf(IllegalStateException("iets onverwachts")),
            "liever geen tekst dan een verkeerde",
        )
    }
}

class ProjectFoutInDePraktijkTest {

    private val store = InMemoryProjectStore()

    @Test
    fun `een leeg bestand komt als leesbare fout naar boven`() {
        store.writeRaw("p1", ProjectSlot.MAIN, "")

        val fout = assertFailsWith<CorruptProjectException> { store.load("p1") }

        assertEquals("project_damaged", fout.code, "fout: ${fout.logLine}")
        assertFalse(fout.retryable, "een leeg bestand vult zichzelf niet aan")
        assertTrue(fout.userMessage.startsWith("Dit project"), "tekst: ${fout.userMessage}")
    }

    @Test
    fun `een bestand uit een nieuwere app krijgt zijn eigen fout`() {
        store.writeRaw(
            "p1",
            ProjectSlot.MAIN,
            metSchemaVersie(ProjectCodec.encode(voorbeeldBestand()), CURRENT_SCHEMA_VERSION + 1),
        )

        val fout = assertFailsWith<UnsupportedSchemaVersionException> { store.load("p1") }

        assertEquals("outdated_app", fout.code, "fout: ${fout.logLine}")
        assertEquals(CURRENT_SCHEMA_VERSION + 1, fout.fileVersion, "fout: ${fout.message}")
    }

    @Test
    fun `een onherstelbaar project levert een fout met een tekst op`() {
        store.writeRaw("p1", ProjectSlot.MAIN, "{ kapot")
        store.writeRaw("p1", ProjectSlot.AUTOSAVE, "")

        val plan = CrashRecovery.inspect(store, "p1")

        assertIs<RecoveryPlan.Unrecoverable>(plan, "plan: $plan")
        val fout = plan.asException()
        assertTrue(plan.detail in fout.message!!, "de reden hoort in de ontwikkelaarstekst: ${fout.message}")
        assertFalse(plan.detail in fout.userMessage, "tekst: ${fout.userMessage}")
        assertEquals("project_damaged", fout.code, "fout: ${fout.logLine}")
    }
}

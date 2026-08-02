package nl.artifation.videoeditor.errors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FormatBytesTest {

    @Test
    fun `hele gigabytes krijgen geen komma`() {
        assertEquals("2 GB", formatBytes(2L * GB))
    }

    @Test
    fun `gigabytes krijgen hoogstens één decimaal`() {
        assertEquals("1,5 GB", formatBytes(GB + GB / 2))
    }

    @Test
    fun `megabytes worden naar boven afgerond`() {
        assertEquals("2 MB", formatBytes(MB + 1L), "te weinig vragen is erger dan te veel vragen")
        assertEquals("700 MB", formatBytes(700L * MB))
    }

    @Test
    fun `kleine hoeveelheden krijgen geen loze precisie`() {
        assertEquals("minder dan 1 kB", formatBytes(0L))
        assertEquals("minder dan 1 kB", formatBytes(-1L))
        assertEquals("1 kB", formatBytes(KB))
    }
}

class FileNameTest {

    @Test
    fun `van een pad blijft alleen de bestandsnaam over`() {
        assertEquals("clip.mp4", fileNameOf("/storage/emulated/0/DCIM/clip.mp4"))
    }

    @Test
    fun `een pad met backslashes werkt ook`() {
        assertEquals("clip.mp4", fileNameOf("""C:\Users\iemand\clip.mp4"""))
    }

    @Test
    fun `een naam zonder pad blijft zoals hij is`() {
        assertEquals("clip.mp4", fileNameOf("clip.mp4"))
    }

    @Test
    fun `een leeg pad levert een leesbare vervanging op`() {
        assertEquals("het bestand", fileNameOf(""))
        assertEquals("het bestand", fileNameOf("/"))
    }
}

class MinutesTest {

    @Test
    fun `wachttijden worden naar boven afgerond op minuten`() {
        assertEquals(2L, minutesRoundedUp(90_000L))
        assertEquals(1L, minutesRoundedUp(60_000L))
    }

    @Test
    fun `wacht nooit nul minuten`() {
        assertEquals(1L, minutesRoundedUp(0L), "\"wacht 0 minuten\" is geen advies")
        assertEquals(1L, minutesRoundedUp(1L))
    }
}

class HumanWaitTest {

    @Test
    fun `onder een minuut telt het in seconden`() {
        assertEquals("30 seconden", humanWait(30_000L))
        assertEquals("45 seconden", humanWait(45_000L))
        assertEquals("59 seconden", humanWait(59_000L))
    }

    @Test
    fun `een minuut is enkelvoud`() {
        assertEquals("1 minuut", humanWait(60_000L))
        assertEquals("1 seconde", humanWait(1L), "en een seconde ook")
    }

    @Test
    fun `daarboven hele minuten naar boven afgerond`() {
        assertEquals("2 minuten", humanWait(90_000L))
        assertEquals("5 minuten", humanWait(300_000L))
    }
}

class ShortenPathsTest {

    @Test
    fun `een absoluut pad blijft alleen als bestandsnaam over`() {
        val melding = "/storage/emulated/0/DCIM/Camera/klant-acme/vakantie.mp4: open failed: EACCES"

        assertEquals("vakantie.mp4: open failed: EACCES", shortenPaths(melding))
    }

    @Test
    fun `een content-uri houdt zijn soort maar niet zijn mappen`() {
        val kort = shortenPaths("kon content://com.android.providers.media.documents/document/video%3A42 niet openen")

        assertEquals("kon content://…/video%3A42 niet openen", kort)
    }

    @Test
    fun `een gecodeerde padscheiding verbergt de mapnaam niet`() {
        val kort = shortenPaths("file:///storage/emulated/0/Documents%2Fklant-acme%2Fgeheim.mp4")

        assertFalse("klant-acme" in kort, "de mapnaam lekt alsnog: $kort")
    }

    @Test
    fun `een https-adres houdt zijn host`() {
        val melding = "POST https://api.groq.com/openai/v1/audio/transcriptions gaf 503"

        assertEquals(melding, shortenPaths(melding), "het pad van een endpoint is juist de nuttige informatie")
    }

    @Test
    fun `een melding zonder pad blijft ongemoeid`() {
        val melding = "write failed: ENOSPC (No space left on device)"

        assertEquals(melding, shortenPaths(melding))
    }

    @Test
    fun `nog een keer inkorten verandert niets meer`() {
        val een = shortenPaths("/storage/emulated/0/DCIM/vakantie.mp4 kan niet gelezen worden")

        assertEquals(een, shortenPaths(een))
    }
}

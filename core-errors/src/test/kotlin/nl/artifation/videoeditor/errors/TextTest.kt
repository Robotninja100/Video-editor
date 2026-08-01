package nl.artifation.videoeditor.errors

import kotlin.test.Test
import kotlin.test.assertEquals

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

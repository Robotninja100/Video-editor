package nl.artifation.videoeditor.errors

/**
 * Tekstgereedschap voor de meldingen.
 *
 * Bewust geen `String.format` en geen `Locale`: die maken de uitvoer afhankelijk
 * van de instellingen van het toestel, en dan zijn de teksten niet meer te
 * testen. Alles hier is puur rekenwerk op getallen en strings.
 */

internal const val KB: Long = 1024L
internal const val MB: Long = 1024L * KB
internal const val GB: Long = 1024L * MB

/** Eén cijfer achter de komma, gerekend in tienden. */
private const val TENTHS: Long = 10L

private const val MS_PER_SECOND: Long = 1_000L
private const val MS_PER_MINUTE: Long = 60_000L

/** Deelt naar boven af — bij "maak ruimte vrij" is te weinig vragen erger dan te veel. */
private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor

/**
 * Menselijke maat voor een hoeveelheid opslag: "2,5 GB", "700 MB".
 *
 * Nederlandse notatie met een komma, en nooit meer dan één decimaal — een
 * gebruiker die ruimte moet vrijmaken heeft niets aan drie cijfers achter de
 * komma.
 */
internal fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= GB -> {
            val tenths = ceilDiv(safe * TENTHS, GB)
            val heel = tenths / TENTHS
            val rest = tenths % TENTHS
            if (rest == 0L) "$heel GB" else "$heel,$rest GB"
        }
        safe >= MB -> "${ceilDiv(safe, MB)} MB"
        safe >= KB -> "${ceilDiv(safe, KB)} kB"
        else -> "minder dan 1 kB"
    }
}

/**
 * Alleen de bestandsnaam, nooit het volledige pad.
 *
 * Een pad bevat vaak een mapnaam met de naam van de gebruiker of van een klant.
 * Dat hoort niet in een melding en al helemaal niet in een logregel.
 */
internal fun fileNameOf(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return "het bestand"
    val cut = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
    val name = if (cut >= 0) trimmed.substring(cut + 1) else trimmed
    return name.ifEmpty { "het bestand" }
}

/** Zin met een hoofdletter beginnen, zonder de rest aan te raken. */
internal fun String.sentenceStart(): String = replaceFirstChar { it.uppercaseChar() }

/** Zelfde label midden in een zin. */
internal fun String.midSentence(): String = replaceFirstChar { it.lowercaseChar() }

/** Naar boven afgeronde minuten; minimaal één, want "wacht 0 minuten" is geen advies. */
internal fun minutesRoundedUp(millis: Long): Long =
    ceilDiv(millis.coerceAtLeast(1L), MS_PER_MINUTE).coerceAtLeast(1L)

/**
 * Wachttijd in gewone taal: "45 seconden", "1 minuut", "3 minuten".
 *
 * Onder een minuut telt het in seconden. Naar boven afronden op hele minuten
 * gaf "wacht ongeveer 1 minuten" — fout Nederlands, en ook nog eens het dubbele
 * van wat de dienst vroeg, terwijl 30 s de meest voorkomende `Retry-After` is.
 */
internal fun humanWait(millis: Long): String {
    val safe = millis.coerceAtLeast(1L)
    if (safe < MS_PER_MINUTE) {
        val seconds = ceilDiv(safe, MS_PER_SECOND).coerceAtLeast(1L)
        return if (seconds == 1L) "1 seconde" else "$seconds seconden"
    }
    val minutes = minutesRoundedUp(safe)
    return if (minutes == 1L) "1 minuut" else "$minutes minuten"
}

/**
 * Vervangt elk pad door alleen de bestandsnaam.
 *
 * De tekst van een JVM-exceptie begint bij een bestandsfout steevast met het
 * volledige pad, en die tekst komt via `detail` in de logregel en in
 * [EditorException]. Precies wat volgens de doc van [ErrorLog] nooit in een log
 * hoort: de mapnamen zeggen niets over de fout en veel over de gebruiker.
 *
 * Alleen `content://` en `file://` worden ingekort. Een https-adres houdt zijn
 * host, want daar is het pad juist het bruikbare deel van de melding.
 */
internal fun shortenPaths(text: String): String {
    val zonderUris = LOCAL_URI.replace(text) { match ->
        "${match.groupValues[1]}://…/" + fileNameOf(match.value.replace("%2F", "/").replace("%2f", "/"))
    }
    return ABSOLUTE_PATH.replace(zonderUris) { match -> fileNameOf(match.value) }
}

private val LOCAL_URI = Regex("""(?i)\b(content|file)://[^\s,;"'>]+""")

/**
 * Een absoluut pad van minstens twee stukken.
 *
 * De terugblik houdt het pad van een adres erbuiten. Zonder de `/` erin begon
 * een match bij de tweede schuine streep van `https://` en bleef er
 * `https:/transcriptions` over; zonder de `:` en de letters begon hij midden in
 * de host.
 */
private val ABSOLUTE_PATH = Regex("""(?<![\w:%/])(?:/[^/\s,;"'<>|?*]+){2,}""")

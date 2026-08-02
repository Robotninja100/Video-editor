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

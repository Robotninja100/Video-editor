package nl.artifation.videoeditor.library

import kotlinx.serialization.Serializable
import nl.artifation.videoeditor.model.Problem
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us
import java.security.MessageDigest

/** Beeldverhouding als categorie, want de UI groepeert hierop en niet op ruwe pixels. */
public enum class Orientation { PORTRAIT, LANDSCAPE, SQUARE }

/**
 * Eén stuk bronmateriaal in de bibliotheek.
 *
 * Puur metadata: de bytes blijven waar ze staan. Deze module leest of kopieert
 * nooit mediabestanden, zodat hij zonder toestel te testen is.
 */
@Serializable
public data class MediaAsset(
    /** Inhoudsidentiteit, zie [MediaIdentity]. Ook het mapnaam-segment van de sidecars. */
    val id: String,
    /** Bewust een String en geen `android.net.Uri`, zodat deze module pure JVM blijft. */
    val uri: String,
    val displayName: String,
    val durationUs: Us,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val hasAudio: Boolean,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    /**
     * Wijzigingsstempel van het bronbestand, in de praktijk de mtime in ms.
     *
     * Sidecars leggen deze waarde vast op het moment van analyse. Verandert het
     * bronbestand terwijl [id] gelijk blijft — een her-encode op dezelfde plek —
     * dan is dat hieraan te zien en zijn de sidecars ongeldig.
     */
    val sourceRevision: Long = 0L,
    /** Sterke hash over de inhoud, als de importer die kon berekenen. Zie [MediaIdentity.of]. */
    val contentHash: String? = null,
) {
    val durationSeconds: Double get() = durationUs.toDouble() / US_PER_SECOND

    val aspect: Float get() = if (height == 0) 0f else width.toFloat() / height.toFloat()

    val orientation: Orientation
        get() = when {
            width > height -> Orientation.LANDSCAPE
            width < height -> Orientation.PORTRAIT
            else -> Orientation.SQUARE
        }

    public companion object {

        /** Maakt een asset met een [id] die uit de inhoud volgt in plaats van uit de uri. */
        public fun create(
            uri: String,
            displayName: String,
            durationUs: Us,
            width: Int,
            height: Int,
            frameRate: Float = 30f,
            hasAudio: Boolean = true,
            sizeBytes: Long = 0L,
            addedAtEpochMs: Long = 0L,
            sourceRevision: Long = 0L,
            contentHash: String? = null,
        ): MediaAsset = MediaAsset(
            id = MediaIdentity.of(sizeBytes, durationUs, contentHash, uriFallback = uri),
            uri = uri,
            displayName = displayName,
            durationUs = durationUs,
            width = width,
            height = height,
            frameRate = frameRate,
            hasAudio = hasAudio,
            sizeBytes = sizeBytes,
            addedAtEpochMs = addedAtEpochMs,
            sourceRevision = sourceRevision,
            contentHash = contentHash,
        )
    }
}

/**
 * Identiteit van bronmateriaal, afgeleid uit de inhoud.
 *
 * De uri is hiervoor onbruikbaar: de documentkiezer van Android geeft per keuze
 * een nieuwe `content://`-uri voor hetzelfde bestand. Wie tweemaal dezelfde
 * opname kiest, zou anders twee assets krijgen — en dus alle analyses dubbel
 * betalen.
 */
public object MediaIdentity {

    /** Genoeg tegen toevallige botsingen, kort genoeg om als mapnaam leesbaar te blijven. */
    private const val ID_LENGTH = 16

    private const val SCHEMA = "v1"

    /**
     * Een aanwezige [contentHash] is doorslaggevend; alleen zonder hash vallen we
     * terug op grootte plus duur. De twee worden nooit gecombineerd, want dan
     * zouden twee importers — één met en één zonder hash — een ander id afgeven
     * voor hetzelfde bestand.
     *
     * Zonder hash **en** zonder grootte blijft alleen de duur over, en dat is
     * geen identiteit: twee filmpjes van tien seconden kregen dan hetzelfde id,
     * waarna het tweede nooit in de bibliotheek kwam en het transcript van het
     * eerste voorgeschoteld kreeg. Niet elke `content://`-provider levert
     * `OpenableColumns.SIZE`, dus dat geval is echt. In dat geval valt de
     * identiteit terug op [uriFallback] — twee keer hetzelfde bestand kiezen
     * geeft dan twee assets, en dat is de goede kant om fout te zitten.
     */
    public fun of(
        sizeBytes: Long,
        durationUs: Us,
        contentHash: String? = null,
        uriFallback: String? = null,
    ): String {
        val key = when {
            !contentHash.isNullOrBlank() -> "$SCHEMA|hash|$contentHash"
            sizeBytes > 0L -> "$SCHEMA|stat|$sizeBytes|$durationUs"
            else -> "$SCHEMA|uri|${uriFallback.orEmpty()}|$durationUs"
        }
        return digest(key)
    }

    public fun of(asset: MediaAsset): String =
        of(asset.sizeBytes, asset.durationUs, asset.contentHash, asset.uri)

    private fun digest(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(ID_LENGTH)
        for (index in 0 until (ID_LENGTH + 1) / 2) {
            val value = bytes[index].toInt() and BYTE_MASK
            hex.append(HEX[value ushr NIBBLE_BITS]).append(HEX[value and NIBBLE_MASK])
        }
        return hex.substring(0, ID_LENGTH)
    }

    private const val HEX = "0123456789abcdef"
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
}

/**
 * Metadatavalidatie bij import.
 *
 * Hergebruikt [Problem] uit `:core-model`, zodat de UI één foutenlijstje kent
 * voor project- én bibliotheekproblemen.
 */
public fun MediaAsset.validate(path: String = "asset"): List<Problem> = buildList {
    if (id.isBlank()) add(Problem("$path.id", "id mag niet leeg zijn"))
    if (uri.isBlank()) add(Problem("$path.uri", "uri mag niet leeg zijn"))
    if (durationUs <= 0L) add(Problem("$path.durationUs", "duur moet positief zijn, was $durationUs"))
    if (width <= 0) add(Problem("$path.width", "width moet positief zijn, was $width"))
    if (height <= 0) add(Problem("$path.height", "height moet positief zijn, was $height"))
    if (frameRate <= 0f || !frameRate.isFinite()) {
        add(Problem("$path.frameRate", "frameRate moet positief en eindig zijn, was $frameRate"))
    }
    if (sizeBytes < 0L) add(Problem("$path.sizeBytes", "sizeBytes mag niet negatief zijn, was $sizeBytes"))
}

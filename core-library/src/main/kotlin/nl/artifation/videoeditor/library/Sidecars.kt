package nl.artifation.videoeditor.library

import kotlinx.serialization.Serializable

/**
 * De analyses die naast het bronmateriaal kunnen liggen.
 *
 * [currentVersion] is de versie die de app nú produceert. Draait er later een
 * beter model, dan gaat dit getal omhoog en meldt [SidecarIndex] dat bestaande
 * sidecars opnieuw moeten — zonder dat de montage wordt aangeraakt.
 */
public enum class AnalysisKind(
    public val fileName: String,
    public val currentVersion: Int,
) {
    TRANSCRIPT("transcript.json", 1),
    SILENCES("silences.json", 1),
    SCENES("scenes.json", 1),
    SUBJECTS("subjects.json", 1),
}

/**
 * De analyseversies die op dit moment gelden.
 *
 * Bestaat als los object zodat een test — of een experiment in de app — een
 * versie kan ophogen zonder de enum aan te passen.
 */
public data class AnalysisVersions(
    val overrides: Map<AnalysisKind, Int> = emptyMap(),
) {
    public fun of(kind: AnalysisKind): Int = overrides[kind] ?: kind.currentVersion

    public fun bumpedTo(kind: AnalysisKind, version: Int): AnalysisVersions =
        copy(overrides = overrides + (kind to version))
}

/**
 * Padberekening voor sidecars. Puur tekst: deze klasse raakt geen bestandssysteem.
 *
 * Layout: `.cache/<assetId>/transcript.json`, `silences.json`, `scenes.json`,
 * `subjects.json` en `masks_<n>.mp4`. Eén map per asset, want dan is opruimen
 * één map verwijderen, en horen de sidecars bij de bronclip in plaats van bij
 * een tijdlijnpositie.
 */
public object SidecarPaths {

    public const val CACHE_DIR: String = ".cache"

    /** `.cache/<assetId>/<bestand>` — precies drie stukken, niet meer of minder. */
    private const val PATH_SEGMENTS: Int = 3

    private const val MASK_PREFIX = "masks_"
    private const val MASK_SUFFIX = ".mp4"

    public fun directory(assetId: String): String {
        requireSafe(assetId)
        return "$CACHE_DIR/$assetId"
    }

    public fun of(assetId: String, kind: AnalysisKind): String =
        "${directory(assetId)}/${kind.fileName}"

    /** Maskvideo per gevolgd onderwerp; [index] loopt gelijk op met `subjects.json`. */
    public fun mask(assetId: String, index: Int): String {
        require(index >= 0) { "mask-index moet >= 0 zijn, was $index" }
        return "${directory(assetId)}/$MASK_PREFIX$index$MASK_SUFFIX"
    }

    public fun all(assetId: String): Map<AnalysisKind, String> =
        AnalysisKind.entries.associateWith { of(assetId, it) }

    /** Het asset waar een pad bij hoort, of null als het pad niet in de cache ligt. */
    public fun assetIdOf(path: String): String? {
        val parts = path.split('/')
        if (parts.size != PATH_SEGMENTS || parts[0] != CACHE_DIR) return null
        return parts[1].takeIf { it.isNotBlank() }
    }

    public fun kindOf(path: String): AnalysisKind? {
        if (assetIdOf(path) == null) return null
        val fileName = path.substringAfterLast('/')
        return AnalysisKind.entries.firstOrNull { it.fileName == fileName }
    }

    public fun maskIndexOf(path: String): Int? {
        if (assetIdOf(path) == null) return null
        val fileName = path.substringAfterLast('/')
        if (!fileName.startsWith(MASK_PREFIX) || !fileName.endsWith(MASK_SUFFIX)) return null
        return fileName.removePrefix(MASK_PREFIX).removeSuffix(MASK_SUFFIX).toIntOrNull()
    }

    /** Een id belandt als mapnaam op schijf; padtekens zouden buiten de cache schrijven. */
    private fun requireSafe(assetId: String) {
        require(assetId.isNotBlank()) { "assetId mag niet leeg zijn" }
        require(!assetId.contains('/') && !assetId.contains('\\')) {
            "assetId mag geen padscheiding bevatten, was '$assetId'"
        }
        require(assetId != "." && assetId != "..") { "assetId mag geen relatief pad zijn" }
    }
}

/**
 * Wat er over een analyse is vastgelegd.
 *
 * [payload] is de serialisatie van het analyseresultaat en blijft voor deze
 * module ondoorzichtig: de bibliotheek weet wélke analyses er zijn, niet wat er
 * in staat. Voor maskvideo's is [payload] leeg — daar beschrijft het record
 * alleen dat het bestand bestaat.
 */
@Serializable
public data class SidecarRecord(
    val assetId: String,
    val kind: AnalysisKind,
    val analysisVersion: Int,
    /** [MediaAsset.sourceRevision] op het moment van analyse. */
    val sourceRevision: Long,
    val payload: String = "",
    val writtenAtEpochMs: Long = 0L,
)

/**
 * Opslag van sidecars, geadresseerd op pad.
 *
 * Achter een interface omdat de echte implementatie op Android bestanden en
 * SAF-uri's gebruikt, en de tests dat niet nodig hebben.
 */
public interface SidecarStorage {
    public fun read(path: String): SidecarRecord?
    public fun write(path: String, record: SidecarRecord)
    public fun delete(path: String): Boolean

    /** Alle bekende paden, in een stabiele volgorde. */
    public fun paths(): List<String>
    public fun exists(path: String): Boolean = read(path) != null
}

/** In-memory implementatie voor tests; gesorteerd, zodat opsommingen deterministisch zijn. */
public class InMemorySidecarStorage(
    initial: Map<String, SidecarRecord> = emptyMap(),
) : SidecarStorage {

    private val records: MutableMap<String, SidecarRecord> = initial.toMutableMap()

    override fun read(path: String): SidecarRecord? = records[path]

    override fun write(path: String, record: SidecarRecord) {
        records[path] = record
    }

    override fun delete(path: String): Boolean = records.remove(path) != null

    override fun paths(): List<String> = records.keys.sorted()

    public val size: Int get() = records.size
}

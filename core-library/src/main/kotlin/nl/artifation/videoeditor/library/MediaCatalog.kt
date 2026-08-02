package nl.artifation.videoeditor.library

import kotlinx.serialization.Serializable
import nl.artifation.videoeditor.model.Us

public enum class MediaSort { NAME, DURATION, ADDED }

/** Filter voor de bibliotheekweergave; een veld op `null` betekent "maakt niet uit". */
public data class MediaFilter(
    val query: String? = null,
    val minDurationUs: Us? = null,
    val maxDurationUs: Us? = null,
    val hasAudio: Boolean? = null,
    val orientation: Orientation? = null,
) {
    /** Een niet-ingevuld criterium filtert niet; alle ingevulde moeten kloppen. */
    public fun matches(asset: MediaAsset): Boolean =
        (query == null || asset.matchesQuery(query)) &&
            (minDurationUs == null || asset.durationUs >= minDurationUs) &&
            (maxDurationUs == null || asset.durationUs <= maxDurationUs) &&
            (hasAudio == null || asset.hasAudio == hasAudio) &&
            (orientation == null || asset.orientation == orientation)
}

private fun MediaAsset.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    return needle.isEmpty() || displayName.lowercase().contains(needle)
}

/**
 * De verzameling geïmporteerd bronmateriaal.
 *
 * Onveranderlijk, net als [nl.artifation.videoeditor.model.Project]: elke
 * bewerking geeft een nieuwe catalogus terug. Dat maakt undo triviaal en
 * voorkomt dat de UI-thread en een analyse-worker elkaars lijst onder de voeten
 * wegtrekken.
 */
@Serializable
public data class MediaCatalog(
    /** In importvolgorde; [sorted] levert de weergavevolgordes. */
    val assets: List<MediaAsset> = emptyList(),
    /**
     * Extra uri's die naar een bestaand asset wijzen.
     *
     * Ontstaat wanneer hetzelfde bestand nogmaals wordt geïmporteerd via een
     * andere uri. Zonder deze index zou een project dat naar die tweede uri
     * verwijst nergens meer op uitkomen.
     */
    val aliases: Map<String, String> = emptyMap(),
) {
    private val byId: Map<String, MediaAsset> = assets.associateBy { it.id }

    private val uriIndex: Map<String, String> = buildMap {
        putAll(aliases)
        // Later geïmporteerd materiaal wint: die uri wijst nu naar de nieuwe inhoud.
        assets.forEach { put(it.uri, it.id) }
    }

    val size: Int get() = assets.size

    val isEmpty: Boolean get() = assets.isEmpty()

    val totalDurationUs: Us get() = assets.sumOf { it.durationUs }

    val totalSizeBytes: Long get() = assets.sumOf { it.sizeBytes }

    public fun get(id: String): MediaAsset? = byId[id]

    public fun contains(id: String): Boolean = byId.containsKey(id)

    /** Zoekt het asset achter een uri, inclusief de alias-uri's van dubbele imports. */
    public fun byUri(uri: String): MediaAsset? = uriIndex[uri]?.let(byId::get)

    /**
     * Alle uri's waaronder een asset bekend is: zijn eigen uri plus de aliassen.
     *
     * Nodig zodra je wilt weten of een bestand nog te bereiken is. Android geeft
     * per keuze een nieuwe `content://`-uri — dat is precies waarom de aliassen
     * bestaan — dus alleen [MediaAsset.uri] toetsen zegt niets als die uri de
     * verlopen eerste is.
     */
    public fun urisOf(id: String): Set<String> = buildSet {
        byId[id]?.let { add(it.uri) }
        aliases.forEach { (uri, target) -> if (target == id) add(uri) }
    }

    /**
     * Voegt toe, of houdt het bestaande asset als de inhoud al bekend is. In dat
     * tweede geval blijft [MediaAsset.addedAtEpochMs] van de eerste import staan
     * en wordt alleen de nieuwe uri als alias onthouden.
     */
    public fun add(asset: MediaAsset): MediaCatalog {
        // Een alias op deze uri gaat over ander materiaal en is achterhaald zodra
        // er nieuwe inhoud op diezelfde uri staat. Bleef hij staan, dan dook het
        // oude asset weer op zodra het nieuwe verwijderd werd — en speelde een
        // clip die naar die uri verwijst opeens andere beelden af.
        val existing = byId[asset.id]
            ?: return copy(assets = assets + asset, aliases = aliases - asset.uri)
        if (existing.uri == asset.uri || aliases[asset.uri] == asset.id) return this
        return copy(aliases = aliases + (asset.uri to existing.id))
    }

    public fun addAll(newAssets: Iterable<MediaAsset>): MediaCatalog =
        newAssets.fold(this) { catalog, asset -> catalog.add(asset) }

    /** Verwijdert het asset en al zijn alias-uri's; onbekende id's veranderen niets. */
    public fun remove(id: String): MediaCatalog {
        if (!contains(id)) return this
        return MediaCatalog(
            assets = assets.filterNot { it.id == id },
            aliases = aliases.filterValues { it != id },
        )
    }

    public fun removeAll(ids: Iterable<String>): MediaCatalog =
        ids.fold(this) { catalog, id -> catalog.remove(id) }

    /**
     * Vervangt de metadata van een bekend asset op zijn plek — bijvoorbeeld na
     * het bijwerken van [MediaAsset.sourceRevision]. Onbekende id's veranderen
     * niets; gebruik [add] om nieuw materiaal toe te voegen.
     */
    public fun replace(asset: MediaAsset): MediaCatalog {
        if (!contains(asset.id)) return this
        return copy(
            assets = assets.map { if (it.id == asset.id) asset else it },
            // Dezelfde reden als bij [add]: een alias van ander materiaal op deze
            // uri is achterhaald zodra dit asset er zelf op komt te staan.
            aliases = if (aliases[asset.uri] == asset.id) aliases else aliases - asset.uri,
        )
    }

    public fun sorted(order: MediaSort, descending: Boolean = false): List<MediaAsset> {
        // Het id als laatste sleutel: zonder tiebreak is de volgorde van gelijke
        // waarden niet reproduceerbaar en flikkert de lijst tussen sessies.
        val comparator = when (order) {
            MediaSort.NAME -> compareBy<MediaAsset> { it.displayName.lowercase() }
            MediaSort.DURATION -> compareBy { it.durationUs }
            MediaSort.ADDED -> compareBy { it.addedAtEpochMs }
        }.thenBy { it.id }
        return assets.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    /** Zoekt op naam, hoofdletterongevoelig. Een lege zoekterm levert alles op. */
    public fun search(query: String): List<MediaAsset> = assets.filter { it.matchesQuery(query) }

    public fun filter(filter: MediaFilter): List<MediaAsset> = assets.filter(filter::matches)

    public fun filter(filter: MediaFilter, order: MediaSort, descending: Boolean = false): List<MediaAsset> =
        sorted(order, descending).filter(filter::matches)

    public companion object {
        public fun of(assets: Iterable<MediaAsset>): MediaCatalog = MediaCatalog().addAll(assets)
    }
}

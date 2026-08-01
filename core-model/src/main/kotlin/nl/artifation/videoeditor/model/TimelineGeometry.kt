package nl.artifation.videoeditor.model

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * De rekenkant van de tijdlijn: tijd ↔ pixels, hit-testing, snappen, liniaal.
 *
 * Bewust los van Compose. Dit is precies de logica waar een editor op staat of
 * valt — een clip die één pixel naast je vinger reageert, of een sleep die net
 * niet snapt, voelt onmiddellijk goedkoop. En het is ook precies de logica die
 * je niet wilt debuggen door telkens naar een toestel te deployen.
 *
 * Alle pixelmaten zijn al omgerekend naar echte pixels; de Compose-laag doet de
 * dp-conversie en geeft ze door.
 */
public data class TimelineGeometry(
    val pxPerSecond: Float,
    /** Hoeveel pixels de tijdlijn naar links is gescrold. */
    val scrollPx: Float = 0f,
    val rulerHeightPx: Float = 28f,
    val trackHeightPx: Float = 56f,
    val trackGapPx: Float = 4f,
    /** Raster waarop een sleep vastklikt. */
    val snapUs: Us = 100_000L,
    /** Binnen deze afstand wint een clipgrens van het raster. */
    val edgeSnapPx: Float = 12f,
) {
    init {
        require(pxPerSecond > 0f) { "pxPerSecond moet positief zijn" }
        require(trackHeightPx > 0f) { "trackHeightPx moet positief zijn" }
        require(snapUs > 0L) { "snapUs moet positief zijn" }
    }

    public fun xAt(atUs: Us): Float = atUs / US_PER_SECOND.toFloat() * pxPerSecond - scrollPx

    public fun timeAt(x: Float): Us =
        (((x + scrollPx) / pxPerSecond) * US_PER_SECOND).toLong().coerceAtLeast(0L)

    public fun widthOf(durationUs: Us): Float = durationUs / US_PER_SECOND.toFloat() * pxPerSecond

    public fun trackTop(index: Int): Float =
        rulerHeightPx + index * (trackHeightPx + trackGapPx)

    /** Zoomt rond [anchorX], zodat het punt onder de vinger blijft staan. */
    public fun zoomedBy(factor: Float, anchorX: Float): TimelineGeometry {
        require(factor > 0f) { "zoomfactor moet positief zijn" }
        val anchorUs = timeAt(anchorX)
        val zoomed = copy(pxPerSecond = (pxPerSecond * factor).coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND))
        // Herbereken de scroll zodat anchorUs weer op anchorX ligt.
        val target = anchorUs / US_PER_SECOND.toFloat() * zoomed.pxPerSecond - anchorX
        return zoomed.copy(scrollPx = target.coerceAtLeast(0f))
    }

    public data class Hit(
        val sequenceIndex: Int,
        val itemIndex: Int,
        val clipId: String,
        val startUs: Us,
    )

    /** Welke clip ligt er onder ([x], [y])? Gaten tellen niet mee. */
    public fun hitTest(sequences: List<Sequence>, x: Float, y: Float): Hit? {
        if (y < rulerHeightPx) return null

        val trackIndex = ((y - rulerHeightPx) / (trackHeightPx + trackGapPx)).toInt()
        if (trackIndex !in sequences.indices) return null
        // In de tussenruimte tussen twee tracks zit geen clip.
        val withinTrack = (y - trackTop(trackIndex))
        if (withinTrack < 0f || withinTrack > trackHeightPx) return null

        val atUs = timeAt(x)
        val sequence = sequences[trackIndex]
        var cursorUs = 0L

        sequence.items.forEachIndexed { itemIndex, item ->
            val startUs = cursorUs
            cursorUs += item.durationUs
            if (atUs in startUs until cursorUs && item is Clip) {
                return Hit(trackIndex, itemIndex, item.id, startUs)
            }
        }
        return null
    }

    /**
     * Klikt een tijdstip vast.
     *
     * Clipgrenzen winnen van het raster: bij monteren wil je clips tegen elkaar
     * aan leggen, en een raster dat daar net naast zit is erger dan geen raster.
     */
    public fun snap(atUs: Us, edges: List<Us> = emptyList()): Us {
        val nearestEdge = edges.minByOrNull { abs(it - atUs) }
        if (nearestEdge != null && abs(xAt(nearestEdge) - xAt(atUs)) <= edgeSnapPx) {
            return nearestEdge
        }
        return (atUs.toDouble() / snapUs).roundToLong() * snapUs
    }

    /** Alle clipgrenzen in de sequences; de kandidaten om tegenaan te snappen. */
    public fun edgesOf(sequences: List<Sequence>): List<Us> = buildList {
        for (sequence in sequences) {
            var cursorUs = 0L
            add(0L)
            for (item in sequence.items) {
                cursorUs += item.durationUs
                add(cursorUs)
            }
        }
    }.distinct().sorted()

    public data class Tick(val atUs: Us, val major: Boolean)

    /**
     * Streepjes voor de liniaal.
     *
     * Het interval schaalt mee met de zoom, zodat streepjes altijd ongeveer
     * [TARGET_TICK_SPACING_PX] uit elkaar staan. Een vast interval levert bij
     * uitzoomen een dichte grijze balk op en bij inzoomen een lege.
     */
    public fun ticks(visibleWidthPx: Float, majorEvery: Int = 5): List<Tick> {
        if (visibleWidthPx <= 0f) return emptyList()

        val intervalUs = chooseInterval()
        val startUs = timeAt(0f) / intervalUs * intervalUs
        val endUs = timeAt(visibleWidthPx)

        return buildList {
            var atUs = startUs
            while (atUs <= endUs) {
                val step = (atUs / intervalUs).toInt()
                add(Tick(atUs, major = step % majorEvery == 0))
                atUs += intervalUs
            }
        }
    }

    internal fun chooseInterval(): Us =
        INTERVALS_US.firstOrNull { widthOf(it) >= TARGET_TICK_SPACING_PX } ?: INTERVALS_US.last()

    public companion object {
        public const val MIN_PX_PER_SECOND: Float = 4f
        public const val MAX_PX_PER_SECOND: Float = 800f

        private const val TARGET_TICK_SPACING_PX = 60f

        /** Een ladder die aanvoelt als tijd: tienden, halven, seconden, minuten. */
        private val INTERVALS_US = listOf(
            100_000L, 200_000L, 500_000L,
            1_000_000L, 2_000_000L, 5_000_000L,
            10_000_000L, 15_000_000L, 30_000_000L,
            60_000_000L, 120_000_000L, 300_000_000L, 600_000_000L,
        )
    }
}

package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Keyframe
import nl.artifation.videoeditor.model.NormRect
import nl.artifation.videoeditor.model.OutputSpec
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/** Waar het onderwerp zich op een tijdstip bevindt, genormaliseerd binnen het bronframe. */
public data class SubjectSample(
    val atUs: Us,
    val centerX: Float,
    val centerY: Float,
)

public data class ReframeConfig(
    /**
     * Hoe snel de crop een verplaatst onderwerp inhaalt. Hoger volgt strakker maar
     * neemt ook meer van de ruis in de detectie mee.
     */
    val smoothingHz: Float = 1.2f,
    /**
     * Beweging kleiner dan dit deel van de framebreedte wordt genegeerd, zodat de
     * crop stilstaat bij een pratend hoofd in plaats van mee te ademen.
     */
    val deadzoneFrac: Float = 0.02f,
    /** Uitdunning van het resultaat; 0 houdt elk keyframe. */
    val decimationTolerance: Float = 0.002f,
) {
    init {
        require(smoothingHz > 0f) { "smoothingHz moet positief zijn" }
        require(deadzoneFrac >= 0f) { "deadzoneFrac moet >= 0 zijn" }
        require(decimationTolerance >= 0f) { "decimationTolerance moet >= 0 zijn" }
    }
}

/**
 * Zet ruwe detecties om in een bruikbaar crop-pad voor auto-reframe.
 *
 * Dit is het hele bewegingsgedrag van de feature, los van de detectie zelf. ML Kit
 * levert straks alleen nog punten aan; wat er hier mee gebeurt — volgen, negeren,
 * verspringen — is pure rekenkunde en daarom zonder toestel te testen.
 */
public object PathSmoother {

    /**
     * @param samples oplopend in tijd, middelpunten genormaliseerd binnen het bronframe
     * @param outputAspect breedte/hoogte van de export, bijvoorbeeld 9:16
     * @param sourceAspect breedte/hoogte van het bronmateriaal
     * @param sceneCutsUs scenegrenzen uit [SceneDetector]; daar springt de crop hard
     */
    public fun smooth(
        samples: List<SubjectSample>,
        outputAspect: Float,
        sourceAspect: Float,
        sceneCutsUs: List<Us> = emptyList(),
        config: ReframeConfig = ReframeConfig(),
    ): List<Keyframe<NormRect>> {
        if (samples.isEmpty()) return emptyList()
        require(samples.zipWithNext().all { (a, b) -> b.atUs > a.atUs }) {
            "samples moeten oplopend in tijd staan"
        }

        val (width, height) = cropSize(outputAspect, sourceAspect)
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        val omega = 2.0 * PI * config.smoothingHz
        val cuts = sceneCutsUs.sorted()

        // De vastgehouden doelen: pas als het onderwerp de deadzone verlaat, verschuift
        // het doel mee. Zonder dit kruipt de crop mee met de ruis in de detectie.
        var heldX = samples.first().centerX
        var heldY = samples.first().centerY

        var posX = 0.0
        var posY = 0.0
        var velX = 0.0
        var velY = 0.0

        val path = ArrayList<Keyframe<NormRect>>(samples.size)

        samples.forEachIndexed { index, sample ->
            if (abs(sample.centerX - heldX) > config.deadzoneFrac) heldX = sample.centerX
            if (abs(sample.centerY - heldY) > config.deadzoneFrac) heldY = sample.centerY

            // Het doel zo ver naar binnen halen dat het venster in het frame past.
            val targetX = heldX.coerceIn(halfWidth, 1f - halfWidth).toDouble()
            val targetY = heldY.coerceIn(halfHeight, 1f - halfHeight).toDouble()

            val previous = samples.getOrNull(index - 1)
            val crossedCut = previous != null && cuts.any { it > previous.atUs && it <= sample.atUs }

            if (previous == null || crossedCut) {
                // Bij een cut is er niets om naartoe te bewegen: het beeld is al gewisseld.
                posX = targetX
                posY = targetY
                velX = 0.0
                velY = 0.0
            } else {
                val dt = (sample.atUs - previous.atUs).toDouble() / US_PER_SECOND
                val (nextX, nextVelX) = springStep(posX, velX, targetX, omega, dt)
                val (nextY, nextVelY) = springStep(posY, velY, targetY, omega, dt)
                posX = nextX
                velX = nextVelX
                posY = nextY
                velY = nextVelY
            }

            path.add(
                Keyframe(
                    atUs = sample.atUs,
                    value = NormRect(
                        left = (posX - halfWidth).toFloat(),
                        top = (posY - halfHeight).toFloat(),
                        right = (posX + halfWidth).toFloat(),
                        bottom = (posY + halfHeight).toFloat(),
                    ),
                ),
            )
        }

        return decimate(path, config.decimationTolerance)
    }

    public fun smooth(
        samples: List<SubjectSample>,
        outputSpec: OutputSpec,
        sourceWidth: Int,
        sourceHeight: Int,
        sceneCutsUs: List<Us> = emptyList(),
        config: ReframeConfig = ReframeConfig(),
    ): List<Keyframe<NormRect>> = smooth(
        samples = samples,
        outputAspect = outputSpec.aspect,
        sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat(),
        sceneCutsUs = sceneCutsUs,
        config = config,
    )

    /**
     * Het grootste venster met de gevraagde verhouding dat nog in het bronframe past,
     * genormaliseerd. 16:9 naar 9:16 levert een strook van 31,6 % van de breedte.
     */
    public fun cropSize(outputAspect: Float, sourceAspect: Float): Pair<Float, Float> {
        require(outputAspect > 0f && sourceAspect > 0f) { "verhoudingen moeten positief zijn" }
        return if (outputAspect <= sourceAspect) {
            (outputAspect / sourceAspect) to 1f
        } else {
            1f to (sourceAspect / outputAspect)
        }
    }

    /**
     * Eén stap van een kritisch gedempte veer, analytisch opgelost.
     *
     * Analytisch en niet met Euler-stapjes, omdat een numerieke integratie bij een
     * grove stap (detectie draait op 5 fps) alsnog kan doorschieten. Een crop die
     * voorbij zijn doel schiet en terugveert is meteen zichtbaar.
     */
    private fun springStep(
        position: Double,
        velocity: Double,
        target: Double,
        omega: Double,
        dt: Double,
    ): Pair<Double, Double> {
        val delta = position - target
        val slope = velocity + omega * delta
        val decay = exp(-omega * dt)
        val nextDelta = (delta + slope * dt) * decay

        return (target + nextDelta) to ((slope - omega * (delta + slope * dt)) * decay)
    }

    /**
     * Haalt keyframes weg die uit hun buren te interpoleren zijn.
     *
     * Detectie op 5 fps geeft honderden keyframes per minuut, terwijl een crop die
     * rustig doorloopt er maar een handvol nodig heeft. Dat scheelt in het
     * projectbestand en in het werk per frame tijdens het renderen.
     */
    private fun decimate(
        path: List<Keyframe<NormRect>>,
        tolerance: Float,
    ): List<Keyframe<NormRect>> {
        if (tolerance <= 0f || path.size <= 2) return path

        val kept = mutableListOf(path.first())
        var anchor = 0

        for (index in 1 until path.size - 1) {
            val candidate = index + 1
            val representable = (anchor + 1 until candidate).all { between ->
                val expected = interpolate(path[anchor], path[candidate], path[between].atUs)
                maxCornerError(expected, path[between].value) <= tolerance
            }
            if (!representable) {
                kept.add(path[index])
                anchor = index
            }
        }

        kept.add(path.last())
        return kept
    }

    private fun interpolate(a: Keyframe<NormRect>, b: Keyframe<NormRect>, atUs: Us): NormRect {
        val span = (b.atUs - a.atUs).toFloat()
        val t = if (span <= 0f) 0f else (atUs - a.atUs) / span
        return NormRect(
            left = a.value.left + (b.value.left - a.value.left) * t,
            top = a.value.top + (b.value.top - a.value.top) * t,
            right = a.value.right + (b.value.right - a.value.right) * t,
            bottom = a.value.bottom + (b.value.bottom - a.value.bottom) * t,
        )
    }

    private fun maxCornerError(a: NormRect, b: NormRect): Float = maxOf(
        abs(a.left - b.left),
        abs(a.top - b.top),
        abs(a.right - b.right),
        abs(a.bottom - b.bottom),
    )
}

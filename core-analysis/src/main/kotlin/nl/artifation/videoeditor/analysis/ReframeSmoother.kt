package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Keyframe
import nl.artifation.videoeditor.model.NormRect
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-reframe: van detecties naar een rustig cropkeyframe-pad.
 *
 * Het werk zit niet in de detectie maar in de smoothing. Een crop die de
 * bounding box exact volgt, wiebelt constant en ziet er onmiddellijk uit als
 * amateurwerk. Drie mechanismen voorkomen dat:
 *
 * 1. **Deadzone** — kleine bewegingen worden genegeerd, de crop blijft staan.
 * 2. **Critically damped spring** — de crop loopt de doelpositie vloeiend achterna
 *    en schiet er nooit voorbij (geen overshoot, per definitie van kritisch gedempt).
 * 3. **Shot-grenzen** — op een harde cut mág de crop springen; daartussen nooit.
 */
public data class Detection(
    val atUs: Us,
    /** Genormaliseerde bounding boxes van gezichten/personen in dit frame. */
    val boxes: List<NormRect>,
)

public data class ReframeConfig(
    /** Doelverhouding breedte/hoogte. 9:16 = 0.5625. */
    val targetAspect: Float = PORTRAIT_WIDTH / PORTRAIT_HEIGHT,
    /** Hoe snel de crop de doelpositie volgt, in Hz. Lager = rustiger, trager. */
    val smoothingHz: Float = 1.2f,
    /** Beweging kleiner dan deze fractie van de framebreedte wordt genegeerd. */
    val deadzoneFrac: Float = 0.05f,
    /** Integratiestap voor de spring. Kleiner = nauwkeuriger, duurder. */
    val stepUs: Us = 33_333L,
) {
    init {
        require(targetAspect > 0f) { "targetAspect moet positief zijn" }
        require(smoothingHz > 0f) { "smoothingHz moet positief zijn" }
        require(deadzoneFrac >= 0f) { "deadzoneFrac moet >= 0 zijn" }
        require(stepUs > 0L) { "stepUs moet positief zijn" }
    }

    public companion object {
        /** 9:16 — het formaat waar dit hele mechanisme voor bestaat. */
        public const val PORTRAIT_WIDTH: Float = 9f
        public const val PORTRAIT_HEIGHT: Float = 16f
    }
}

public object ReframeSmoother {

    /** Het midden van het frame; waar de crop staat als er niets gedetecteerd is. */
    private const val FRAME_CENTER = 0.5f

    /** Ondergrens voor het gewicht van een box, zodat een lege box niet door nul deelt. */
    private const val MIN_BOX_WEIGHT = 1e-6f

    /**
     * Lost het cropppad op.
     *
     * @param sourceAspect breedte/hoogte van het bronmateriaal
     * @param sceneCutsUs shot-grenzen; daar springt de crop in plaats van te glijden
     */
    public fun solve(
        detections: List<Detection>,
        sourceAspect: Float,
        sceneCutsUs: List<Us> = emptyList(),
        config: ReframeConfig = ReframeConfig(),
    ): List<Keyframe<NormRect>> {
        require(sourceAspect > 0f) { "sourceAspect moet positief zijn" }
        if (detections.isEmpty()) return emptyList()

        val cropSize = cropSize(sourceAspect, config.targetAspect)
        val samples = detections.sortedBy { it.atUs }
        val cuts = sceneCutsUs.sorted()

        // Doelcentrum per sample; frames zonder detectie houden het vorige doel vast.
        var heldTarget = samples.firstNotNullOfOrNull { centroid(it.boxes) } ?: Point(FRAME_CENTER, FRAME_CENTER)
        var position = heldTarget
        var velocity = Point(0f, 0f)

        val out = mutableListOf<Keyframe<NormRect>>()
        var cutIndex = 0

        for ((sampleIndex, sample) in samples.withIndex()) {
            val detected = centroid(sample.boxes)
            if (detected != null && distance(detected, heldTarget) > config.deadzoneFrac) {
                heldTarget = detected
            }

            // Ligt er een shot-grens vóór dit sample? Dan hard springen.
            var jumped = false
            while (cutIndex < cuts.size && cuts[cutIndex] <= sample.atUs) {
                cutIndex++
                jumped = true
            }
            if (jumped) {
                heldTarget = detected ?: heldTarget
                position = heldTarget
                velocity = Point(0f, 0f)
            } else if (sampleIndex > 0) {
                val dtUs = sample.atUs - samples[sampleIndex - 1].atUs
                val result = integrate(position, velocity, heldTarget, dtUs, config)
                position = result.first
                velocity = result.second
            }

            out.add(Keyframe(sample.atUs, clampedRect(position, cropSize)))
        }

        return out
    }

    private data class Point(val x: Float, val y: Float)

    private data class CropSize(val width: Float, val height: Float)

    /**
     * De crop is zo groot mogelijk binnen het bronframe bij de doelverhouding.
     * Bij 16:9 → 9:16 wordt dat een smalle verticale strook over de volle hoogte.
     */
    private fun cropSize(sourceAspect: Float, targetAspect: Float): CropSize =
        if (targetAspect < sourceAspect) {
            CropSize(width = targetAspect / sourceAspect, height = 1f)
        } else {
            CropSize(width = 1f, height = sourceAspect / targetAspect)
        }

    private fun centroid(boxes: List<NormRect>): Point? {
        if (boxes.isEmpty()) return null
        // Groter oppervlak weegt zwaarder: de persoon op de voorgrond stuurt de crop.
        var weightSum = 0f
        var x = 0f
        var y = 0f
        for (box in boxes) {
            val weight = max(MIN_BOX_WEIGHT, box.width * box.height)
            x += box.centerX * weight
            y += box.centerY * weight
            weightSum += weight
        }
        return Point(x / weightSum, y / weightSum)
    }

    private fun distance(a: Point, b: Point): Float =
        max(abs(a.x - b.x), abs(a.y - b.y))

    /**
     * Impliciete critically-damped spring. Stabiel bij elke stapgrootte, in
     * tegenstelling tot de naïeve expliciete vorm die bij grote dt explodeert.
     */
    private fun integrate(
        start: Point,
        startVelocity: Point,
        target: Point,
        dtUs: Us,
        config: ReframeConfig,
    ): Pair<Point, Point> {
        var position = start
        var velocity = startVelocity
        val omega = (2.0 * PI * config.smoothingHz).toFloat()

        var remaining = dtUs
        while (remaining > 0L) {
            val stepUs = min(remaining, config.stepUs)
            val dt = stepUs.toFloat() / US_PER_SECOND
            // Beide assen uit dezelfde toestand van vóór de stap, anders loopt
            // de snelheid een stap achter op de positie.
            val stepX = springStep(position.x, velocity.x, target.x, dt, omega)
            val stepY = springStep(position.y, velocity.y, target.y, dt, omega)
            position = Point(stepX.first, stepY.first)
            velocity = Point(stepX.second, stepY.second)
            remaining -= stepUs
        }
        return position to velocity
    }

    private fun springStep(
        x: Float,
        v: Float,
        target: Float,
        dt: Float,
        omega: Float,
    ): Pair<Float, Float> {
        val f = 1f + 2f * dt * omega
        val oo = omega * omega
        val hoo = dt * oo
        val hhoo = dt * hoo
        val detInv = 1f / (f + hhoo)
        val detX = f * x + dt * v + hhoo * target
        val detV = v + hoo * (target - x)
        return (detX * detInv) to (detV * detInv)
    }

    /** Houdt de crop binnen het frame; anders zie je zwarte randen. */
    private fun clampedRect(center: Point, size: CropSize): NormRect {
        val halfWidth = size.width / 2f
        val halfHeight = size.height / 2f
        val cx = center.x.coerceIn(halfWidth, 1f - halfWidth)
        val cy = center.y.coerceIn(halfHeight, 1f - halfHeight)
        return NormRect(
            left = cx - halfWidth,
            top = cy - halfHeight,
            right = cx + halfWidth,
            bottom = cy + halfHeight,
        )
    }
}

package nl.artifation.videoeditor.spike

import android.graphics.Bitmap

/**
 * De uitslag van de fase 0-poort, met de meetwaarden erbij.
 *
 * Alle drempels staan hier bij elkaar en met een reden erbij. Ze zijn een oordeel,
 * geen natuurwet: daarom staan de ruwe getallen ook altijd op het scherm, zodat
 * een randgeval te zien is in plaats van weggerond tot "geslaagd".
 */
internal data class SpikeReport(
    val parity: ParityResult?,
    val blur: MaskedBlurResult?,
    val blurTrimmed: MaskedBlurResult?,
    val samples: List<SampleFrames>,
    /** Gevuld als de meting zelf strandde; dan zegt de uitslag niets. */
    val failure: String?,
) {
    val passed: Boolean
        get() = failure == null &&
            parity?.passed == true &&
            blur?.passed == true &&
            blurTrimmed?.passed == true

    /** De uitslag in één zin, zonder vakjargon. */
    val verdict: String
        get() = when {
            failure != null -> "De meting is niet afgerond, dus er is geen uitslag."
            passed -> "Geslaagd — de renderketen doet wat het bouwplan aanneemt."
            else -> "Gezakt — dit moet opgelost worden vóór fase 1."
        }
}

internal data class ParityResult(
    val samples: List<ParitySample>,
    /** Reden waarom er niet gemeten kon worden, als die er is. */
    val note: String?,
) {
    private val scores: List<Double> get() = samples.mapNotNull { it.ssim }

    val meanSsim: Double? get() = scores.takeIf { it.isNotEmpty() }?.average()
    val minSsim: Double? get() = scores.minOrNull()
    val missing: Int get() = samples.count { it.ssim == null }

    val passed: Boolean
        get() {
            if (note != null || samples.isEmpty()) return false
            // Elk tijdstip moet gemeten zijn: een frame dat niet op te halen was is
            // geen "gemiddeld toch prima", het is een gat in het bewijs.
            if (missing > 0) return false
            val mean = meanSsim ?: return false
            val min = minSsim ?: return false
            return mean >= MEAN_THRESHOLD && min >= MIN_THRESHOLD
        }

    val explanation: String
        get() = when {
            note != null -> note
            samples.isEmpty() -> "Er is geen enkel frame vergeleken."
            missing > 0 -> "$missing van de ${samples.size} tijdstippen leverden geen frame op."
            passed -> "Preview en export leveren hetzelfde beeld; de laagste score is " +
                "${format(minSsim)} en dat is ruim boven de grens van $MIN_THRESHOLD."
            else -> "Preview en export lopen uiteen: gemiddeld ${format(meanSsim)}, " +
                "laagste ${format(minSsim)}. Verwacht was minstens $MEAN_THRESHOLD gemiddeld."
        }

    companion object {
        /**
         * De export is gecomprimeerd en de preview niet, dus perfecte gelijkheid is
         * niet haalbaar. Onder de 0,90 gaat het niet meer om compressie maar om een
         * echt verschil in de renderketen — en dat is precies wat dit moet vangen.
         */
        const val MEAN_THRESHOLD = 0.90

        /** Eén slecht tijdstip mag de rest niet kunnen verbergen. */
        const val MIN_THRESHOLD = 0.85
    }
}

internal data class ParitySample(
    val timeUs: Long,
    /** Null als het frame niet op te halen was. */
    val ssim: Double?,
)

internal data class MaskedBlurResult(
    val label: String,
    val inPointUs: Long,
    val samples: List<BlurSample>,
) {
    /** Hoeveel scherper het beeld binnen de cirkel is dan erbuiten. */
    val sharpnessRatios: List<Double>
        get() = samples.mapNotNull { it.sharpnessRatio }

    val worstSharpnessRatio: Double? get() = sharpnessRatios.minOrNull()

    /**
     * Hoeveel scherper de plek volgens de **bron**tijd is dan de plek volgens de
     * cliptijd. Bij een ongetrimde clip zijn die gelijk en zegt dit niets; bij een
     * getrimde clip is dit hét onderscheid.
     */
    val syncRatios: List<Double> get() = samples.mapNotNull { it.syncRatio }

    val worstSyncRatio: Double? get() = syncRatios.minOrNull()

    val passed: Boolean
        get() {
            if (samples.isEmpty()) return false
            val sharpness = worstSharpnessRatio ?: return false
            if (sharpness < SHARPNESS_THRESHOLD) return false
            // Alleen eisen als er iets te onderscheiden viel: bij een ongetrimde clip
            // vallen beide plekken samen en is er geen syncRatio.
            val sync = worstSyncRatio ?: return true
            return sync >= SYNC_THRESHOLD
        }

    val explanation: String
        get() = when {
            samples.isEmpty() -> "Er is geen enkel frame gemeten."
            (worstSharpnessRatio ?: 0.0) < SHARPNESS_THRESHOLD ->
                "Binnen de cirkel is het beeld maar ${format(worstSharpnessRatio)}× zo scherp " +
                    "als erbuiten. Verwacht was minstens ${SHARPNESS_THRESHOLD}×: het mask " +
                    "werkt niet, of de blur raakt ook de cirkel."
            worstSyncRatio != null && worstSyncRatio!! < SYNC_THRESHOLD ->
                "De scherpe plek staat niet waar het mask hem op dit brontijdstip zet " +
                    "(${format(worstSyncRatio)}× tegenover de plek volgens de cliptijd). " +
                    "Dat wijst erop dat het in-punt onderweg verloren gaat."
            worstSyncRatio == null ->
                "Cirkel scherp, achtergrond geblurd, over de volle duur " +
                    "(minstens ${format(worstSharpnessRatio)}× scherper binnen dan buiten)."
            else ->
                "Cirkel scherp op de juiste plek, ook met een in-punt van " +
                    "${inPointUs / 1_000_000}s: ${format(worstSharpnessRatio)}× scherper binnen " +
                    "dan buiten, en ${format(worstSyncRatio)}× scherper dan waar de cirkel " +
                    "zou staan als het in-punt genegeerd werd."
        }

    companion object {
        /**
         * Het testbeeld is een schaakbord, dus het verschil tussen scherp en geblurd
         * hoort fors te zijn. Twee keer is een ruime ondergrens: daaronder gaat het
         * niet meer om meetruis.
         */
        const val SHARPNESS_THRESHOLD = 2.0

        /**
         * Lager dan de scherptedrempel, want beide plekken kunnen deels binnen de
         * geblurde achtergrond vallen. Boven de 1,5 is de scherpe plek onmiskenbaar
         * die van de brontijd.
         */
        const val SYNC_THRESHOLD = 1.5
    }
}

internal data class BlurSample(
    val clipTimeUs: Long,
    val sourceTimeUs: Long,
    val insideSharpness: Double,
    val outsideSharpness: Double,
    /** Null als de twee kandidaatplekken te dicht bij elkaar lagen om te scheiden. */
    val naiveSharpness: Double?,
) {
    val sharpnessRatio: Double?
        get() = if (outsideSharpness <= 0.0) null else insideSharpness / outsideSharpness

    val syncRatio: Double?
        get() = naiveSharpness?.let { if (it <= 0.0) null else insideSharpness / it }
}

/** Frames om op het scherm te tonen, zodat de uitslag ook met het oog te toetsen is. */
internal data class SampleFrames(
    val label: String,
    val left: Pair<Bitmap, String>,
    val right: Pair<Bitmap, String>?,
)

private fun format(value: Double?): String =
    if (value == null) "—" else String.format(java.util.Locale.ROOT, "%.2f", value)

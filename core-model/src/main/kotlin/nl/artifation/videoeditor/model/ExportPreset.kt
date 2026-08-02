package nl.artifation.videoeditor.model

import kotlinx.serialization.Serializable

/**
 * Hoeveel kwaliteit je per bit wilt kopen.
 *
 * Uitgedrukt in bits per pixel per frame, want dat is de enige maat die over
 * resoluties en framerates heen betekenis houdt. "8 Mbit/s" zegt niets zonder te
 * weten hoeveel pixels er per seconde doorheen moeten: dezelfde 8 Mbit is royaal
 * voor 720p30 en karig voor 4K60.
 */
public enum class ExportQuality(public val bitsPerPixel: Float) {
    /** Voor snel delen; zichtbare artefacten in druk beeld. */
    ZUINIG(0.05f),

    /** Wat je voor sociale media wilt: geen zichtbaar verlies bij normaal materiaal. */
    GEBALANCEERD(0.09f),

    /** Ruim, voor materiaal dat nog een ronde bewerking moet doorstaan. */
    RUIM(0.16f),
}

/**
 * Een complete exportinstelling: formaat plus bitrates.
 *
 * Bestaat apart van [OutputSpec] omdat die alleen zegt hoe groot het beeld is.
 * Wat het mag kosten aan bits is een andere keuze, en juist die wil je als
 * gebruiker per export kunnen omzetten zonder aan het projectformaat te komen.
 */
@Serializable
public data class ExportPreset(
    val id: String,
    val label: String,
    val outputSpec: OutputSpec,
    /** Bits per seconde voor het beeld. */
    val videoBitrate: Int,
    /** Bits per seconde voor het geluid. */
    val audioBitrate: Int = DEFAULT_AUDIO_BITRATE,
) {
    init {
        require(id.isNotBlank()) { "preset heeft een id nodig" }
        require(videoBitrate > 0) { "videoBitrate moet positief zijn, was $videoBitrate" }
        require(audioBitrate > 0) { "audioBitrate moet positief zijn, was $audioBitrate" }
    }

    /**
     * Ruwe schatting van de bestandsgrootte in bytes.
     *
     * Genoeg om in de UI te waarschuwen voordat iemand een uur materiaal in 4K
     * wegschrijft. Variabele bitrate maakt dit een benadering, geen belofte.
     */
    public fun estimatedBytes(durationUs: Us): Long {
        if (durationUs <= 0L) return 0L
        val bitsPerSecond = (videoBitrate + audioBitrate).toLong()
        return durationUs * bitsPerSecond / (US_PER_SECOND * BITS_PER_BYTE)
    }

    public companion object {
        public const val DEFAULT_AUDIO_BITRATE: Int = 128_000
        private const val BITS_PER_BYTE = 8

        /** De standaard: 9:16 op 1080 breed, wat elk platform accepteert. */
        public val SHORTS_1080: ExportPreset = balanced(
            id = "shorts-1080",
            label = "Shorts 1080×1920",
            outputSpec = OutputSpec(width = 1080, height = 1920, frameRate = 30),
        )

        /** Zelfde formaat, soepeler beeld. Kost ongeveer het dubbele. */
        public val SHORTS_1080_60: ExportPreset = balanced(
            id = "shorts-1080-60",
            label = "Shorts 1080×1920, 60 fps",
            outputSpec = OutputSpec(width = 1080, height = 1920, frameRate = 60),
        )

        /** Voor een trage verbinding of een lange video. */
        public val SHORTS_720: ExportPreset = ExportPreset(
            id = "shorts-720",
            label = "Shorts 720×1280, zuinig",
            outputSpec = OutputSpec(width = 720, height = 1280, frameRate = 30),
            videoBitrate = recommendedVideoBitrate(
                OutputSpec(width = 720, height = 1280, frameRate = 30),
                ExportQuality.ZUINIG,
            ),
        )

        /** Liggend, voor materiaal dat niet naar 9:16 hoeft. */
        public val LIGGEND_1080: ExportPreset = balanced(
            id = "liggend-1080",
            label = "Liggend 1920×1080",
            outputSpec = OutputSpec(width = 1920, height = 1080, frameRate = 30),
        )

        /** Ruim bemeten, om later nog een keer te kunnen bewerken. */
        public val MASTER_1080: ExportPreset = ExportPreset(
            id = "master-1080",
            label = "Master 1080×1920, ruim",
            outputSpec = OutputSpec(width = 1080, height = 1920, frameRate = 30),
            videoBitrate = recommendedVideoBitrate(
                OutputSpec(width = 1080, height = 1920, frameRate = 30),
                ExportQuality.RUIM,
            ),
            audioBitrate = 256_000,
        )

        /** Alle presets, in de volgorde waarin ze in de UI horen te staan. */
        public val ALL: List<ExportPreset> = listOf(
            SHORTS_1080,
            SHORTS_1080_60,
            SHORTS_720,
            LIGGEND_1080,
            MASTER_1080,
        )

        public fun byId(id: String): ExportPreset? = ALL.firstOrNull { it.id == id }

        /**
         * De bitrate die bij dit formaat hoort.
         *
         * `width × height × frameRate` is het aantal pixels per seconde; keer de
         * bits per pixel geeft de bitrate. Zo schaalt hij vanzelf mee met elk
         * formaat, ook eentje die hier niet als preset staat.
         */
        public fun recommendedVideoBitrate(
            outputSpec: OutputSpec,
            quality: ExportQuality = ExportQuality.GEBALANCEERD,
        ): Int {
            val pixelsPerSecond =
                outputSpec.width.toLong() * outputSpec.height * outputSpec.frameRate
            return (pixelsPerSecond * quality.bitsPerPixel).toInt()
        }

        /** Een preset op maat voor een formaat dat niet in [ALL] staat. */
        public fun forSpec(
            outputSpec: OutputSpec,
            quality: ExportQuality = ExportQuality.GEBALANCEERD,
        ): ExportPreset = ExportPreset(
            id = "op-maat-${outputSpec.width}x${outputSpec.height}-${outputSpec.frameRate}",
            label = "${outputSpec.width}×${outputSpec.height}, ${outputSpec.frameRate} fps",
            outputSpec = outputSpec,
            videoBitrate = recommendedVideoBitrate(outputSpec, quality),
        )

        private fun balanced(id: String, label: String, outputSpec: OutputSpec) = ExportPreset(
            id = id,
            label = label,
            outputSpec = outputSpec,
            videoBitrate = recommendedVideoBitrate(outputSpec, ExportQuality.GEBALANCEERD),
        )
    }
}

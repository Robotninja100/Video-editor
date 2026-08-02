package nl.artifation.videoeditor.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Haalt de audio uit een videobestand als losse samples.
 *
 * Zonder dit draait de hele DSP in `:core-analysis` op niets: stiltedetectie en
 * loudness zijn af en getest, maar er kwam nooit geluid binnen. Dit is de enige
 * plek in de keten die een echt bestand aanraakt.
 *
 * Mono, want beide analyses meten niveau en geen richting; de stereobreedte zou
 * alleen geheugen kosten. De downmix zelf komt uit `:core-analysis`, zodat de
 * afspraak over hoe kanalen samengevoegd worden op één plek staat.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README:
 * de apk-job in CI is de eerste plek waar deze code langs een compiler gaat.
 */
internal object AudioDecoder {

    data class Pcm(
        val mono: FloatArray,
        val sampleRate: Int,
        val durationUs: Long,
    ) {
        // Handmatig, want FloatArray vergelijkt op identiteit in een data class.
        override fun equals(other: Any?): Boolean =
            other is Pcm &&
                mono.contentEquals(other.mono) &&
                sampleRate == other.sampleRate &&
                durationUs == other.durationUs

        override fun hashCode(): Int =
            (mono.contentHashCode() * PRIME + sampleRate) * PRIME + durationUs.hashCode()
    }

    /**
     * @param onProgress fractie 0..1 van het gedecodeerde materiaal
     * @return `null` als het bestand geen bruikbare audiotrack heeft
     */
    @Suppress("ReturnCount") // Elke uitgang is "hier valt niets te decoderen".
    fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
    ): Pcm? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = audioTrackIndex(extractor) ?: return null
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            decodeTrack(extractor, format, mime, onProgress)
        } catch (ignored: Exception) {
            // setDataSource en createDecoderByType gooien bij een uri die de
            // provider niet meer geeft, bij een codec die het toestel niet heeft,
            // en bij een bestand dat geen media is. Alle drie zijn "geen bruikbare
            // audio", niet iets om de app op te laten vallen.
            null
        } finally {
            extractor.release()
        }
    }

    private fun audioTrackIndex(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        }

    private fun decodeTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String,
        onProgress: (Float) -> Unit,
    ): Pcm {
        // Expliciet 16 bits vragen: zonder dit levert een decoder op sommige
        // toestellen floats en op andere shorts, en dan lees je de ene helft van
        // je opname als ruis.
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val samples = ArrayList<Float>(INITIAL_CAPACITY)
        val info = MediaCodec.BufferInfo()
        var invoerKlaar = false

        try {
            while (true) {
                if (!invoerKlaar) invoerKlaar = voerAan(codec, extractor, durationUs, onProgress)

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // De decoder mag zijn formaat pas bij het eerste blok
                        // vaststellen; wat de extractor meldde was een aanname.
                        val actueel = codec.outputFormat
                        sampleRate = actueel.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = actueel.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outputIndex < 0) continue
                        leesUit(codec, outputIndex, info, samples)
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        onProgress(1f)
        val interleaved = samples.toFloatArray()
        return Pcm(
            mono = downmixToMono(interleaved, channels.coerceAtLeast(1)),
            sampleRate = sampleRate,
            // De duur uit het formaat is betrouwbaarder dan die uit het aantal
            // samples: een decoder mag priming-samples weglaten.
            durationUs = if (durationUs > 0L) durationUs else duurUit(interleaved.size, channels, sampleRate),
        )
    }

    /** @return true zodra het einde van de track is aangeboden */
    private fun voerAan(
        codec: MediaCodec,
        extractor: MediaExtractor,
        durationUs: Long,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex < 0) return false

        val buffer = codec.getInputBuffer(inputIndex) ?: return false
        val size = extractor.readSampleData(buffer, 0)

        return if (size < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
            if (durationUs > 0L) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
            extractor.advance()
            false
        }
    }

    private fun leesUit(
        codec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        into: ArrayList<Float>,
    ) {
        if (info.size <= 0) return
        val buffer = codec.getOutputBuffer(outputIndex) ?: return

        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        while (shorts.hasRemaining()) {
            into.add(shorts.get() / FULL_SCALE)
        }
    }

    private fun duurUit(interleaved: Int, channels: Int, sampleRate: Int): Long {
        if (sampleRate <= 0 || channels <= 0) return 0L
        return interleaved.toLong() / channels * US_PER_SECOND / sampleRate
    }

    private const val TIMEOUT_US = 10_000L
    private const val INITIAL_CAPACITY = 1 shl 20
    private const val FULL_SCALE = 32_768f
    private const val US_PER_SECOND = 1_000_000L
    private const val PRIME = 31
}

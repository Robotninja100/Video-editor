package nl.artifation.videoeditor.analysis

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Minimale WAV-lezer en -schrijver voor tests.
 *
 * `docs/BOUWPLAN.md` vraagt om een golden WAV met bekende intervallen. Die als
 * binair bestand in de repo zetten maakt hem ondoorzichtig — je ziet in een diff
 * niet wat erin zit — en hem met ffmpeg genereren voegt een tool toe die op de
 * bouwmachine niet hoeft te staan. Dit is een paar regels code en dan is de fixture
 * leesbaar én reproduceerbaar.
 *
 * 16-bits PCM, little-endian. Meer heeft een test niet nodig.
 */
internal object WavFixtures {

    private const val HEADER_BYTES = 44
    private const val PCM_FORMAT: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val FULL_SCALE = 32_767f

    data class Pcm(val samples: FloatArray, val sampleRate: Int, val channels: Int) {

        // Handmatig omdat FloatArray op identiteit vergelijkt.
        override fun equals(other: Any?): Boolean =
            other is Pcm &&
                samples.contentEquals(other.samples) &&
                sampleRate == other.sampleRate &&
                channels == other.channels

        override fun hashCode(): Int =
            (samples.contentHashCode() * 31 + sampleRate) * 31 + channels
    }

    fun write(file: File, samples: FloatArray, sampleRate: Int, channels: Int = 1) {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())

        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(PCM_FORMAT)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(BITS_PER_SAMPLE)

        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        for (sample in samples) {
            buffer.putShort((sample.coerceIn(-1f, 1f) * FULL_SCALE).roundToInt().toShort())
        }

        file.writeBytes(buffer.array())
    }

    fun read(file: File): Pcm {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)

        require(buffer.readTag() == "RIFF") { "geen RIFF-bestand" }
        buffer.int
        require(buffer.readTag() == "WAVE") { "geen WAVE-bestand" }

        var channels = 0
        var sampleRate = 0

        while (buffer.remaining() >= 8) {
            val tag = buffer.readTag()
            val size = buffer.int

            when (tag) {
                "fmt " -> {
                    require(buffer.short == PCM_FORMAT) { "alleen ongecomprimeerde PCM" }
                    channels = buffer.short.toInt()
                    sampleRate = buffer.int
                    buffer.int
                    buffer.short
                    require(buffer.short == BITS_PER_SAMPLE) { "alleen 16-bits PCM" }
                    buffer.position(buffer.position() + (size - 16))
                }

                "data" -> {
                    val samples = FloatArray(size / 2) { buffer.short / FULL_SCALE }
                    return Pcm(samples, sampleRate, channels)
                }

                else -> buffer.position(buffer.position() + size)
            }
        }
        error("geen data-chunk gevonden in $file")
    }

    private fun ByteBuffer.readTag(): String =
        String(ByteArray(4).also { get(it) })
}

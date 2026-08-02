package nl.artifation.videoeditor.render

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider

/**
 * Eén vaste snelheid voor de hele clip.
 *
 * Media3 heeft hier geen publieke implementatie voor: `SpeedProvider` is bedoeld
 * voor snelheidsverlopen, en de enige kant-en-klare implementaties zitten
 * package-private in `:media3-transformer`. Het model kent per clip één snelheid,
 * dus dit is genoeg — en het houdt de deur open voor een verloop later.
 */
internal class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {

    init {
        require(speed > 0f && speed.isFinite()) { "speed moet positief en eindig zijn, was $speed" }
    }

    override fun getSpeed(timeUs: Long): Float = speed

    /** Nooit een volgende wijziging: de snelheid ligt vast voor de hele clip. */
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

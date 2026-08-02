package nl.artifation.videoeditor.render

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import nl.artifation.videoeditor.model.Keyframe
import nl.artifation.videoeditor.model.PlannedClip
import nl.artifation.videoeditor.model.PlannedEffect
import nl.artifation.videoeditor.model.cropAt
import nl.artifation.videoeditor.model.sourcePtsFor

/**
 * Meebewegende uitsnede voor auto-reframe.
 *
 * Media3's vaste `Crop` kan dit niet: die krijgt zijn randen één keer mee. Een
 * [MatrixTransformation] wordt per frame om een matrix gevraagd, en dat is precies
 * wat een crop-pad nodig heeft.
 *
 * De matrix doet twee dingen tegelijk: het midden van de uitsnede naar het midden
 * van het beeld schuiven, en zover opschalen dat de uitsnede het frame vult. Dat
 * is hetzelfde als croppen en dan uitvergroten, maar in één stap en zonder
 * tussenliggende buffer.
 *
 * **Niet gecompileerd in de omgeving waarin dit geschreven is.** Zie de README.
 */
@UnstableApi
internal class AnimatedCropEffect(
    private val path: List<Keyframe<PlannedEffect.Crop>>,
    private val clip: PlannedClip,
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val matrix = Matrix()

        // Keyframes horen bij de bronclip; dezelfde omrekening als bij de mask en
        // de ondertitels, anders loopt de uitsnede weg op een getrimde clip.
        val crop = path.cropAt(clip.sourcePtsFor(presentationTimeUs)) ?: return matrix

        val breedte = crop.right - crop.left
        val hoogte = crop.top - crop.bottom
        // Een ontaarde uitsnede zou door nul delen; dan maar onbewerkt doorlaten.
        if (breedte <= 0f || hoogte <= 0f) return matrix

        // NDC loopt van −1 tot 1, dus een uitsnede van breedte b vult het beeld
        // na een schaling van 2/b.
        val schaalX = FULL_NDC / breedte
        val schaalY = FULL_NDC / hoogte
        val middenX = (crop.left + crop.right) / 2f
        val middenY = (crop.bottom + crop.top) / 2f

        // Eerst schalen, dan verschuiven: x' = (x − midden) × schaal.
        matrix.setScale(schaalX, schaalY)
        matrix.postTranslate(-middenX * schaalX, -middenY * schaalY)
        return matrix
    }

    private companion object {
        /** De volle breedte van normalized device coordinates: −1 tot 1. */
        const val FULL_NDC = 2f
    }
}

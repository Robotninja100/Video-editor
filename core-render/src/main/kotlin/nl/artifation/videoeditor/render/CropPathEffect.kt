package nl.artifation.videoeditor.render

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation
import nl.artifation.videoeditor.model.Keyframe
import nl.artifation.videoeditor.model.NormRect
import nl.artifation.videoeditor.model.interpolateAt
import kotlin.math.roundToInt

/**
 * Bewegende uitsnede voor auto-reframe.
 *
 * Media3's eigen `Crop` is statisch: één rechthoek voor de hele clip. Het pad uit
 * fase 5 verandert per frame, dus die kan hier niet gebruikt worden. Deze
 * [MatrixTransformation] leest per frame de rechthoek uit het pad — met exact
 * dezelfde [interpolateAt] die in `:core-model` getest is — en zet die om naar de
 * matrix die Media3 op de textuurcoördinaten toepast.
 *
 * **Aanname over het pad:** de uitsnede verschuift wel, maar verandert niet van
 * formaat. Dat is hoe fase 5 het pad oplevert: geclampt op de doel-aspect, met een
 * vaste zoom. De uitvoergrootte moet namelijk voor de hele clip vaststaan — Media3
 * bepaalt die één keer in [configure] — dus een pad dat wél in- en uitzoomt zou de
 * eerste rechthoek als formaat krijgen en daarna gaan vervormen. Verandert fase 5
 * daar ooit iets aan, dan moet dit mee.
 */
internal class CropPathEffect(
    private val keyframes: List<Keyframe<NormRect>>,
) : MatrixTransformation {

    init {
        require(keyframes.isNotEmpty()) { "een leeg crop-pad hoort al in :core-model weggefilterd te zijn" }
    }

    /** De rechthoek waar de uitvoergrootte op gebaseerd is; zie de klassedoc. */
    private val referenceRect: NormRect = keyframes.first().value

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // Even afmetingen: encoders willen dat, net als in OutputSpec.validate().
        val width = (inputWidth * referenceRect.width).roundToInt().coerceAtLeast(2).roundDownToEven()
        val height = (inputHeight * referenceRect.height).roundToInt().coerceAtLeast(2).roundDownToEven()
        return Size(width, height)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val rect = keyframes.interpolateAt(presentationTimeUs) ?: referenceRect
        return Matrix().apply {
            // Media3 rekent in genormaliseerde apparaatcoördinaten: -1..1, y omhoog.
            // Het model rekent in 0..1 met y omlaag. Vandaar de omkering van y.
            val centerX = rect.centerX * 2f - 1f
            val centerY = 1f - rect.centerY * 2f

            // Eerst de uitsnede naar het midden schuiven, dan opblazen tot hij het
            // hele beeld vult. In deze volgorde: post* stapelt van links naar rechts.
            postTranslate(-centerX, -centerY)
            postScale(1f / rect.width.coerceAtLeast(MIN_EXTENT), 1f / rect.height.coerceAtLeast(MIN_EXTENT))
        }
    }

    private companion object {
        /** Voorkomt een deling door nul bij een ontaarde rechthoek. */
        const val MIN_EXTENT = 1e-4f
    }
}

private fun Int.roundDownToEven(): Int = this - (this % 2)

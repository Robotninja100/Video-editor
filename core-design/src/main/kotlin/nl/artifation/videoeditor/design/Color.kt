package nl.artifation.videoeditor.design

import kotlin.math.pow

/**
 * Kleur als ARGB, los van Android.
 *
 * Pure JVM, zodat de hele kleurstelling — inclusief contrastcontroles — getest
 * kan worden zonder toestel. De Compose-laag zet dit één op één om naar
 * `androidx.compose.ui.graphics.Color`.
 */
@JvmInline
public value class Argb(public val value: Int) {

    public val alpha: Int get() = (value ushr 24) and 0xFF
    public val red: Int get() = (value ushr 16) and 0xFF
    public val green: Int get() = (value ushr 8) and 0xFF
    public val blue: Int get() = value and 0xFF

    public val alphaFraction: Float get() = alpha / 255f

    public fun withAlpha(fraction: Float): Argb {
        val a = (fraction.coerceIn(0f, 1f) * 255f).toInt()
        return Argb((a shl 24) or (value and 0x00FFFFFF))
    }

    /** `#AARRGGBB`, handig in tests en foutmeldingen. */
    override fun toString(): String = "#%08X".format(value)

    public companion object {
        public fun of(hex: Long): Argb = Argb(hex.toInt())
    }
}

/**
 * Contrast volgens WCAG 2.1.
 *
 * Dit is de reden dat deze module bestaat. Een glass-UI legt tekst op een
 * doorschijnend vlak boven wisselende videobeelden; zonder harde ondergrens
 * wordt een deel van de interface onleesbaar zodra er een licht frame onder
 * schuift. De tests leggen die ondergrens vast.
 */
public object Contrast {

    /** WCAG AA voor normale tekst. */
    public const val AA_NORMAL: Double = 4.5

    /** WCAG AA voor grote tekst en UI-componenten. */
    public const val AA_LARGE: Double = 3.0

    /**
     * Legt [foreground] over [background] met de alpha van de voorgrond.
     * Nodig omdat glass-lagen per definitie doorschijnend zijn.
     */
    public fun composite(foreground: Argb, background: Argb): Argb {
        val a = foreground.alphaFraction
        fun blend(f: Int, b: Int) = (f * a + b * (1 - a)).toInt().coerceIn(0, 255)
        return Argb(
            (0xFF shl 24) or
                (blend(foreground.red, background.red) shl 16) or
                (blend(foreground.green, background.green) shl 8) or
                blend(foreground.blue, background.blue),
        )
    }

    /** Relatieve luminantie volgens WCAG. */
    public fun luminance(color: Argb): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** Contrastverhouding tussen 1:1 en 21:1. Beide kleuren moeten dekkend zijn. */
    public fun ratio(a: Argb, b: Argb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Contrast van doorschijnende tekst op een doorschijnend vlak boven een
     * ondergrond — het geval dat in een glass-UI daadwerkelijk voorkomt.
     */
    public fun effectiveRatio(text: Argb, glass: Argb, backdrop: Argb): Double {
        val surface = composite(glass, backdrop)
        return ratio(composite(text, surface), surface)
    }
}

/**
 * Waarneembaar kleurverschil.
 *
 * Contrastverhouding meet luminantie en zegt niets over kleur: blauw en paars
 * kunnen 1,04:1 scoren en toch moeiteloos uit elkaar te houden zijn. Voor
 * kleuren die informatie dragen — de tracks op de tijdlijn — is ΔE in
 * CIELAB het juiste gereedschap.
 */
public object ColorDifference {

    /** Vuistregel: ΔE onder ~10 is subtiel, boven ~25 duidelijk verschillend. */
    public const val CLEARLY_DISTINCT: Double = 25.0

    /** ΔE*ab (CIE76). Simpel, en ruim voldoende om een palet te toetsen. */
    public fun deltaE(a: Argb, b: Argb): Double {
        val (l1, a1, b1) = toLab(a)
        val (l2, a2, b2) = toLab(b)
        val dl = l1 - l2
        val da = a1 - a2
        val db = b1 - b2
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }

    internal fun toLab(color: Argb): Triple<Double, Double, Double> {
        fun linear(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        val r = linear(color.red)
        val g = linear(color.green)
        val bl = linear(color.blue)

        // sRGB naar XYZ met D65-witpunt.
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * bl
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883

        fun f(t: Double): Double =
            if (t > 0.008856) t.pow(1.0 / 3.0) else (7.787 * t) + (16.0 / 116.0)

        val fx = f(x)
        val fy = f(y)
        val fz = f(z)

        return Triple(
            116.0 * fy - 16.0,
            500.0 * (fx - fy),
            200.0 * (fy - fz),
        )
    }
}

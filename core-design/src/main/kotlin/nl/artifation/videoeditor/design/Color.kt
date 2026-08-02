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

    public val alpha: Int get() = (value ushr ALPHA_SHIFT) and CHANNEL_MASK
    public val red: Int get() = (value ushr RED_SHIFT) and CHANNEL_MASK
    public val green: Int get() = (value ushr GREEN_SHIFT) and CHANNEL_MASK
    public val blue: Int get() = value and CHANNEL_MASK

    public val alphaFraction: Float get() = alpha / CHANNEL_MAX_F

    public fun withAlpha(fraction: Float): Argb {
        val a = (fraction.coerceIn(0f, 1f) * CHANNEL_MAX_F).toInt()
        return Argb((a shl ALPHA_SHIFT) or (value and RGB_MASK))
    }

    /** `#AARRGGBB`, handig in tests en foutmeldingen. */
    override fun toString(): String = "#%08X".format(value)

    public companion object {
        internal const val ALPHA_SHIFT: Int = 24
        internal const val RED_SHIFT: Int = 16
        internal const val GREEN_SHIFT: Int = 8
        internal const val CHANNEL_MASK: Int = 0xFF
        internal const val CHANNEL_MAX: Int = 255
        internal const val CHANNEL_MAX_F: Float = 255f

        /** Alles behalve alpha; nodig om alleen de alpha te vervangen. */
        internal const val RGB_MASK: Int = 0x00FFFFFF

        public fun of(hex: Long): Argb = Argb(hex.toInt())
    }
}

/**
 * De sRGB-transferfunctie en de luminantieweging.
 *
 * Staat apart omdat contrast (WCAG) en kleurverschil (CIELAB) allebei beginnen
 * met dezelfde stap: de gamma-gecodeerde kanaalwaarde terugrekenen naar licht.
 * Eén implementatie, twee gebruikers.
 */
private object Srgb {

    /**
     * De knik tussen het lineaire en het macht-deel van de curve.
     *
     * Twee waarden, en dat is geen slordigheid: WCAG 2.x noemt in zijn
     * contrastformule letterlijk 0.03928, terwijl de sRGB-specificatie zelf
     * 0.04045 aanhoudt. Het verschil is verwaarloosbaar, maar de contrasttests
     * toetsen tegen de WCAG-formule, dus die krijgt de WCAG-waarde.
     */
    const val WCAG_KNEE: Double = 0.03928
    const val SRGB_KNEE: Double = 0.04045

    const val LINEAR_SLOPE: Double = 12.92
    const val GAMMA_OFFSET: Double = 0.055
    const val GAMMA_SCALE: Double = 1.055
    const val GAMMA: Double = 2.4

    const val CHANNEL_MAX: Double = 255.0

    /** Bijdrage per kanaal aan luminantie; groen doet verreweg het meeste. */
    const val LUMA_RED: Double = 0.2126
    const val LUMA_GREEN: Double = 0.7152
    const val LUMA_BLUE: Double = 0.0722

    fun linear(channel: Int, knee: Double): Double {
        val c = channel / CHANNEL_MAX
        return if (c <= knee) c / LINEAR_SLOPE else ((c + GAMMA_OFFSET) / GAMMA_SCALE).pow(GAMMA)
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

    /** Voorkomt deling door nul bij zwart, en drukt de schaal naar maximaal 21:1. */
    private const val RATIO_OFFSET: Double = 0.05

    /**
     * Legt [foreground] over [background] met de alpha van de voorgrond.
     * Nodig omdat glass-lagen per definitie doorschijnend zijn.
     */
    public fun composite(foreground: Argb, background: Argb): Argb {
        val a = foreground.alphaFraction
        fun blend(f: Int, b: Int) = (f * a + b * (1 - a)).toInt().coerceIn(0, Argb.CHANNEL_MAX)
        return Argb(
            (Argb.CHANNEL_MASK shl Argb.ALPHA_SHIFT) or
                (blend(foreground.red, background.red) shl Argb.RED_SHIFT) or
                (blend(foreground.green, background.green) shl Argb.GREEN_SHIFT) or
                blend(foreground.blue, background.blue),
        )
    }

    /** Relatieve luminantie volgens WCAG. */
    public fun luminance(color: Argb): Double {
        fun channel(value: Int): Double = Srgb.linear(value, Srgb.WCAG_KNEE)
        return Srgb.LUMA_RED * channel(color.red) +
            Srgb.LUMA_GREEN * channel(color.green) +
            Srgb.LUMA_BLUE * channel(color.blue)
    }

    /** Contrastverhouding tussen 1:1 en 21:1. Beide kleuren moeten dekkend zijn. */
    public fun ratio(a: Argb, b: Argb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + RATIO_OFFSET) / (darker + RATIO_OFFSET)
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

    // sRGB naar XYZ, D65-witpunt. De middelste rij is de luminantieweging en
    // staat daarom in [Srgb]; alleen X en Z hebben eigen coëfficiënten nodig.
    private const val X_FROM_RED: Double = 0.4124
    private const val X_FROM_GREEN: Double = 0.3576
    private const val X_FROM_BLUE: Double = 0.1805
    private const val Z_FROM_RED: Double = 0.0193
    private const val Z_FROM_GREEN: Double = 0.1192
    private const val Z_FROM_BLUE: Double = 0.9505

    /** Het D65-witpunt zelf; Y is per definitie 1. */
    private const val WHITE_X: Double = 0.95047
    private const val WHITE_Z: Double = 1.08883

    // De f()-functie uit de CIELAB-definitie: kubieke wortel, met een lineair
    // stuk vlak bij zwart zodat de afgeleide daar eindig blijft.
    private const val F_KNEE: Double = 0.008856
    private const val F_SLOPE: Double = 7.787
    private const val F_OFFSET_NUMERATOR: Double = 16.0
    private const val F_OFFSET_DENOMINATOR: Double = 116.0
    private const val CUBE_ROOT: Double = 1.0 / 3.0

    private const val L_SCALE: Double = 116.0
    private const val L_OFFSET: Double = 16.0
    private const val A_SCALE: Double = 500.0
    private const val B_SCALE: Double = 200.0

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
        fun linear(value: Int): Double = Srgb.linear(value, Srgb.SRGB_KNEE)

        val r = linear(color.red)
        val g = linear(color.green)
        val bl = linear(color.blue)

        val x = (X_FROM_RED * r + X_FROM_GREEN * g + X_FROM_BLUE * bl) / WHITE_X
        val y = Srgb.LUMA_RED * r + Srgb.LUMA_GREEN * g + Srgb.LUMA_BLUE * bl
        val z = (Z_FROM_RED * r + Z_FROM_GREEN * g + Z_FROM_BLUE * bl) / WHITE_Z

        fun f(t: Double): Double =
            if (t > F_KNEE) {
                t.pow(CUBE_ROOT)
            } else {
                (F_SLOPE * t) + (F_OFFSET_NUMERATOR / F_OFFSET_DENOMINATOR)
            }

        val fx = f(x)
        val fy = f(y)
        val fz = f(z)

        return Triple(
            L_SCALE * fy - L_OFFSET,
            A_SCALE * (fx - fy),
            B_SCALE * (fy - fz),
        )
    }
}

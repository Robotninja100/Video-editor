package nl.artifation.videoeditor.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArgbTest {

    @Test
    fun `kanalen worden correct uitgelezen`() {
        val color = Argb.of(0x80FF8040)

        assertEquals(0x80, color.alpha)
        assertEquals(0xFF, color.red)
        assertEquals(0x80, color.green)
        assertEquals(0x40, color.blue)
    }

    @Test
    fun `alpha aanpassen laat de kleur intact`() {
        val faded = Argb.of(0xFF0A84FF).withAlpha(0.5f)

        assertEquals(0x0A, faded.red)
        assertEquals(0x84, faded.green)
        assertEquals(0xFF, faded.blue)
        assertEquals(127, faded.alpha)
    }

    @Test
    fun `alpha wordt begrensd`() {
        assertEquals(255, Argb.of(0xFF000000).withAlpha(9f).alpha)
        assertEquals(0, Argb.of(0xFF000000).withAlpha(-1f).alpha)
    }
}

class ContrastMathTest {

    @Test
    fun `zwart op wit is de maximale verhouding`() {
        val ratio = Contrast.ratio(Argb.of(0xFF000000), Argb.of(0xFFFFFFFF))
        assertEquals(21.0, ratio, 0.01)
    }

    @Test
    fun `een kleur met zichzelf geeft 1 op 1`() {
        assertEquals(1.0, Contrast.ratio(Argb.of(0xFF123456), Argb.of(0xFF123456)), 1e-9)
    }

    @Test
    fun `de verhouding is symmetrisch`() {
        val a = Argb.of(0xFF0A84FF)
        val b = Argb.of(0xFF07070A)
        assertEquals(Contrast.ratio(a, b), Contrast.ratio(b, a), 1e-9)
    }

    @Test
    fun `compositie met volledige alpha geeft de voorgrond`() {
        val result = Contrast.composite(Argb.of(0xFFAABBCC), Argb.of(0xFF000000))
        assertEquals(0xAA, result.red)
        assertEquals(0xCC, result.blue)
    }

    @Test
    fun `compositie met alpha nul geeft de achtergrond`() {
        val result = Contrast.composite(Argb.of(0x00FFFFFF), Argb.of(0xFF102030))
        assertEquals(0x10, result.red)
        assertEquals(0x30, result.blue)
    }
}

/**
 * Dit is de kern van deze module: een glass-UI legt tekst op doorschijnende
 * vlakken boven wisselende videobeelden. Zonder harde ondergrens wordt een deel
 * van de interface onleesbaar zodra er een licht frame onder schuift.
 */
class PaletteReadabilityTest {

    private val backdrops = listOf(
        Argb.of(0xFF07070A) to "de eigen achtergrond",
        Argb.of(0xFF000000) to "een zwart videoframe",
        Argb.of(0xFF808080) to "een middengrijs videoframe",
        Argb.of(0xFFFFFFFF) to "een uitgebrand wit videoframe",
    )

    @Test
    fun `primaire tekst is leesbaar op elk glaslaagje boven elk videoframe`() {
        for (level in Tokens.GlassLevel.entries) {
            for ((backdrop, omschrijving) in backdrops) {
                val ratio = Contrast.effectiveRatio(
                    text = Tokens.Palette.textPrimary,
                    glass = level.scrim(),
                    backdrop = backdrop,
                )
                assertTrue(
                    ratio >= Contrast.AA_NORMAL,
                    "$level op $omschrijving haalt maar %.2f:1".format(ratio),
                )
            }
        }
    }

    @Test
    fun `secundaire tekst haalt minstens de grens voor grote tekst`() {
        for (level in Tokens.GlassLevel.entries) {
            for ((backdrop, omschrijving) in backdrops) {
                val ratio = Contrast.effectiveRatio(
                    text = Tokens.Palette.textSecondary,
                    glass = level.scrim(),
                    backdrop = backdrop,
                )
                assertTrue(
                    ratio >= Contrast.AA_LARGE,
                    "$level op $omschrijving haalt maar %.2f:1".format(ratio),
                )
            }
        }
    }

    @Test
    fun `tekst op de eigen achtergrond haalt ruim AA`() {
        val ratio = Contrast.ratio(Tokens.Palette.textPrimary, Tokens.Palette.backdrop)
        assertTrue(ratio >= 15.0, "kreeg %.2f:1".format(ratio))
    }

    @Test
    fun `accentkleuren zijn zichtbaar op de achtergrond`() {
        val accents = mapOf(
            "accent" to Tokens.Palette.accent,
            "accentAlt" to Tokens.Palette.accentAlt,
            "destructive" to Tokens.Palette.destructive,
            "success" to Tokens.Palette.success,
            "warning" to Tokens.Palette.warning,
        )

        for ((naam, kleur) in accents) {
            val ratio = Contrast.ratio(kleur, Tokens.Palette.backdrop)
            assertTrue(ratio >= Contrast.AA_LARGE, "$naam haalt maar %.2f:1".format(ratio))
        }
    }

    /**
     * Contrastverhouding is hier het verkeerde gereedschap: blauw en paars kunnen
     * 1,04:1 scoren en toch prima te onderscheiden zijn. Kleurverschil hoort in
     * een perceptuele ruimte gemeten te worden.
     */
    @Test
    fun `trackkleuren zijn onderling te onderscheiden`() {
        val colors = Tokens.Track.all
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                val delta = ColorDifference.deltaE(colors[i], colors[j])
                assertTrue(
                    delta >= ColorDifference.CLEARLY_DISTINCT,
                    "${colors[i]} en ${colors[j]} liggen te dicht bij elkaar (ΔE %.1f)".format(delta),
                )
            }
        }
    }
}

class GlassHierarchyTest {

    @Test
    fun `hogere lagen zijn sterker geblurd, getint en opgetild`() {
        val levels = Tokens.GlassLevel.entries

        levels.zipWithNext { lower, higher ->
            assertTrue(higher.blurDp > lower.blurDp, "$higher blurt niet sterker dan $lower")
            assertTrue(higher.scrimAlpha > lower.scrimAlpha, "$higher is niet dichter dan $lower")
            assertTrue(higher.borderAlpha > lower.borderAlpha, "$higher heeft geen sterkere rand")
            assertTrue(higher.elevationDp > lower.elevationDp, "$higher ligt niet hoger")
        }
    }

    @Test
    fun `glas blijft doorschijnend, anders is het gewoon een vlak`() {
        for (level in Tokens.GlassLevel.entries) {
            assertTrue(level.scrimAlpha < 0.85f, "$level is te dekkend om nog glas te heten")
        }
    }

    @Test
    fun `er zijn precies drie niveaus`() {
        assertEquals(3, Tokens.GlassLevel.entries.size, "meer niveaus maken hiërarchie onleesbaar")
    }
}

class ScaleTest {

    @Test
    fun `de ruimteschaal loopt strikt op`() {
        Tokens.Space.scale.zipWithNext { a, b -> assertTrue(b > a, "$b volgt niet op $a") }
    }

    @Test
    fun `de ruimteschaal is een veelvoud van vier`() {
        assertTrue(Tokens.Space.scale.all { it % 4 == 0 }, "buiten het 4-punts raster")
    }

    @Test
    fun `de radiusschaal loopt strikt op`() {
        Tokens.Radius.scale.zipWithNext { a, b -> assertTrue(b > a, "$b volgt niet op $a") }
    }

    @Test
    fun `de typeschaal loopt af van display naar mono`() {
        val sizes = Tokens.Type.entries.map { it.sizeSp }
        sizes.zipWithNext { a, b -> assertTrue(b <= a, "typeschaal springt omhoog: $a -> $b") }
    }

    @Test
    fun `regelhoogte is altijd groter dan de tekengrootte`() {
        for (type in Tokens.Type.entries) {
            assertTrue(type.lineHeightSp > type.sizeSp, "$type heeft te krappe regelhoogte")
        }
    }

    @Test
    fun `bewegingsduur blijft binnen wat snel aanvoelt`() {
        Tokens.Motion.scale.zipWithNext { a, b -> assertTrue(b > a, "$b volgt niet op $a") }
        assertTrue(Tokens.Motion.scale.max() <= 320, "te traag voor een editor")
    }

    @Test
    fun `aanraakdoelen halen de minimummaat`() {
        assertTrue(Tokens.Layout.MIN_TOUCH_DP >= 44, "te klein om te bedienen op een telefoon")
        assertTrue(Tokens.Layout.TOOLBAR_HEIGHT_DP >= Tokens.Layout.MIN_TOUCH_DP)
        assertTrue(Tokens.Layout.TRACK_HEIGHT_DP >= Tokens.Layout.MIN_TOUCH_DP)
    }

    @Test
    fun `de tijdlijn past de linialen en tracks`() {
        val minimum = Tokens.Layout.RULER_HEIGHT_DP + Tokens.Layout.TRACK_HEIGHT_DP * 2
        assertTrue(
            Tokens.Layout.TIMELINE_HEIGHT_DP >= minimum,
            "tijdlijn te laag voor liniaal plus twee tracks",
        )
    }
}

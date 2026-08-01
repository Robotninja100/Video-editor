package nl.artifation.videoeditor.design

/**
 * De design tokens.
 *
 * Eén bron voor kleur, glas, ruimte, radii, typografie en beweging. De
 * Compose-laag leest hier alles uit en verzint zelf niets — dat is wat een
 * interface samenhangend houdt in plaats van "elk scherm net iets anders".
 *
 * De stijl is donker en doorschijnend: een video-editor toont beeld, en elke
 * pixel chroom die niet nodig is, concurreert daarmee. Panelen liggen als
 * matglas over de video in plaats van hem weg te duwen.
 */
public object Tokens {

    /**
     * Basiskleuren.
     *
     * Donker als uitgangspunt, niet als variant: bij videobewerking is een
     * lichte interface hinderlijk en vertekent hij je oordeel over belichting.
     */
    public object Palette {
        /** Achter alles. Bijna zwart met een spoor blauw — puur zwart oogt dood. */
        public val backdrop: Argb = Argb.of(0xFF07070A)
        public val backdropRaised: Argb = Argb.of(0xFF0E0E13)

        public val textPrimary: Argb = Argb.of(0xFFFFFFFF)
        public val textSecondary: Argb = Argb.of(0xB3FFFFFF)
        public val textTertiary: Argb = Argb.of(0x73FFFFFF)

        public val accent: Argb = Argb.of(0xFF0A84FF)
        public val accentAlt: Argb = Argb.of(0xFF5E5CE6)
        public val destructive: Argb = Argb.of(0xFFFF375F)
        public val success: Argb = Argb.of(0xFF30D158)
        public val warning: Argb = Argb.of(0xFFFF9F0A)
    }

    /**
     * Kleur per tracksoort op de tijdlijn.
     *
     * Kleur is hier informatie, geen decoratie: je moet in één oogopslag zien
     * wat voor materiaal ergens staat.
     */
    public object Track {
        public val video: Argb = Argb.of(0xFF0A84FF)
        public val audio: Argb = Argb.of(0xFF30D158)
        public val caption: Argb = Argb.of(0xFFFF9F0A)
        /** Magenta en niet paars: paars ligt in Lab-ruimte te dicht bij het blauw. */
        public val mask: Argb = Argb.of(0xFFF25CC1)

        public val all: List<Argb> = listOf(video, audio, caption, mask)
    }

    /**
     * De glaslagen.
     *
     * Drie niveaus, en niet meer. Meer niveaus maken de hiërarchie onleesbaar in
     * plaats van rijker.
     *
     * Belangrijk: het glas **verdonkert** wat erachter ligt, het tint niet wit.
     * Dat is de valkuil van glassmorphism — een wit tintje ziet er mooi uit boven
     * een donker beeld en maakt de tekst onleesbaar zodra er een licht frame
     * onder schuift. Een donkere scrim garandeert leesbaarheid over willekeurig
     * videomateriaal; de glans en de rand geven het daarna zijn glasgevoel.
     */
    public enum class GlassLevel(
        /** Blur-straal in dp die op de achtergrond wordt toegepast. */
        public val blurDp: Int,
        /** Zwarte scrim over de geblurde achtergrond. Dit doet het leeswerk. */
        public val scrimAlpha: Float,
        /** Subtiele witte glans erbovenop; puur voor het materiaalgevoel. */
        public val sheenAlpha: Float,
        /** Rand die de kaart van zijn ondergrond scheidt en dikte suggereert. */
        public val borderAlpha: Float,
        public val elevationDp: Int,
    ) {
        /** Rustige panelen die grotendeels achtergrond zijn: tijdlijnbalk, zijpaneel. */
        Recessed(blurDp = 20, scrimAlpha = 0.56f, sheenAlpha = 0.03f, borderAlpha = 0.06f, elevationDp = 0),

        /** Het standaardpaneel: gereedschapsbalken, kaarten, inspector. */
        Floating(blurDp = 32, scrimAlpha = 0.64f, sheenAlpha = 0.05f, borderAlpha = 0.12f, elevationDp = 8),

        /** Alles wat de aandacht opeist: dialogen, menu's, exportvoortgang. */
        Overlay(blurDp = 48, scrimAlpha = 0.74f, sheenAlpha = 0.07f, borderAlpha = 0.20f, elevationDp = 24),
        ;

        /** De scrim die daadwerkelijk over de achtergrond gaat. */
        public fun scrim(): Argb = Argb.of(0xFF000000).withAlpha(scrimAlpha)

        public fun sheen(): Argb = Palette.textPrimary.withAlpha(sheenAlpha)

        public fun border(): Argb = Palette.textPrimary.withAlpha(borderAlpha)
    }

    /** Ruimte in dp. Een 4-punts schaal; alles daartussen is willekeur. */
    public object Space {
        public const val XS: Int = 4
        public const val S: Int = 8
        public const val M: Int = 12
        public const val L: Int = 16
        public const val XL: Int = 24
        public const val XXL: Int = 32

        public val scale: List<Int> = listOf(XS, S, M, L, XL, XXL)
    }

    /**
     * Hoekradii. Ruim, want dat is wat glas zacht maakt in plaats van scherp.
     */
    public object Radius {
        public const val SMALL: Int = 8
        public const val MEDIUM: Int = 14
        public const val LARGE: Int = 22
        public const val SHEET: Int = 28
        /** Voor pillen en knoppen die volledig rond moeten zijn. */
        public const val FULL: Int = 999

        public val scale: List<Int> = listOf(SMALL, MEDIUM, LARGE, SHEET)
    }

    /** Typografische schaal in sp, met de bijbehorende regelhoogte. */
    public enum class Type(public val sizeSp: Int, public val lineHeightSp: Int, public val weight: Int) {
        Display(32, 38, 700),
        Title(22, 28, 650),
        Headline(17, 22, 600),
        Body(15, 20, 400),
        Label(13, 17, 500),
        /** Voor timecodes; tabulaire cijfers zodat ze niet verspringen. */
        Mono(12, 16, 500),
        ;

        public val scale: List<Type> get() = entries
    }

    /**
     * Beweging.
     *
     * Kort en onopvallend. Animaties in een editor moeten de aandacht volgen,
     * niet vragen — alles boven ~300 ms voelt traag zodra je aan het monteren bent.
     */
    public object Motion {
        public const val INSTANT_MS: Int = 90
        public const val QUICK_MS: Int = 160
        public const val STANDARD_MS: Int = 240
        public const val SHEET_MS: Int = 320

        /** Standaard easing-curve (cubic-bezier), Apple-achtig: snel weg, zacht aan. */
        public val standardEasing: List<Float> = listOf(0.32f, 0.72f, 0f, 1f)

        public val scale: List<Int> = listOf(INSTANT_MS, QUICK_MS, STANDARD_MS, SHEET_MS)
    }

    /** Vaste maten van de editor-UI. */
    public object Layout {
        public const val TIMELINE_HEIGHT_DP: Int = 220
        public const val TRACK_HEIGHT_DP: Int = 56
        public const val RULER_HEIGHT_DP: Int = 28
        public const val TOOLBAR_HEIGHT_DP: Int = 56
        public const val PLAYHEAD_WIDTH_DP: Int = 2
        /** Minimale aanraakmaat; kleiner is op een telefoon niet te bedienen. */
        public const val MIN_TOUCH_DP: Int = 44
    }
}

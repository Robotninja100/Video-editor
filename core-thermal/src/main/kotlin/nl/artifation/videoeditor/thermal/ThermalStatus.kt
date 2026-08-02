package nl.artifation.videoeditor.thermal

/**
 * Thermische toestand van het toestel, oplopend in ernst.
 *
 * Bewust een eigen enum en geen `android.os.PowerManager.THERMAL_STATUS_*`:
 * deze module is pure JVM en moet zonder toestel of Android-SDK te testen zijn.
 * De niveaus komen wel één-op-één overeen met die van Android, zodat de
 * Android-laag een gemeten niveau met [fromAndroidLevel] kan doorgeven zonder
 * dat er elders een vertaaltabel nodig is.
 *
 * De volgorde is die van oplopende ernst; daardoor mag je waarden met `<` en
 * `>` vergelijken, en dat is precies wat de drempels in [ThermalPolicy] doen.
 */
public enum class ThermalStatus {
    /** Geen belasting: het toestel kan alles kwijt. */
    NONE,

    /** Lichte belasting: nog geen terugschakeling, maar de warmte loopt op. */
    LIGHT,

    /** Matige belasting: de chip schakelt terug, werk komt trager binnen. */
    MODERATE,

    /** Ernstige belasting: doorwerken levert nauwelijks nog snelheidswinst op. */
    SEVERE,

    /** Kritiek: het toestel beperkt zichzelf hard. */
    CRITICAL,

    /** Noodgeval: alleen nog ruimte voor bellen en noodfuncties. */
    EMERGENCY,

    /** Afsluiten: het toestel gaat zichzelf uitzetten. */
    SHUTDOWN,
    ;

    /** Handiger te lezen dan `this >= other` op de plekken waar de drempel een parameter is. */
    public fun atLeast(other: ThermalStatus): Boolean = this >= other

    public companion object {

        /**
         * Vertaalt een Android-niveau (`PowerManager.THERMAL_STATUS_*`, 0..6).
         *
         * Een onbekend niveau (-1) telt als [NONE] — dat is wat Android zelf ook
         * teruggeeft als het niveau niet te bepalen is. Een niveau bóven het
         * hoogst bekende telt als [SHUTDOWN]: bij een nieuwer Android met een
         * extra niveau is de veiligste aanname dat het erger is, niet minder erg.
         */
        public fun fromAndroidLevel(level: Int): ThermalStatus = when {
            level <= 0 -> NONE
            level >= SHUTDOWN.ordinal -> SHUTDOWN
            else -> entries[level]
        }
    }
}

package nl.artifation.videoeditor.pipeline

import nl.artifation.videoeditor.thermal.ThermalStatus

/** Een thermometer die je zelf verdraait. */
internal class Thermometer(
    var status: ThermalStatus = ThermalStatus.NONE,
) : ThermalStatusSource {
    override fun current(): ThermalStatus = status
}

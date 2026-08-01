# Video-editor

AI-video-editor voor Android, voor eigen gebruik. Doelfeatures: object tracking met
auto-blur, auto-ondertiteling, auto-knippen op stiltes, auto-reframe naar 9:16 en
auto-edit.

Het volledige plan met architectuur, roadmap en risico's staat in
**[docs/BOUWPLAN.md](docs/BOUWPLAN.md)**.

## Huidige stand

Vier modules gebouwd en getest; twee geschreven maar niet gecompileerd.

| Module | Status | Inhoud |
|---|---|---|
| `:core-model` | ✅ gebouwd & getest | Timeline-model, bewerkingen, rendercontract, tijdlijn-geometrie, undo, persistentie |
| `:core-analysis` | ✅ gebouwd & getest | Stiltes, scenedetectie, reframe-smoothing, EBU R128-loudness, captions, cut-list |
| `:core-remote` | ✅ gebouwd & getest | Transcriptie, segmentatie + RLE, auto-edit — contracten en parsing |
| `:core-design` | ✅ gebouwd & getest | Glaslagen, palet, typeschaal, contrast- en ΔE-validatie |
| `:core-render` | 📝 geschreven, niet gebouwd | `CompositionMapper`, `MaskedBlurShaderProgram`, `MaskVideoDecoder` |
| `:app` | 📝 geschreven, niet gebouwd | Compose glass-componenten, tijdlijn-canvas |

De vier `core-`modules zijn bewust pure JVM. Daardoor draaien **190 tests** zonder
emulator of toestel, en dat dekt precies waar stille regressies zitten:
tijdlijnrekenwerk, DSP, coördinaatomrekening en het parsen van antwoorden van
diensten die je niet in de hand hebt.

## Bouwen

```bash
./gradlew test          # alle unittests (190, pure JVM)
./gradlew :core-model:test
```

### De Android-modules staan bewust uit

`:app` en `:core-render` bestaan wel als broncode, maar staan **niet** in
`settings.gradle.kts` en hun `build.gradle.kts` heet `.disabled`. Reden: de
omgeving waarin ze geschreven zijn had geen Android SDK (`dl.google.com` is
geblokkeerd op organisatiebeleid), dus die code is nooit gecompileerd. Ze in de
build zetten zou `./gradlew test` laten falen en een valse indruk van
"het werkt" geven.

Aanzetten zodra je een omgeving met de Android SDK hebt:

```bash
mv app/build.gradle.kts.disabled app/build.gradle.kts
mv core-render/build.gradle.kts.disabled core-render/build.gradle.kts
# haal de commentaartekens weg bij de include()-regels in settings.gradle.kts
```

Reken erop dat er dan compilatiefouten uit komen — ongecompileerde code heeft ze
altijd. Zie fase 0 in het bouwplan voor wat er daarna bewezen moet worden.

## Ontwerpprincipes

Twee dingen die je bij elke wijziging moet aanhouden — de rest volgt daaruit.

**AI raakt de renderloop nooit.** Analyse gebeurt vooraf en schrijft sidecar-bestanden
weg naast het bronmateriaal. De renderpipeline leest die bestanden alleen. Geen
modelinferentie tijdens playback, ooit.

Daardoor is het ook een vrije keuze of een analyse op het toestel of in de cloud
draait: de sidecar-formaten zijn identiek, dus de renderpipeline merkt het verschil
niet. Transcriptie en segmentatie gaan naar externe diensten, stiltedetectie en
reframe-detectie blijven lokaal. Zie het bouwplan voor de afweging.

**Media3 is de backend, niet het projectformaat.** `:core-model` bevat geen enkel
Media3-type. De vertaling gebeurt in één functie in `:core-render`. Zolang dat het
enige koppelvlak blijft, kan Media3 geüpgraded worden zonder de editor te herschrijven
— relevant, want `CompositionPlayer` is nog experimenteel.

Verder: alle tijden in **microseconden**, nooit in framenummers (telefoonopnames zijn
vaak variable framerate). Effectparameters in **genormaliseerde eenheden**, nooit in
pixels, anders zien preview en export er verschillend uit.

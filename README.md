# Video-editor

AI-video-editor voor Android, voor eigen gebruik. Doelfeatures: object tracking met
auto-blur, auto-ondertiteling, auto-knippen op stiltes, auto-reframe naar 9:16 en
auto-edit.

Het volledige plan met architectuur, roadmap en risico's staat in
**[docs/BOUWPLAN.md](docs/BOUWPLAN.md)**.

## Huidige stand

Tien modules gebouwd en getest; twee compleet geschreven maar nooit gecompileerd.

| Module | Status | Inhoud |
|---|---|---|
| `:core-model` | ✅ | Timeline, bewerkingen, rendercontract, tijdlijn-geometrie, undo, persistentie |
| `:core-analysis` | ✅ | Stiltes, scenes, reframe-smoothing, EBU R128-loudness, captions, cut-list |
| `:core-remote` | ✅ | Transcriptie, segmentatie + RLE, auto-edit — contracten en parsing |
| `:core-design` | ✅ | Glaslagen, palet, schalen, contrast- en ΔE-validatie |
| `:core-library` | ✅ | Mediacatalogus, sidecar-paden, verouderingslogica |
| `:core-project` | ✅ | Projectopslag, schemamigratie, autosave, crashherstel |
| `:core-jobs` | ✅ | Wachtrij, toestandsmachine, backoff, voortgang |
| `:core-errors` | ✅ | Foutentaxonomie, retry-beleid, gebruikersteksten |
| `:core-thermal` | ✅ | Thermisch beleid, hysterese, blokplanner |
| `:core-pipeline` | ✅ | **De koppeling**: thermische rem op de wachtrij, analyseplanner, integratietests |
| `:core-render` | 📝 compleet, niet gebouwd | `CompositionMapper`, `MaskedBlurShaderProgram`, `MaskVideoDecoder` |
| `:app` | 📝 compleet, niet gebouwd | Activity, state-houder, glas-UI, tijdlijn-canvas, resources |

Alle `core-`modules zijn bewust pure JVM. Daardoor draaien **786 tests** zonder
emulator of toestel, en dat dekt precies waar stille regressies zitten:
tijdlijnrekenwerk, DSP, coördinaatomrekening, toestandsmachines en het parsen van
antwoorden van diensten die je niet in de hand hebt.

De modules kennen elkaar bewust niet — `:core-jobs` weet niets van temperatuur,
`:core-thermal` niets van taken. Dat houdt ze los testbaar, maar het betekent ook
dat de koppeling ergens moet gebeuren. Dat is `:core-pipeline`, en die bewijst met
integratietests dat ze samen doen wat de bedoeling is.

## Bouwen

```bash
./gradlew controle      # statische analyse, alle tests, dekkingsrapport
./gradlew test          # alleen de unittests (739, pure JVM)
./gradlew :core-model:test
```

De ondergrens staat op 90% regeldekking. Detekt draait met een basislijn per
module in `config/detekt/`, en **alle tien staan leeg**. Dat is het punt: een
bevroren basislijn is prima voor een dag, maar daarna verdwijnt elk nieuw
probleem tussen de oude. Waar een regel wél klopt maar het geval niet, staat een
`@Suppress` met de reden in de code — lokaal, leesbaar, en het verjaart niet.

### De app bouwen — dit moet op jouw machine

De Android-modules kunnen in deze omgeving niet gebouwd worden. Niet uit
tijdgebrek: `maven.google.com` en `dl.google.com` zijn allebei geblokkeerd op
organisatiebeleid, dus Compose, Media3 én de Android Gradle Plugin zijn
onbereikbaar. Maven Central spiegelt androidx niet (404) en apt heeft alleen
SDK-platform **API 23**, terwijl dit project minSdk 31 vraagt.

Op een machine mét internettoegang tot Google:

```bash
mv app/build.gradle.kts.disabled app/build.gradle.kts
mv core-render/build.gradle.kts.disabled core-render/build.gradle.kts
# haal de commentaartekens weg bij de include()-regels in settings.gradle.kts
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug
./gradlew :app:installDebug        # met je S24 Ultra aangesloten
```

Wat je dan krijgt: de app vraagt bij het starten om een video, zet die als clip
op de tijdlijn en speelt hem af met `CompositionPlayer` — precies het rondje dat
fase 0 moet bewijzen. Monteren zelf (knippen, slepen, effecten) zit in het model
en in de tijdlijn-UI, maar loopt nog niet door naar de export.

Reken erop dat de compiler dingen vindt: deze code is nooit gecompileerd. Wel is
elke Media3-aanroep nagelezen tegen de **bron van 1.10.1** in plaats van tegen
het geheugen. Dat leverde twee echte fouten op die anders pas op het toestel
waren opgevallen: `GlProgram(context, …)` leest shaders uit *assets* in plaats
van uit een string, en `EditedMediaItem.Builder.setSpeed` neemt een
`SpeedProvider`, geen getal.

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

# Video-editor

AI-video-editor voor Android, voor eigen gebruik. Doelfeatures: object tracking met
auto-blur, auto-ondertiteling, auto-knippen op stiltes, auto-reframe naar 9:16 en
auto-edit.

Het volledige plan met architectuur, roadmap en risico's staat in
**[docs/BOUWPLAN.md](docs/BOUWPLAN.md)**.

## Huidige stand

Tien modules gebouwd en getest; twee die compileren maar nooit op een toestel
hebben gedraaid.

| Module | Status | Inhoud |
|---|---|---|
| `:core-model` | ✅ | Timeline, bewerkingen, rendercontract, tijdlijn-geometrie, undo, persistentie, exportpresets |
| `:core-analysis` | ✅ | Stiltes, scenes, reframe-smoothing, EBU R128-loudness, captions, cue-correctie, cut-list |
| `:core-remote` | ✅ | Transcriptie, segmentatie + RLE, auto-edit — contracten en parsing |
| `:core-design` | ✅ | Glaslagen, palet, schalen, contrast- en ΔE-validatie |
| `:core-library` | ✅ | Mediacatalogus, sidecar-paden, verouderingslogica |
| `:core-project` | ✅ | Projectopslag, schemamigratie, autosave, crashherstel |
| `:core-jobs` | ✅ | Wachtrij, toestandsmachine, backoff, voortgang |
| `:core-errors` | ✅ | Foutentaxonomie, retry-beleid, gebruikersteksten |
| `:core-thermal` | ✅ | Thermisch beleid, hysterese, blokplanner |
| `:core-pipeline` | ✅ | **De koppeling**: thermische rem op de wachtrij, analyseplanner, integratietests |
| `:core-render` | 🔨 bouwt in CI | `CompositionMapper`, `MaskedBlurShaderProgram`, `MaskVideoDecoder`, `Exporter`, caption- en cropeffecten |
| `:app` | 🔨 bouwt in CI | Activity, state-houder, glas-UI, tijdlijn-canvas, resources |

Alle `core-`modules zijn bewust pure JVM. Daardoor draaien **834 tests** zonder
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
./gradlew test          # alleen de unittests (834, pure JVM)
./gradlew :core-model:test
```

De ondergrens staat op 90% regeldekking. Detekt draait met een basislijn per
module in `config/detekt/`, en **alle tien staan leeg**. Dat is het punt: een
bevroren basislijn is prima voor een dag, maar daarna verdwijnt elk nieuw
probleem tussen de oude. Waar een regel wél klopt maar het geval niet, staat een
`@Suppress` met de reden in de code — lokaal, leesbaar, en het verjaart niet.

### De app op je toestel krijgen

Twee wegen. De snelste kost geen Android SDK.

**1. De APK ophalen uit CI.** Elke push bouwt hem. Ga naar Actions → de laatste
run → onderaan bij **Artifacts** → `app-debug-apk`, pak het zip uit en:

```bash
adb install app-debug.apk
```

**2. Zelf bouwen.** Open het project in Android Studio; die schrijft
`local.properties` met `sdk.dir`, en dáármee doen de Android-modules mee — zie
`settings.gradle.kts`. Er hoeft niets hernoemd of ontkommentarieerd te worden.

```bash
./gradlew :app:installDebug        # met je S24 Ultra aangesloten
```

Zonder SDK blijven `:app` en `:core-render` buiten de build en draaien de
834 JVM-tests gewoon. Dat is geen theoretisch geval: de omgeving waarin deze code
geschreven is kan `maven.google.com` en `dl.google.com` niet bereiken —
geblokkeerd op organisatiebeleid — en heeft alleen SDK-platform **API 23**,
terwijl dit project minSdk 31 vraagt. Vandaar de derde CI-job: die runner mag wél
bij Google, en dáár gaat de Android-code langs een compiler.

Wil je die weg met de hand:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

### Wat de app doet, en wat niet

Bij het starten vraagt hij om een video, zet die als clip op de tijdlijn en
speelt hem af met `CompositionPlayer` — precies het rondje dat fase 0 moet
bewijzen. Monteren zelf (knippen, slepen, effecten) zit in het model en in de
tijdlijn-UI.

Knippen op de playhead, de geselecteerde clip verwijderen, ongedaan maken en
exporteren zitten in de bovenbalk. De exportknop schrijft hetzelfde renderplan
weg dat de preview afspeelt, met voortgang en annuleren, en met de bitrate uit
een `ExportPreset` die uit het project zelf wordt afgeleid. Preview en export
delen daarmee de complete effectketen — precies de opzet waarvan fase 0 moet
aantonen dat hij ook echt hetzelfde beeld oplevert.

Het bestand komt in de app-map (`Android/data/…/files/Movies`), niet in de
galerij: wegschrijven naar MediaStore vraagt om keuzes over mislukte exports die
bij de afwerking horen.

**Dat hij compileert betekent niet dat hij werkt.** Er is nooit een frame op een
toestel gerenderd. Wel is elke Media3-aanroep nagelezen tegen de **bron van
1.10.1** in plaats van tegen het geheugen, en dat leverde twee fouten op die
allebei meteen fataal waren: `GlProgram(context, …)` leest shaders uit *assets*
in plaats van uit een string, en `EditedMediaItem.Builder.setSpeed` neemt een
`SpeedProvider`, geen getal.

Wat er nog bewezen moet worden, staat in `docs/BOUWPLAN.md` onder Verificatie:
preview/export-pariteit, het teken van de in-point, en de richting van de
snelheidsomrekening in `sourcePtsFor`. Die drie kunnen alleen op het toestel.

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

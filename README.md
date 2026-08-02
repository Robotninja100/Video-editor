# Video-editor

AI-video-editor voor Android, voor eigen gebruik. Doelfeatures: object tracking met
auto-blur, auto-ondertiteling, auto-knippen op stiltes, auto-reframe naar 9:16 en
auto-edit.

Het volledige plan met architectuur, roadmap en risico's staat in
**[docs/BOUWPLAN.md](docs/BOUWPLAN.md)**. Wat er nog moet gebeuren, staat als
afvinkbare lijst in **[docs/CHECKLIST.md](docs/CHECKLIST.md)**.

## Huidige stand

| Module | Status | Inhoud |
|---|---|---|
| `:core-model` | ✅ | Timeline-model, bewerkingen, validatie, JSON, undo/redo, `RenderPlan` |
| `:core-analysis` | ✅ | Stiltedetectie, loudness (EBU R128), sidecars, scenedetectie, reframe-smoothing, auto-edit |
| `:core-render` | ✅ | `RenderPlan` → Media3, `MaskedBlurShaderProgram`, crop-pad, ondertitels |
| `:app` | 🔨 | Compose-tijdlijn en het Spike-scherm voor de fase 0-poort |
| `:ml-whisper` | ⬜ | whisper.cpp via JNI — wacht op de NDK |
| `:ml-tracking` | ⬜ | EdgeTAM via QNN — wacht op de NDK |

De renderlaag is geschreven en compileert tegen Media3 1.10.1, maar of het *beeld*
klopt is daarmee niet bewezen. Dat is wat de fase 0-poort meet, en die meting kan
alleen op een toestel: een bouwomgeving heeft geen hardwarecodecs en geen GPU.

`:core-model` en `:core-analysis` zijn bewust pure JVM. Daardoor draaien hun tests
zonder emulator of toestel, en dat is precies waar de stille regressies zitten:
tijdlijnrekenwerk en DSP.

Om dezelfde reden staat álle beslislogica van de renderer in `RenderPlan` — welke
clips, welke trims, welke effectvolgorde, en hoe brontijden naar cliptijden worden
omgerekend. `:core-render` vertaalt dat één op één naar Media3 en beslist
zelf niets. De vertaaltabel staat onderaan de checklist.

## Bouwen

```bash
./gradlew build              # alles bouwen en testen (193 tests)
./gradlew test               # alleen de pure-JVM modules
./gradlew :app:assembleDebug # installeerbare APK
```

De Android-modules vereisen de SDK met **platform 36** — Media3 1.10.1 eist dat, 35
is niet genoeg. Wijs hem aan met `ANDROID_HOME` of met `sdk.dir` in
`local.properties`. CI installeert hem zelf en bewaart de APK als artefact bij elke
build.

## De fase 0-poort draaien

De poort uit het bouwplan — pariteit tussen preview en export, en masked blur die
synchroon blijft op een getrimde clip — zit als scherm in de app. Installeer de APK,
open het tabblad **Fase 0** en druk op één knop. Het testmateriaal wordt op het
toestel zelf gemaakt; ffmpeg is nergens voor nodig.

De uitslag komt in gewone taal op het scherm, met de meetwaarden en een paar frames
erbij, zodat het ook met het oog te beoordelen is. Zakt bewijs 2, dan moet de opslag
van maskers terug naar de tekentafel — en dan is dat in week 1 duidelijk in plaats
van in week 12.

## Ontwerpprincipes

Twee dingen die je bij elke wijziging moet aanhouden — de rest volgt daaruit.

**AI raakt de renderloop nooit.** Analyse gebeurt offline en schrijft sidecar-bestanden
weg naast het bronmateriaal. De renderpipeline leest die bestanden alleen. Geen
modelinferentie tijdens playback, ooit.

**Media3 is de backend, niet het projectformaat.** `:core-model` bevat geen enkel
Media3-type. De vertaling gebeurt in één functie in `:core-render`. Zolang dat het
enige koppelvlak blijft, kan Media3 geüpgraded worden zonder de editor te herschrijven
— relevant, want `CompositionPlayer` is nog experimenteel.

Verder: alle tijden in **microseconden**, nooit in framenummers (telefoonopnames zijn
vaak variable framerate). Effectparameters in **genormaliseerde eenheden**, nooit in
pixels, anders zien preview en export er verschillend uit.

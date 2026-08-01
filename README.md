# Video-editor

AI-video-editor voor Android, voor eigen gebruik. Doelfeatures: object tracking met
auto-blur, auto-ondertiteling, auto-knippen op stiltes, auto-reframe naar 9:16 en
auto-edit.

Het volledige plan met architectuur, roadmap en risico's staat in
**[docs/BOUWPLAN.md](docs/BOUWPLAN.md)**.

## Huidige stand

De fundering staat: de twee modules die géén Android SDK nodig hebben, met tests.

| Module | Status | Inhoud |
|---|---|---|
| `:core-model` | ✅ | Timeline-model, bewerkingen, validatie, JSON-persistentie |
| `:core-analysis` | ✅ | Stiltedetectie (RMS + hysterese) |
| `:core-render` | ⬜ | `toComposition()`, `MaskedBlurShaderProgram` — vereist Android SDK |
| `:app` | ⬜ | Compose-UI en timeline |
| `:ml-whisper` | ⬜ | whisper.cpp via JNI |
| `:ml-tracking` | ⬜ | EdgeTAM via QNN |

`:core-model` en `:core-analysis` zijn bewust pure JVM. Daardoor draaien hun tests
zonder emulator of toestel, en dat is precies waar de stille regressies zitten:
tijdlijnrekenwerk en DSP.

## Bouwen

```bash
./gradlew test          # alle unittests (37, pure JVM)
./gradlew :core-model:test
```

De Android-modules worden toegevoegd zodra de Android SDK is geconfigureerd; zie
fase 0 in het bouwplan.

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

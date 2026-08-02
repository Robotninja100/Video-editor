# Werklijst

Afvinkbare uitwerking van de roadmap in **[BOUWPLAN.md](BOUWPLAN.md)**. Dat document
legt uit *waarom*; dit document is de lijst van *wat er nog moet gebeuren*.

Legenda: ✅ af · 🔨 in aanbouw · ⬜ nog niet begonnen · 🔒 geblokkeerd

---

## Blokkades

| Vereist | Waarvoor | Status |
|---|---|---|
| **Android SDK** | `:app`, `:core-render`, alle Media3-code | ✅ opgelost |
| **ffmpeg op de werkplek** | Testmateriaal voor fase 0 | ✅ niet meer nodig |
| **Samsung S24 Ultra** | Fase 0-poort, hardware-codecs, QNN/NPU | 🔒 blijft |
| **Android NDK** | whisper.cpp JNI (fase 3), EdgeTAM (fase 6) | 🔒 blijft |

De eerste twee zijn weg. De SDK kon geïnstalleerd worden zodra de Google-servers
bereikbaar waren; ffmpeg is overbodig geworden doordat het testmateriaal nu op het
toestel zelf gemaakt wordt, met `MediaCodec` en `Canvas`.

Wat níét op te lossen valt in een bouwomgeving: er is geen `/dev/kvm`, dus geen
bruikbare emulator, en hardwarecodecs bestaan alleen op hardware. Alles wat écht
door een codec of over de GPU moet, blijft daarom een meting op het toestel — nu
wel als een scherm in de app in plaats van als een spike op een werkplek.

Alles wat hieronder 🔒 heeft, wacht op het toestel.

---

## Fase 0 — Spike (de go/no-go-poort)

Niet doorgaan naar fase 1 zonder dat deze twee bewijzen er liggen. Zie
[BOUWPLAN.md §Verificatie](BOUWPLAN.md).

- [x] Gradle-opzet met pure-JVM modules ✅
- [x] `RenderPlan` — het exacte, testbare invoerformaat voor de renderer ✅
- [x] Android SDK configureren, `:core-render` en `:app` aan `settings.gradle.kts` ✅
- [x] Media3 1.10.1 als afhankelijkheid pinnen (`libs.versions.toml`) ✅
- [x] `RenderPlan.toComposition()` — domme vertaling naar Media3 (spec onderaan) ✅
- [x] Bronvideo + grayscale maskvideo genereren — op het toestel zelf, zonder ffmpeg ✅
- [x] `MaskedBlurShaderProgram`: tweede `MediaCodec` → `SurfaceTexture` → OES-texture ✅
- [x] Pull-based `advanceTo(targetUs)`, monotoon vooruit, geen seeks ✅
- [x] `updateTexImage()` op Transformer's GL-thread met de juiste EGL-context ✅
- [x] Spike-scherm in de app dat beide bewijzen meet en de uitslag toont ✅
- [ ] **Bewijs 1 — pariteit:** de meting draaien op de S24 Ultra 🔒
- [ ] **Bewijs 2 — masked blur:** de meting draaien op de S24 Ultra 🔒
- [ ] Go/no-go vastleggen. Faalt bewijs 2, dan terug naar de tekentafel voor maskopslag (RLE per frame of vormgebaseerd) 🔒

---

## Fase 1 — Skelet

- [x] Projectmodel: `Project`/`Sequence`/`TimelineItem`/`EffectSpec` ✅
- [x] Bewerkingen: split, ripple delete, lift, insert, move, overwrite, trim, normalize ✅
- [x] Validatie van modelinvarianten ✅
- [x] JSON-persistentie via kotlinx-serialization ✅
- [x] Undo/redo — snapshotstack ✅
- [x] `Project.toRenderPlan()` incl. cue-mapping en in-point-offsets ✅
- [x] Compose-timeline: scrub ✅
- [x] Compose-timeline: trim aan beide randen ✅
- [x] Compose-timeline: split op de playhead ✅
- [x] Compose-timeline: verplaatsen (drag-and-drop) en gaten tonen ✅
- [x] Export via `Transformer`, met voortgang en annuleren ✅
- [ ] Preview via `CompositionPlayer`, gekoppeld aan de playhead — nu alleen in de spike 🔒
- [ ] Tijdlijn koppelen aan echte media in plaats van de demo-sequence 🔒
- [ ] Projecten opslaan/laden op schijf, plus autosave 🔒
- [ ] Media importeren (SAF-picker), duur en resolutie uitlezen 🔒

---

## Fase 2 — Stiltes + audio

- [x] RMS-vensteranalyse met hysterese ✅
- [x] Minimale stiltelengte en padding ✅
- [x] `keepIntervals()` — complement, klaar om clips van te maken ✅
- [x] Downmix naar mono ✅
- [x] Sidecar-formaat per bronclip, met versienummer ✅
- [x] `SidecarStore` — lezen/schrijven, versiemismatch verwerpt de cache ✅
- [x] EBU R128 / ITU-R BS.1770-4 loudnessmeting (K-weighting, blokken, gating) ✅
- [x] Gain naar doelniveau berekenen ✅
- [x] Golden-WAV-testfixture zonder externe tooling ✅
- [ ] True peak met oversampling; nu wordt alleen de samplepiek gemeten
- [ ] Audio decoderen uit een bronbestand naar PCM (`MediaExtractor`/`MediaCodec`) 🔒
- [ ] WorkManager-analysepipeline die de sidecar vult en voortgang rapporteert 🔒
- [ ] Loudness toepassen als `AudioProcessor` in de renderketen 🔒
- [ ] UI: "knip stiltes weg" — voorstel tonen, per stilte accepteren/afwijzen 🔒

---

## Fase 3 — Captions

- [x] `Cue`-model met woordgrenzen (karaoke mogelijk) ✅
- [x] `CaptionStyle` in genormaliseerde eenheden ✅
- [x] Cue-mapping door trim en snelheid in het `RenderPlan` ✅
- [ ] whisper.cpp als submodule + CMake-build voor arm64-v8a 🔒
- [ ] JNI-laag: model laden, transcriberen, voortgang, annuleren 🔒
- [ ] Model `small` q5_1 bundelen of downloaden bij eerste gebruik 🔒
- [ ] Transcriptieresultaat naar `Cue`s met woordtijden 🔒
- [ ] VAD-correctie: cue-grenzen bijstellen op de stiltes uit fase 2 🔒
- [ ] `OverlayEffect` met per cue een gecachete `BitmapOverlay` 🔒
- [ ] Cache-invalidatie bij stijlwijziging 🔒
- [ ] UI: cues bewerken, stijl kiezen, timing corrigeren 🔒

---

## Fase 4 — Auto-edit

- [x] Genummerd transcript genereren voor de prompt ✅
- [x] Indices uit LLM-output parsen (tolerant voor omringende tekst en codefences) ✅
- [x] Validatie: buiten bereik, dubbel, ongesorteerd — met rapportage van wat verworpen is ✅
- [x] Indices → keep-intervallen, aangrenzende samengevoegd ✅
- [ ] Claude-API-aanroep, API-sleutel veilig opslaan 🔒
- [ ] Prompt schrijven en afstemmen (behoud van context, geen halve zinnen) 🔒
- [ ] Keep-intervallen omzetten naar een nieuwe `Sequence` en tonen als voorstel 🔒
- [ ] Foutafhandeling: geen netwerk, rate limit, lege respons 🔒

---

## Fase 5 — Reframe

- [x] Scenedetectie op frame-signatures ✅
- [x] Kritisch gedempte veer over het ruwe pad (geen overshoot) ✅
- [x] Deadzone tegen jitter bij een stilstaand onderwerp ✅
- [x] Harde sprong op scenegrenzen in plaats van doorzwiepen ✅
- [x] Clampen binnen het frame op de doel-aspect ✅
- [x] Decimatie naar `Keyframe<NormRect>`-pad ✅
- [ ] ML Kit face + pose detection, 1 frame per 200 ms 🔒
- [ ] Frames samplen uit het bronbestand en naar `FrameSignature` reduceren 🔒
- [ ] Onderwerpkeuze bij meerdere gezichten (grootste? sprekende?) 🔒
- [ ] Crop-pad in de sidecar en als `EffectSpec.Crop` op de clip 🔒
- [ ] `Presentation`/crop-effect in de renderketen 🔒
- [ ] UI: pad bekijken, handmatig keyframes bijstellen 🔒

---

## Fase 6 — Tracking

- [ ] EdgeTAM-model converteren en kwantiseren voor QNN 🔒
- [ ] QNN-delegate integreren (niet GPU) 🔒
- [ ] Tap-to-select: aanwijzen wat er getrackt wordt 🔒
- [ ] Maskvideo genereren en encoderen (halve resolutie, grayscale) 🔒
- [ ] Mask in de sidecar koppelen aan de bronclip 🔒
- [ ] `EffectSpec.MaskedBlur` aan de clip hangen — shader is al klaar uit fase 0 🔒
- [ ] Hertracken na een trim of split 🔒
- [ ] UI: voortgang, resultaat inspecteren, mask bijwerken 🔒

---

## Fase 7 — Afwerking

- [ ] Exportpresets (resolutie, bitrate, framerate) 🔒
- [ ] Thermal-chunking: exporteren in stukken, pauzeren bij oplopende temperatuur 🔒
- [ ] Analyses parallelliseren en resultaten hergebruiken 🔒
- [ ] Geheugengedrag bij lange tijdlijnen 🔒
- [ ] Foutmeldingen en herstel na een mislukte export 🔒

---

## Doorlopend

- [x] Unittests op de deterministische kern ✅
- [x] CI: `./gradlew build` op main en op elke pull request ✅
- [x] CI levert bij elke build een installeerbare APK op als artefact ✅
- [x] Android Lint als build-stap; `warningsAsErrors` op `:core-render` ✅
- [ ] Lint op Kotlin-stijl: ktlint of detekt met een gedeelde configuratie
- [ ] `CLAUDE.md` met de conventies van deze repo (microseconden, genormaliseerde eenheden, geen Media3 in het model)
- [ ] Instrumented tests zodra er een Android-module is 🔒
- [ ] Crash-rapportage of op zijn minst een logbestand op het toestel 🔒

---

## Spec voor `:core-render`

Deze module wordt bewust zo dun mogelijk. Alle beslissingen — welke clips, welke
trims, welke effectvolgorde, hoe tijden door in-points en snelheid gemapt worden —
staan al in het `RenderPlan` dat `:core-model` produceert en dat volledig op de JVM
getest is. `:core-render` vertaalt dat één op één naar Media3 en beslist zelf niets.

Zo hoort de vertaling eruit te zien:

| `RenderPlan` | Media3 |
|---|---|
| `RenderPlan` | `Composition` met `EditedMediaItemSequence` per `RenderSequence` |
| `RenderPlan.width/height` | `Presentation` op de `Composition` — `Transformer` heeft geen setter voor resolutie |
| `RenderPlan.frameRate` | `EditedMediaItem.setFrameRate()`, als **boven**grens: Media3 kan de framerate verlagen maar niet verhogen |
| `RenderItem.Gap` | `EditedMediaItemSequence.Builder.addGap(durationUs)` |
| `RenderItem.Source` | `EditedMediaItem` van een `MediaItem` |
| `clipStartUs` / `clipEndUs` | `MediaItem.ClippingConfiguration` |
| `speed` | `EditedMediaItem.setSpeed(SpeedProvider)` — `SpeedChangeEffect` is in 1.10.1 afgeschaft |
| `RenderEffect.ColorAdjust` | `RgbAdjustment` / `Contrast` |
| `RenderEffect.CropPath` | crop-/`Presentation`-effect, waarde per frame uit `interpolateAt()` |
| `RenderEffect.CaptionOverlays` | `OverlayEffect` met een `BitmapOverlay` per cue |
| `RenderEffect.MaskedBlur` | `MaskedBlurShaderProgram` |

Twee dingen die de vertaling **niet** zelf mag uitrekenen, omdat ze al in het plan
staan en daar getest zijn:

- **`RenderEffect.MaskedBlur.sourceOffsetUs`** — de shader trekt dit van de
  frame-PTS af om de maskdecoder aan te sturen. Zelf iets afleiden uit de
  clipping-configuratie is precies de fout die op een getrimde clip misgaat.
- **`RenderEffect.CaptionOverlays.cues`** — die staan al in clip-lokale tijd, met
  trim en snelheid verrekend. Niet nog een keer omrekenen.

Voor de volledige regels van de mapping en waarom ze zo zijn: `RenderPlan.kt` en
`RenderPlanTest.kt` in `:core-model`.

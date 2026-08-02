# Werklijst

Afvinkbare uitwerking van de roadmap in **[BOUWPLAN.md](BOUWPLAN.md)**. Dat document
legt uit *waarom*; dit document is de lijst van *wat er nog moet gebeuren*.

Legenda: ✅ af · 🔨 in aanbouw · ⬜ nog niet begonnen · 🔒 geblokkeerd

---

## Blokkades

Onderstaande blokkade bepaalt de volgorde van al het overige werk. Zonder Android SDK
en toestel kan een groot deel van dit project niet gebouwd én niet geverifieerd worden.

| Vereist | Waarvoor |
|---|---|
| **Android SDK + Android Studio** | `:app`, `:core-render`, alle Media3-code |
| **Samsung S24 Ultra aangesloten** | Fase 0-poort, hardware-codecs, QNN/NPU |
| **Android NDK** | whisper.cpp JNI (fase 3), EdgeTAM-runtime (fase 6) |
| **ffmpeg op de werkplek** | Genereren van de maskvideo-testfixture voor fase 0 |

Alles wat hieronder 🔒 heeft, wacht hierop. Alles zonder 🔒 kan op een gewone JVM
gebouwd en getest worden — daarom is die kolom bewust zo ver mogelijk gevuld.

---

## Fase 0 — Spike (de go/no-go-poort)

Niet doorgaan naar fase 1 zonder dat deze twee bewijzen er liggen. Zie
[BOUWPLAN.md §Verificatie](BOUWPLAN.md).

- [x] Gradle-opzet met pure-JVM modules ✅
- [x] `RenderPlan` — het exacte, testbare invoerformaat voor de renderer ✅
- [ ] Android SDK configureren, `:core-render` en `:app` aan `settings.gradle.kts` 🔒
- [ ] Media3 1.10.1 als afhankelijkheid pinnen (`libs.versions.toml`) 🔒
- [ ] `RenderPlan.toComposition()` — domme vertaling naar Media3 (spec onderaan) 🔒
- [ ] Bronvideo + grayscale maskvideo genereren met ffmpeg (bewegende witte cirkel, halve resolutie) 🔒
- [ ] `MaskedBlurShaderProgram`: tweede `MediaCodec` → `SurfaceTexture` → OES-texture 🔒
- [ ] Pull-based `advanceMaskTo(targetUs)`, monotoon vooruit, geen seeks 🔒
- [ ] `updateTexImage()` op Transformer's GL-thread met de juiste EGL-context 🔒
- [ ] **Bewijs 1 — pariteit:** 10 s exporteren én afspelen in `CompositionPlayer`, frames vergelijken met SSIM op ~10 vaste timestamps 🔒
- [ ] **Bewijs 2 — masked blur:** cirkel scherp, rest geblurd, synchroon over de volle duur — in preview, in export, én op een getrimde clip (in-point ≠ 0) 🔒
- [ ] Go/no-go vastleggen. Faalt bewijs 2, dan terug naar de tekentafel voor maskopslag (RLE per frame of vormgebaseerd) 🔒

---

## Fase 1 — Skelet

- [x] Projectmodel: `Project`/`Sequence`/`TimelineItem`/`EffectSpec` ✅
- [x] Bewerkingen: split, ripple delete, lift, insert, move, overwrite, trim, normalize ✅
- [x] Validatie van modelinvarianten ✅
- [x] JSON-persistentie via kotlinx-serialization ✅
- [x] Undo/redo — snapshotstack ✅
- [x] `Project.toRenderPlan()` incl. cue-mapping en in-point-offsets ✅
- [ ] Compose-timeline: scrub 🔒
- [ ] Compose-timeline: trim aan beide randen 🔒
- [ ] Compose-timeline: split op de playhead 🔒
- [ ] Compose-timeline: verplaatsen (drag-and-drop) en gaten tonen 🔒
- [ ] Preview via `CompositionPlayer`, gekoppeld aan de playhead 🔒
- [ ] Export via `Transformer`, met voortgang en annuleren 🔒
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
- [x] CI: `./gradlew test` op push en PR ✅
- [ ] Lint: ktlint of detekt met een gedeelde configuratie
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
| `RenderPlan.width/height/frameRate` | encoderinstellingen op `Transformer` |
| `RenderItem.Gap` | `EditedMediaItemSequence.Builder.addGap(durationUs)` |
| `RenderItem.Source` | `EditedMediaItem` van een `MediaItem` |
| `clipStartUs` / `clipEndUs` | `MediaItem.ClippingConfiguration` |
| `speed` | `SpeedChangeEffect` |
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

# AI-video-editor op Android — uitvoerbaar bouwplan

## Context

De repo `robotninja100/video-editor` is leeg. Het bijgevoegde document beschrijft een AI-video-editor voor eigen gebruik op Android met vijf features: auto-blur met object tracking, auto-ondertiteling, auto-knippen op stiltes, auto-reframe 9:16 en auto-edit.

Doel na overleg aangescherpt:

- **Toestel:** Samsung S24 Ultra (Snapdragon 8 Gen 3 for Galaxy).
- **Ambitie:** een echte editor op expert-niveau — knippen, verplaatsen, effecten — plus auto tracking. Niet alleen een "lange video in → Shorts uit"-pipeline.
- **Auto-blur:** volledige segmentatie met mask-video, zoals in §4.4 van het document. Bewust gekozen na afweging tegen goedkopere vormgebaseerde alternatieven.
- **Externe AI mag.** Het zware modelwerk hoeft niet on-device. Zie *Analyse lokaal of in de cloud*; dat schrapt whisper.cpp-JNI en de QNN-integratie volledig.

Het brondocument is technisch correct geverifieerd (Media3 1.10.1 stable, `CompositionPlayer` experimenteel, EdgeTAM-modelgroottes). Dit plan neemt de architectuur uit §2 en §3 over en corrigeert drie dingen: de fasevolgorde rond het grootste risico, het timeline-model voor "verplaatsen", en de aanname dat preview/export-pariteit gratis is.

---

## Beslissend onderzoeksresultaat

Uitgezocht voordat dit plan geschreven werd, omdat het de architectuur bepaalt:

| Vraag | Antwoord | Gevolg |
|---|---|---|
| Kan een tweede videostream via de Composition in een shader? | `VideoCompositor` is publiek (`@UnstableApi`) en timestamp-gesynchroniseerd, **maar** `MultipleInputVideoGraph` is `final` en hardcodeert `DefaultVideoCompositor`. Geen injectiepunt. | De masktrack moet **zelf** worden gedecodeerd binnen een custom `GlShaderProgram`. |
| Hoe synchroniseer je dat dan? | `GlShaderProgram.queueInputFrame()` levert `presentationTimeUs` mee. | **Pull-based** mask-decoder: trek de maskdecoder vooruit tot zijn PTS ≥ target. Werkt in beide timingregimes (realtime preview én zo-snel-mogelijk export) omdat het aan de timestamp hangt, niet aan de wandklok. |
| Ondersteunt Media3 gaten in een sequence? | Ja, `EditedMediaItemSequence.Builder.addGap()` (sinds 1.8.0). | De beperking "sequentieel, geen gaten" uit §3 van het document kan eruit — nodig voor "verplaatsen". |

Dit maakt de gekozen mask-video-aanpak **haalbaar**, maar bevestigt ook dat het geen uurtje werk is: de gesynchroniseerde maskdecoder is het zwaarste onderdeel van het project.

---

## Correcties op het brondocument

1. **Het architectuurrisico wordt verplaatst naar fase 0.** Het document bouwt tracking als laatste (fase 6, week 12+). Maar de *architectuur* van de masktrack is de grootste onbekende; als de pull-decoder niet werkt, verandert alles. Splits daarom:
   - **Architectuurrisico → fase 0:** handgemaakte maskvideo, hardgecodeerde blur, géén EdgeTAM. Bewijst het mechanisme in dagen.
   - **ML-werk → fase 6:** EdgeTAM, tap-to-select, maskgeneratie. Zoals gepland als laatste, want de shader is dan al af.

2. **Preview/export-pariteit is niet gratis.** §1 claimt het, §7 noemt precies dit als zwak punt. Ze delen de effectketen maar niet de encoder, kleurruimte of framerate. Fase 0 moet het *meten*, niet aannemen.

3. **Blur-radius in genormaliseerde eenheden.** Preview draait vaak op lagere resolutie dan export. Een blur van "13 pixels" is dan zichtbaar anders in preview dan in de export. Radius uitdrukken als fractie van framebreedte. Concrete pariteitsval.

4. **Audio ontbreekt.** Loudness-normalisatie (EBU R128) via `AudioProcessor` is een dag werk en hoort bij fase 2, waar de RMS-analyse toch al gebouwd wordt.

5. **Auto-edit met indices, niet timestamps.** Nummer het transcript en laat de LLM `[12, 13, 27]` teruggeven in plaats van tijden. Dan kan hij per constructie geen tijden verzinnen en vervalt de validatiestap uit §4.5.

6. **Media3-versie: pin op 1.10.1.** 1.11.0-rc01 (22 juli 2026) brengt `setFrameRate()` en pitch-behoud bij speed changes. Nuttig, maar een upgrade midden in het project met een experimentele API is precies wat je niet wilt. Pin 1.10.1, herbeoordeel na fase 4.

---

## Architectuur

Ongewijzigd overgenomen uit §2 van het document — dit is de juiste beslissing:

```
IMPORT → ANALYSE (WorkManager, offline) → SIDECAR-METADATA
                                               ↓
                                     TIMELINE-MODEL (eigen EDL)
                                               ↓
                            Project.toComposition()  ← enige koppelvlak
                                     ↙                    ↘
                          CompositionPlayer            Transformer
                             (preview)                   (export)
```

Geen modelinferentie tijdens playback, ooit. Sidecars per bronclip (niet per tijdlijnpositie), zodat ze hergebruikt worden als een clip meerdere keren op de tijdlijn staat.

### Moduleopzet

```
:app            Compose-UI, timeline, navigatie
:core-model     Project/Sequence/Clip/EffectSpec + kotlinx-serialization  (pure JVM, unittestbaar)
:core-render    toComposition(), custom GlShaderProgram's, mask-decoder
:core-analysis  stilte-DSP, scenedetectie, sidecar-IO                     (pure JVM waar mogelijk)
:core-remote    transcriptie (Groq), segmentatie (SAM 3), auto-edit (Claude)
:ml-ondevice    optioneel later: lokale fallback voor transcriptie
```

`:core-model` en `:core-analysis` als pure JVM-modules is de belangrijkste keuze hier — die zijn dan zonder emulator te testen.

### Timeline-model

Uitbreiding op §3 van het document, nodig voor "verplaatsen" en meerdere tracks:

```kotlin
data class Project(
    val id: String,
    val sequences: List<Sequence>,   // 0 = hoofdtrack, rest = overlays
    val outputSpec: OutputSpec,
)

data class Sequence(
    val id: String,
    val items: List<TimelineItem>,   // mag gaten bevatten
)

sealed interface TimelineItem {
    val durationUs: Long
    data class Gap(override val durationUs: Long) : TimelineItem
    data class Clip(
        val sourceUri: Uri,
        val inPointUs: Long,
        val outPointUs: Long,
        val speed: Float = 1f,
        val effects: List<EffectSpec> = emptyList(),
    ) : TimelineItem
}

sealed interface EffectSpec {                       // serialiseerbaar, GEEN Media3-objecten
    data class MaskedBlur(val maskUri: Uri, val radiusFrac: Float) : EffectSpec
    data class Crop(val path: List<Keyframe<RectF>>) : EffectSpec
    data class Captions(val style: CaptionStyle, val cues: List<Cue>) : EffectSpec
    data class ColorAdjust(val exposure: Float, val contrast: Float) : EffectSpec
}
```

**Media3-beperkingen om in het model af te dwingen** (anders lopen ze pas bij export tegen je aan):

- Items binnen één sequence overlappen nooit. Overlappen = tweede sequence nodig.
- Crossfades tussen twee clips vereisen dus twee sequences met alpha via `VideoCompositorSettings` — niet één sequence. Belangrijk als je transitions wilt.
- Werk overal in microseconden, nooit in framenummers (VFR-opnames).

Persistentie: JSON via kotlinx-serialization. Undo/redo: snapshots van `Project` in een deque — immutable data classes geven structural sharing gratis.

---

## De masked-blur pipeline (het kernstuk)

Custom `GlShaderProgram` die zelf een tweede `MediaCodec` aanstuurt:

```kotlin
class MaskedBlurShaderProgram(...) : BaseGlShaderProgram(false, 1) {

    // MediaCodec → SurfaceTexture → OES-texture, monotone forward-pull.
    // Geen seeks: mask en bron delen de timebase en framecount.
    private fun advanceMaskTo(targetUs: Long) {
        while (maskPtsUs < targetUs && !maskEos) {
            releaseOutputBuffer(render = true)
            awaitFrameAvailable()
            surfaceTexture.updateTexImage()   // op de GL-thread van Transformer
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        advanceMaskTo(presentationTimeUs - clipInPointUs)  // timeline-PTS → bron-PTS
        // bind inputTexId als uSource, maskTexId als uMask, teken
    }
}
```

```glsl
vec4 sharp = texture(uSource, vTex);
vec4 soft  = blur(uSource, vTex, uRadiusFrac);   // fractie van framebreedte, niet pixels
float m    = texture(uMask, vTex).r;             // luma; chroma is vlak grijs
fragColor  = mix(sharp, soft, smoothstep(0.35, 0.65, m));
```

Aandachtspunten die fase 0 moet uitwijzen:

- `updateTexImage()` moet op de GL-thread met de juiste EGL-context draaien — Transformer's shader-thread is de juiste plek.
- Grayscale-mask wordt als gewone YUV geëncodeerd; na de OES-sampler lees je `.r`.
- Trimming: de effect-PTS moet door dezelfde in-point gemapt worden als de clip. Dit exact vastpinnen in fase 0, niet later.
- Halve-resolutie mask + lineaire filtering + `smoothstep` geeft gratis feathering.

---

## Analyse lokaal of in de cloud

Het zware rekenwerk mag naar externe AI-diensten. Dat is precies de winst van de
architectuur hierboven: de renderpipeline leest alleen sidecar-bestanden en heeft
geen idee of die door de NPU of door een server zijn gemaakt. De keuze is dus per
analysesoort te maken, en later te herzien, zonder de editor aan te raken.

| Analyse | Waar | Waarom |
|---|---|---|
| Stiltes | **lokaal** | Pure DSP, milliseconden. Cloud zou alleen latency toevoegen. |
| Scenedetectie | **lokaal** | Histogramverschil, triviaal. |
| Reframe-detectie | **lokaal** | ML Kit is gratis en snel; de S24 Ultra doet dit moeiteloos. |
| Transcriptie | **cloud** | `whisper-large-v3-turbo` via Groq: ~$0,0006/min. Fors nauwkeuriger dan `small` q5 lokaal, en het bespaart de hele JNI-bridge. |
| Segmentatie / tracking | **cloud** | SAM 3 via fal.ai: $0,005 per 16 frames (~$0,31 per 1000 frames). Betere kwaliteit dan EdgeTAM en geen QNN-integratie nodig. |
| Auto-edit | **cloud** | Was al de Claude API. |

Implementatie: één interface per analysesoort in een aparte module `:core-remote`,
met een lokale en een cloud-implementatie. De sidecar-formaten blijven identiek,
zodat wisselen van backend geen enkel effect heeft op de rest.

### Wat dit concreet oplevert

- **whisper.cpp JNI vervalt.** Dat was twee weken; het wordt een HTTP-call. Het
  echte werk in fase 3 was toch al de overlay-rendering, niet de transcriptie.
- **EdgeTAM en de QNN-delegate vervallen.** Dat was het grootste risico in fase 6.
- **Geen thermal throttling meer** bij lange clips — de reden waarom on-device
  tracking van tien minuten materiaal pijnlijk werd.

### Wat je ervoor terugkrijgt aan problemen

- **Upload is de bottleneck, niet inferentie.** Stuur nooit het 4K-origineel.
  Analyseer op een **proxy**: 540p voor segmentatie, en voor transcriptie alleen
  een 16 kHz mono audiotrack. De masks komen terug op proxy-resolutie, wat prima
  is — ze werden toch al op halve resolutie opgeslagen.
- **Kosten schalen met framerate.** ~$0,56 per minuut op 30 fps is reëel. Track op
  10 fps en interpoleer de masks ertussen: blur-randen zijn toch zacht, dus dat is
  visueel gratis en scheelt een factor 3. Track bovendien alleen het fragment dat
  geblurd moet worden, niet de hele video.
- **Vraag de RLE-variant aan.** `sam-3/video-rle` geeft RLE-masks terug; die
  encodeer je lokaal naar de grayscale masktrack. Scheelt bandbreedte en je houdt
  de maskvideo in eigen hand.
- **Privacy.** Auto-blur wordt vaak juist *voor* privacy gebruikt. Bedenk bewust
  dat het ongeblurde materiaal dan bij een derde partij langskomt. Voor eigen
  gebruik waarschijnlijk prima, maar het is een keuze, geen detail.
- **Offline werkt niet meer** voor captions en tracking. Stiltes en reframe blijven
  lokaal, dus de basis-editing blijft zonder netwerk bruikbaar.

### Wat hier níet door verandert

Fase 0 blijft ongewijzigd. Of de masks nu van EdgeTAM of van SAM 3 komen, de
gesynchroniseerde mask-decoder in de shader is exact hetzelfde probleem. Die poort
blijft dus staan.

---

## Roadmap

| Fase | Duur | Resultaat |
|---|---|---|
| **0. Spike** | 1 week | Gradle-opzet + **twee** bewijzen: (a) pariteitsmeting export vs. preview via frame-hashes, (b) masked blur met handgemaakte maskvideo. **Go/no-go — niet verder zonder.** |
| **1. Skelet** | 2–3 weken | Projectmodel, `toComposition()`, Compose-timeline met scrub/trim/split/**verplaatsen**/gaten, export. Unittests op `:core-model`. |
| **2. Stiltes + audio** | 4 dagen | RMS-DSP, hysterese, WorkManager-analysepipeline, sidecar-IO, EBU R128-loudness. |
| **3. Captions** | 1 week | Groq-transcriptie via `:core-remote`, `OverlayEffect` met gecachete `BitmapOverlay` per cue, VAD-correctie tegen de stiltes uit fase 2. |
| **4. Auto-edit** | 1 week | Claude API, genummerd transcript in / **indices** uit, mapping naar clips. |
| **5. Reframe** | 2 weken | ML Kit face+pose op 1 frame/200 ms, scenedetectie, Kalman/spring-smoothing, deadzone, crop-keyframes. |
| **6. Tracking** | 1,5–2 weken | SAM 3 via fal.ai, proxy-upload, RLE → maskvideo, tap-to-select. Shader is al klaar uit fase 0. |
| **7. Afwerking** | doorlopend | Presets, thermal-chunking, snelheid. |

**Totaal ≈ 10–12 weken part-time**, tegen 14–17 met alles on-device. De winst zit
volledig in fase 3 en 6: de twee fases waar het modelwerk zat.

### Waarom fase 5 vóór fase 6

Reframe deelt zijn detectie-infrastructuur (ML Kit, per-frame sampling, smoothing, keyframe-paden) met tracking. Reframe eerst bouwen levert een werkende feature op *en* de helft van de infrastructuur voor tracking.

---

## Testen

Het brondocument noemt testen niet. De deterministische kern is triviaal te testen op de JVM en is precies waar stille regressies binnensluipen:

- `:core-model` — `toComposition()` mapping, serialisatie-roundtrip, split/move/ripple-operaties, gap-invarianten.
- `:core-analysis` — stiltedetectie op een golden WAV met bekende intervallen; Kalman-smoothing op een synthetisch pad.
- Auto-edit — indexvalidatie: gooi indices buiten bereik weg, test met opzettelijk kapotte LLM-output.

---

## Verificatie

**Fase 0 is de enige echte poort.** Twee concrete criteria:

1. **Pariteit.** Exporteer een composition van 10 s met de blur-shader. Speel dezelfde composition af in `CompositionPlayer` en leg frames vast. Vergelijk perceptueel (SSIM) op ~10 vaste timestamps. Systematisch verschil in kleur of scherpte = een probleem dat je nú oplost, niet in week 12.
2. **Masked blur.** Genereer met ffmpeg een grayscale maskvideo (bewegende witte cirkel op zwart) op halve resolutie, en een bronvideo. Verwacht resultaat: de cirkel is scherp, de rest geblurd, en de mask blijft synchroon over de volle duur — zowel in preview als in export, en óók op een getrimde clip (in-point ≠ 0).

Falen op punt 2 betekent terug naar de tekentafel voor de maskopslag (RLE per frame, of vormgebaseerd). Daarom staat het in week 1 en niet in week 12.

**Per fase daarna:** elke fase eindigt in iets dat op de S24 Ultra draait en zichtbaar werkt. Fase 2–5 leveren elk een sidecar die je met een debug-scherm kunt inspecteren voordat de UI eraan hangt.

**Draaien:** `./gradlew :app:installDebug` op het aangesloten toestel; `./gradlew test` voor de JVM-modules.

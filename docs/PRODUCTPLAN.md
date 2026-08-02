# Van hier naar een app die af is

Dit document beschrijft **wat er nog moet gebeuren en in welke volgorde**, tot een
versie die je aan iemand anders kunt geven. Het *waarom* achter de architectuur —
de afwegingen, de risico's, de Media3-beperkingen — staat in
**[BOUWPLAN.md](BOUWPLAN.md)**. Dat document blijft leidend voor beslissingen;
dit document is de weg ernaartoe.

Legenda: ✅ af · 🔨 in aanbouw · ⬜ nog niet begonnen · 🔒 vereist een toestel

---

## 1. Waar we nu staan

**Ongeveer een kwart van de weg**, en dat kwart is de makkelijk toetsbare helft.

| | |
|---|---|
| Modules | 12 |
| JVM-tests | 804, groen |
| Statische analyse | detekt + ktlint schoon, alle basislijnen leeg |
| Regeldekking | ondergrens 90%, bewaakt in CI |
| Android-tests | Robolectric op `:core-render` en `:app` |
| APK | bouwt in CI, te downloaden als artefact |
| **Op een toestel gedraaid** | **nooit — geen enkel frame gerenderd** |

Die laatste regel is de belangrijkste van het document. Alles hierboven zegt dat
de code doet wat de code zegt te doen. Niets zegt dat het beeld klopt.

### Wat er echt af is

- **Het projectmodel.** Timeline met gaten, split/trim/move/ripple/lift/overwrite,
  validatie van de invarianten, JSON-persistentie, undo/redo, tijdlijn-geometrie
  (hit-test, snap, zoom-om-anker, tick-ladder).
- **Het rendercontract.** `RenderPlan` doet al het rekenwerk dat foutgevoelig is:
  cues en crop-paden naar cliptijd, trim en snelheid verrekend, nul-effecten
  weggefilterd, en de omrekening naar de maskvideo (`sourcePtsFor`) met het
  in-punt én de snelheid erin.
- **De renderketen.** Alle vier de effecten vertalen naar Media3: masked blur,
  crop-pad, ondertitels, kleurcorrectie. Plus export via `Transformer`.
- **De analyses die lokaal blijven.** Stiltedetectie met hysterese, scenedetectie,
  reframe-smoothing met deadzone, EBU R128-loudness, cue-layout, cut-list.
- **De infrastructuur.** Taakwachtrij met backoff, thermisch beleid, foutentaxonomie
  met Nederlandse gebruikersteksten, projectopslag met schemamigratie en
  crashherstel, mediacatalogus met sidecar-verouderingslogica.
- **Het designsysteem.** Glaslagen, palet, schalen — met echte validatie:
  WCAG-contrast van elke tekstkleur op elke glaslaag boven zwarte, witte en
  middengrijze videoframes, en ΔE tussen de trackkleuren.

### Wat er niet is

- **De fase 0-poort is niet gehaald.** Het meetscherm bestaat nu wel, maar is nooit
  gedraaid. Zolang dat zo is, staat de hele roadmap op een aanname.
- **Acht van de twaalf modules zitten niet in de app.** `:core-project`,
  `:core-jobs`, `:core-pipeline`, `:core-library`, `:core-thermal` en
  `:core-errors` staan niet in `app/build.gradle.kts`; `:core-analysis` en
  `:core-remote` staan er wel in maar worden nergens geïmporteerd. De 804 tests
  dekken code die de app grotendeels niet uitvoert.
- **Geen persistentie.** Alleen `InMemoryProjectStore`. Alles weg bij afsluiten.
- **Geen netwerk.** Ktor is gedeclareerd, er is geen client. Geen sleutelbeheer.
- **Geen on-device ML.** Geen whisper, geen EdgeTAM, geen ML Kit-aanroep.
- **Speler en tijdlijn zijn ontkoppeld.** Geen play/pause, scrubben seekt niet.
- **Geen launcher-icoon, geen window insets, geen animaties.**

---

## 2. Wat "af" betekent

De lat, één zin: **een APK die je aan iemand geeft, die hij installeert, en waar
hij zonder uitleg een video mee kan monteren en exporteren.**

Uitgeschreven:

1. Materiaal importeren en het project **overleeft het afsluiten van de app**.
2. Monteren met de hand: knippen, trimmen, verplaatsen, ongedaan maken.
3. De vijf features doen wat ze beloven, elk met een voorstel dat je kunt
   afwijzen vóór het toegepast wordt.
4. Exporteren naar een bestand dat in de galerij verschijnt en te delen is.
5. Fouten worden getoond in gewone taal, met een weg terug.
6. Het werkt op meer dan één toestel en overleeft draaien, backgrounden en een
   telefoon die warm wordt.
7. Te installeren via een release op GitHub, niet via een CI-artefact.

**Buiten scope** — zie §7.

---

## 3. De stappen

Elke fase eindigt in iets dat op het toestel te zien is. Geen fase begint voordat
de vorige dat gehaald heeft.

### Fase 0 — De poort · 1 week 🔒

De enige echte go/no-go. De code ligt er; het gaat om de **meting**.

- [x] `SpikeActivity` met eigen ingang in de launcher
- [x] Bron- en maskvideo genereren op het toestel (`Canvas` + `MediaCodec`, geen ffmpeg)
- [x] SSIM en scherptemeting, met eigen tests op de meetlat
- [x] Drempels expliciet: gemiddelde SSIM ≥ 0,90, minimum ≥ 0,85, een ontbrekend
      frame telt als zakken
- [ ] **Bewijs 1 — pariteit.** 10 s exporteren én afspelen in `CompositionPlayer`,
      frames vergelijken op ~10 vaste tijdstippen 🔒
- [ ] **Bewijs 2 — masked blur.** Cirkel scherp, rest geblurd, synchroon over de
      volle duur — in preview, in export, én op een getrimde clip 🔒
- [ ] **Bewijs 3 — de snelheid.** Hetzelfde op een clip van 2×. Dit staat er los
      bij omdat de spike het nu niet meet en de fout stil is 🔒
- [ ] Drie aannames vastpinnen die alleen hier blijken: het teken van het in-punt,
      de richting van de snelheidsomrekening in `sourcePtsFor`, en of Media3 de
      effect-PTS vóór of ná de snelheidsaanpassing levert 🔒
- [ ] Go/no-go vastleggen. Zakt bewijs 2, dan terug naar de tekentafel voor de
      maskopslag: RLE per frame, of vormgebaseerd 🔒

**Acceptatie:** een screenshot van het spike-scherm met drie groene vinkjes.

### Fase 1 — Het fundament · 2 weken

De laag die alles blokkeert. Hier worden de acht losse modules aangesloten.

- [ ] **Dependency injection** (Hilt). Zonder dit is er geen weg om een store,
      HTTP-client of taakrunner in een `ViewModel` te krijgen — dit blokkeert
      letterlijk alle punten hieronder
- [ ] `FileProjectStore : ProjectStore` op schijf, plus autosave en crashherstel
      aansluiten (`:core-project` is compleet en getest, hij hangt nergens aan)
- [ ] **Duurzame media-URI's.** De photo picker geeft vluchtige rechten. Kiezen:
      SAF met `takePersistableUriPermission`, of een proxy-kopie in app-opslag.
      Deze keuze moet vóór de opslag gemaakt worden, anders is een opgeslagen
      project morgen niet meer te openen
- [ ] **Export vanuit de editor**: voortgang, annuleren, `MediaStore`-insert,
      deel-intent, `FileProvider`
- [ ] **Speler ↔ tijdlijn koppelen**: play/pause, seek bij scrubben,
      `Player.Listener` die de afspeelkop laat meelopen, `onPause`/`onResume`
- [ ] De compositie niet meer bij elke projectwijziging herbouwen — nu doet
      `LaunchedEffect(project)` dat wel, en dat herstart de decoders per sleepactie
- [ ] Taakrunner die `JobQueue` leegwerkt, met `:core-pipeline` als thermische rem
      en `AnalysisService` als drager
- [ ] `POST_NOTIFICATIONS` runtime aanvragen — nu start de service maar wordt de
      melding stil onderdrukt, en dat is precies de melding die hem legitimeert
- [ ] Veilige sleutelopslag voor Groq en Anthropic, plus een instellingenscherm

**Acceptatie:** een video importeren, knippen, afsluiten, heropenen, exporteren,
en het bestand terugvinden in de galerij.

### Fase 2 — De editor af · 2 weken

- [ ] Trim-handvatten aan beide randen van een clip
- [ ] Splitsen op de afspeelkop
- [ ] Ripple delete en lift
- [ ] Snappen op clipgrenzen — `TimelineGeometry.edgesOf()` bestaat en getest,
      hij wordt alleen niet aangeroepen
- [ ] Horizontaal pannen, en de weergave die de afspeelkop volgt
- [ ] Het gebarenconflict oplossen: pinch, drag en tap vechten nu om dezelfde
      pointers op één `Canvas`
- [ ] Liniaal-labels (nu staan er alleen streepjes)
- [ ] Filmstrip-thumbnails en een golfvorm
- [ ] Gaten zichtbaar maken — nu is een gat niet van "einde van de track" te
      onderscheiden
- [ ] Slepen tussen tracks (`onMove` geeft nu alleen de bron-sequence door)
- [ ] Haptiek bij snappen

**Acceptatie:** een montage van tien knippen maken zonder de tijdlijn te verlaten.

### Fase 3 — Stiltes en audio · 1 week

De DSP is af en getest; wat ontbreekt is de invoer en de uitvoer.

- [x] RMS met hysterese, minimale stiltelengte, padding, `keepIntervals()`
- [x] EBU R128 / ITU-R BS.1770-4 loudness met K-weighting en gating
- [ ] PCM-extractie uit de bron (`MediaExtractor` + `MediaCodec`)
- [ ] Sidecar schrijven en lezen per bronclip, met versienummer
- [ ] "Knip stiltes weg": voorstel tonen, per stilte accepteren of afwijzen
- [ ] Loudness toepassen als `AudioProcessor` in de renderketen
- [ ] True peak met oversampling — nu wordt alleen de samplepiek gemeten

**Acceptatie:** een opname met pauzes erin gaat er korter en gelijkmatiger uit.

### Fase 4 — Ondertiteling · 1,5 week

Cloud eerst. Lokaal komt later achter dezelfde interface — zie §6.

- [x] `Cue`-model met woordgrenzen, `CaptionStyle` genormaliseerd
- [x] Cue-mapping door trim en snelheid heen, in `RenderPlan`
- [x] `CaptionsOverlay` met woordwrap en contour, maten als fractie van de framehoogte
- [ ] Audio-extractie naar 16 kHz mono
- [ ] Groq-client (`whisper-large-v3-turbo`), met de bestaande parser uit `:core-remote`
- [ ] VAD-correctie: cue-grenzen bijstellen op de stiltes uit fase 3
- [ ] Bitmap-cache per cue, met invalidatie bij stijlwijziging
- [ ] Ondertitel-editor: tekst corrigeren, timing bijstellen, stijl kiezen

**Acceptatie:** een video van vijf minuten krijgt ondertitels die op het woord
synchroon lopen, ook na een knip.

### Fase 5 — Auto-edit · 0,5 week

- [x] Genummerd transcript, index-parsing tolerant voor codefences en proza
- [x] Validatie: buiten bereik en herhalingen apart gerapporteerd
- [x] Indices → intervallen, aangrenzende samengevoegd
- [ ] Anthropic-client
- [ ] Beoordelingsscherm: het voorstel zien vóór het toegepast wordt
- [ ] Foutafhandeling: geen netwerk, rate limit, lege respons

**Acceptatie:** een interview van twintig minuten levert een voorstel van twee
minuten dat je regel voor regel kunt afwijzen.

### Fase 6 — Reframe naar 9:16 · 2 weken

- [x] Scenedetectie, kritisch gedempte veer, deadzone, clampen op de doel-aspect
- [x] `CropPathEffect` die per frame interpoleert
- [ ] Frame-sampling: 1 frame per 200 ms uit de bron
- [ ] ML Kit face **en pose** — alleen face-detection is nu gedeclareerd
- [ ] Onderwerpkeuze bij meerdere gezichten
- [ ] Crop-pad in de sidecar en als `EffectSpec.Crop` op de clip
- [ ] Aspect-presets in de UI, en het pad handmatig kunnen bijstellen

**Acceptatie:** een liggende opname van een pratend persoon wordt staand, zonder
dat het beeld zichtbaar zwabbert bij een scenewissel.

### Fase 7 — Tracking en auto-blur · 3–4 weken

De zwaarste fase, en bewust on-device. Het bouwplan noemt SAM 3 in de cloud als
goedkoper alternatief; die weg is hier niet gekozen omdat auto-blur een
privacyfunctie is, en het ongeblurde origineel naar een derde partij sturen daar
tegenin gaat. Zie §6 als je die afweging wilt herzien.

- [ ] EdgeTAM converteren en kwantiseren voor QNN
- [ ] QNN-delegate integreren — nadrukkelijk niet de GPU-delegate
- [ ] Tap-to-select: aanwijzen wat er gevolgd wordt
- [ ] **Maskvideo-encoder**: masks naar een grayscale mp4 op halve resolutie.
      Deze bestaat nergens en is een aparte brok werk
- [ ] Mask als sidecar aan de bronclip koppelen
- [ ] Hertracken na een trim of split
- [ ] Voortgang tonen, resultaat inspecteren, mask bijwerken

**Acceptatie:** een gezicht in beeld blijft geblurd terwijl het beweegt, ook na
het knippen van de clip, en ook op 2×.

### Fase 8 — Afwerking en uitgeven · 1,5 week

- [ ] Release-buildtype met R8, plus regels voor Media3, ML Kit, Ktor en
      kotlinx-serialization — alle vier hebben ze die nodig
- [ ] Signing-config en een versiestrategie (nu staat `versionCode = 1` vast)
- [ ] Release via GitHub Releases in plaats van een CI-artefact
- [ ] Exportpresets: resolutie, bitrate, framerate
- [ ] Thermal-chunking: exporteren in stukken, pauzeren bij oplopende temperatuur
- [ ] Android lint in de build en in CI
- [ ] Gedrag controleren op een tweede toestel en een tweede Android-versie
- [ ] Geheugengedrag bij lange tijdlijnen

**Acceptatie:** iemand anders installeert de APK van de releasepagina en komt er
zonder jou uit.

**Totaal ≈ 14–16 weken part-time.**

---

## 4. De app moet er ook uitzien

Dit hoofdstuk staat apart omdat het anders altijd het eerst sneuvelt. Het is ook
het goedkoopste hoofdstuk van het document: het designsysteem is al gebouwd en
gevalideerd, het komt alleen niet aan.

**Wat er al ligt.** `:core-design` heeft een donker palet, drie glaslagen met
monotone blur/scrim/rand, een 4-punts ruimteschaal, radii, een typeschaal met
lettergrootte, regelhoogte en gewicht, bewegingsduren met easing, en een
minimale aanraakmaat. Plus twee soorten validatie die je zelden ziet: WCAG-contrast
van elke tekstkleur op elke glaslaag boven willekeurige videoframes, en ΔE tussen
de trackkleuren zodat ze onderling onderscheidbaar blijven.

**Wat er moet gebeuren, op volgorde van zichtbaar effect:**

1. **Window insets.** `enableEdgeToEdge()` wordt aangeroepen maar nergens wordt
   een inset-padding toegepast. De werkbalk loopt onder de statusbalk door en de
   tijdlijn onder de gebarenbalk. Dit is het eerste wat opvalt bij openen, en het
   is een middag werk.
2. **Een launcher-icoon.** Er is er geen — de app krijgt nu het standaard
   Android-icoon. Adaptive icon, plus een splash screen via `core-splashscreen`.
3. **Afspeelbediening.** Play/pause, tijdweergave, een afspeelkop die met het
   beeld meeloopt. Zonder dit voelt het niet als een speler maar als een plaatje.
4. **Een `MaterialTheme`-wrapper** met een kleur- en typografieschema uit `Tokens`.
   Nu worden alleen `sizeSp` gelezen en wordt `FontWeight` met de hand gezet,
   terwijl `lineHeightSp` en `weight` klaarstaan. En omdat er geen thema omheen
   staat, vallen Material-defaults terug op het **lichte** schema — ripples en
   randen die niet bij de rest passen.
5. **Iconen.** De werkbalk gebruikt nu Unicode-tekens als tekst: `↶ ↷ ◎ T ⌗ ◐`.
   Die renderen per toestel anders en zijn niet te stylen.
6. **Beweging.** `Tokens.Motion` staat klaar en wordt nergens gebruikt. Er is geen
   enkele animatie in de app. Sheet-transities, selectiefeedback, zoomdemping,
   een flits bij ongedaan maken.
7. **Foutmeldingen aansluiten.** `:core-errors` heeft de teksten al geschreven en
   getest — "Het exporteren van je video is gestopt. Je toestel is te warm
   geworden…" — er is alleen geen scherm dat ze toont. Laaghangend fruit met veel
   effect.
8. **Lege staten en een weg terug.** Annuleert iemand de mediakiezer, dan blijft
   er nu een leeg zwart scherm over zonder knop. Er is geen lege staat voor "nog
   geen project" en geen bevestiging bij iets onomkeerbaars.
9. **Toegankelijkheid.** Geen enkele `contentDescription`. TalkBack leest nu "↶"
   voor. Plus focusvolgorde en een minimale raakmaat op de clips in de tijdlijn.
10. **Meerdere schermen** met navigatie: projectoverzicht, mediabibliotheek,
    export, instellingen. `:core-library` en `:core-project` staan er klaar voor.
11. **Teksten naar `strings.xml`.** Alle UI-copy staat nu hardgecodeerd in Kotlin.
12. **Screenshot-tests** (Roborazzi of Paparazzi). De logische aanvulling op
    `TokensTest`: die bewaakt of de kleuren kloppen, screenshot-tests bewaken of
    het scherm klopt.

---

## 5. Spec voor `:core-render`

Deze module blijft zo dun mogelijk. Alle beslissingen — welke clips, welke trims,
welke effectvolgorde, hoe tijden door in-punten en snelheid gemapt worden — staan
al in het `RenderPlan` dat `:core-model` produceert en dat volledig op de JVM
getest is. `:core-render` vertaalt dat één op één en beslist zelf niets.

| `RenderPlan` | Media3 |
|---|---|
| `RenderPlan` | `Composition` met een `EditedMediaItemSequence` per `RenderSequence` |
| `RenderPlan.width/height` | `Presentation` op de compositie |
| `RenderPlan.frameRate` | `EditedMediaItem.setFrameRate()` als bovengrens |
| `RenderItem.Gap` | `EditedMediaItemSequence.Builder.addGap()` |
| `RenderItem.Source` | `EditedMediaItem` van een `MediaItem` |
| `clipStartUs` / `clipEndUs` | `MediaItem.ClippingConfiguration` in **microseconden** |
| `speed` | `setSpeed(ConstantSpeedProvider)` — niet `SpeedChangeEffect`, die is afgeschaft |
| `RenderEffect.ColorAdjust` | `RgbAdjustment` en/of `Contrast` |
| `RenderEffect.CropPath` | `CropPathEffect`, waarde per frame uit `interpolateAt()` |
| `RenderEffect.CaptionOverlays` | `OverlayEffect` met één `CaptionsOverlay` |
| `RenderEffect.MaskedBlur` | `MaskedBlurEffect` → `MaskedBlurShaderProgram` |

Drie dingen die de vertaling **niet** zelf mag uitrekenen:

- **De maskpositie.** `RenderEffect.MaskedBlur.sourcePtsFor()` doet dat, met het
  in-punt én de snelheid erin. Het hele effect gaat daarom naar de shader, niet
  drie losse velden — velden uitpakken en in de shader opnieuw combineren is
  precies hoe de snelheidsfactor eerder verdween.
- **De cue-tijden.** Die staan al in cliptijd, met trim en snelheid verrekend.
- **De NDC-omrekening.** `NormRect.ndcCenter()` staat in `:core-model` met vier
  tests; in deze module zou hij een emulator kosten.

**Maskconventie: wit is scherp, zwart is geblurd.** Eén keer vastgelegd, omdat de
twee eerdere implementaties het tegengesteld deden.

---

## 6. Beslissingen die je later kunt herzien

Twee keuzes in dit plan zijn omkeerbaar gemaakt, en het is de moeite waard om te
weten waar de knop zit.

**Transcriptie: cloud nu, lokaal later.** `:core-remote` bevat alleen contracten
en parsers, geen HTTP. Een lokale whisper.cpp-implementatie komt achter dezelfde
interface en levert dezelfde sidecar op — de renderpipeline merkt het verschil
niet. Kosten van de cloudweg: ongeveer $0,0006 per minuut audio. Wat je ervoor
inlevert is offline werken.

**Tracking: on-device.** Het bouwplan rekent voor dat SAM 3 via fal.ai goedkoper
en sneller te bouwen is (~$0,31 per 1000 frames, en de hele QNN-integratie
vervalt). De keuze hier is bewust anders, omdat auto-blur meestal juist voor
privacy gebruikt wordt. Wil je toch de cloudweg: de shader, de maskvideo en het
sidecar-formaat blijven identiek — alleen de bron van de masks verandert, en dan
zakt fase 7 van 3–4 weken naar ongeveer 2.

---

## 7. Wat we bewust niet doen

- **Play Store.** Geen store-listing, screenshots, privacybeleid of
  reviewproces. Distributie loopt via GitHub Releases.
- **Tablets en opvouwbare schermen.** Eén layout, telefoon, staand.
- **Samenwerking en cloudopslag van projecten.** Projecten blijven op het toestel.
- **Ondersteuning voor oudere Android-versies.** minSdk 31 blijft staan.
- **Een plugin-architectuur voor effecten.** Vier effecten is genoeg; een vijfde
  is een dag werk in de bestaande structuur.

---

## 8. Doorlopend

- [x] Unittests op de deterministische kern (804)
- [x] CI: statische analyse, tests met dekkingspoort, APK-build
- [x] Detekt en ktlint met lege basislijnen per module
- [x] Robolectric-tests op `:core-render` en `:app`
- [ ] `CLAUDE.md` met de conventies van deze repo: microseconden, genormaliseerde
      eenheden, geen Media3 in het model, wit-is-scherp
- [ ] Android lint in de build en in CI
- [ ] Instrumented tests op een echt toestel
- [ ] Crashrapportage, of op zijn minst een logbestand op het toestel

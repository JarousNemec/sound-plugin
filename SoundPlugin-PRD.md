# PRD — SoundPlugin

> Product Requirements Document pro vývoj pluginu pomocí Claude Code.
> Verze dokumentu: 1.0 · Jazyk projektu: **Kotlin** · Cílová platforma: **IntelliJ Platform 2023.1+**

---

## 1. Přehled

**SoundPlugin** je plugin pro JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, CLion a další postavené na IntelliJ Platform), který přehrává zvukové efekty jako reakci na události v IDE. Cílem je zpříjemnit a "ozvučit" práci v editoru — od kliknutí na akci přes dokončení buildu až po selhání běžícího procesu.

Plugin se dodává s přibalenou sadou zvuků ze hry **Stronghold 1** (autor udělil svolení k použití). Uživatelé si mohou vytvářet vlastní **soundpacky** a sdílet je mezi sebou i mezi různými instancemi IDE.

### 1.1 Hodnota pro uživatele

- Okamžitá zvuková zpětná vazba na události, které jinak vyžadují vizuální kontrolu (build hotový, testy prošly, proces spadl).
- Personalizace pracovního prostředí.
- Snadné sdílení vlastních zvukových sad v komunitě.

### 1.2 Mimo rozsah (Non-goals)

- Generování nebo editace zvuku uvnitř pluginu.
- Hudba na pozadí / streamování.
- Podpora IDE mimo IntelliJ Platform (VS Code apod.).
- Mobilní nebo webové verze.

---

## 2. Cíle a metriky úspěchu

| Cíl | Metrika |
|---|---|
| Stabilita | Nulový pád IDE nebo jeho procesu způsobený pluginem. Žádná výjimka z pluginu nesmí probublat do EDT nebo shodit background proces. |
| Výkon | Přehrání zvuku nesmí blokovat EDT; latence od události po zvuk < 50 ms. |
| Pokrytí akcí | Plugin zachytí všechny registrované `AnAction` akce + kurátorovaný seznam životních událostí (viz §5). |
| Použitelnost | Uživatel namapuje zvuk na akci do 3 kliknutí v Settings. |
| Přenositelnost | Export/import soundpacku funguje mezi libovolnými dvěma instancemi IDE 2023.1+. |

---

## 3. Klíčová technická rozhodnutí

Tato rozhodnutí byla potvrzena se zadavatelem. Jsou-li označena *(předpoklad)*, lze je revidovat.

| Oblast | Rozhodnutí | Zdůvodnění |
|---|---|---|
| Jazyk | **Kotlin** | Doporučeno JetBrains pro nové pluginy; coroutines pro async přehrávání. |
| Min. platforma | **2023.1** (`sinceBuild = 231`) | ~2 roky zpětné kompatibility, dostupnost moderního API. |
| Build systém | **Gradle + IntelliJ Platform Gradle Plugin 2.x** | Standard pro vývoj pluginů. |
| Audio backend | **Java Sound API** (`javax.sound.sampled`) | Součást JDK, žádné externí závislosti, podpora WAV/AU/AIFF. |
| Souběh zvuků | **Poslední přebíjí (single active voice)** | Při novém zvuku se právě hrající zastaví. Volitelná krátká fronta max 1 čekající zvuk. |
| Konfigurace | Zvuk + per-akce hlasitost + globální hlasitost s mute | Potvrzeno zadavatelem. |
| Persistence | `PersistentStateComponent` (lokální) + `soundpack.json` (sdílitelný) | Oddělení osobního nastavení od sdíleného obsahu. |

---

## 4. Architektura

### 4.1 Vrstvy

```
┌─────────────────────────────────────────────┐
│ Event Sources                               │
│  · AnActionListener (všechny akce)           │
│  · Lifecycle listenery (build, run, VCS…)    │
└───────────────────┬─────────────────────────┘
                    │ EventId
                    ▼
┌─────────────────────────────────────────────┐
│ SoundEventRouter                             │
│  · mapuje EventId → SoundMapping             │
│  · aplikuje override z nastavení             │
└───────────────────┬─────────────────────────┘
                    │ SoundRequest(file, volume)
                    ▼
┌─────────────────────────────────────────────┐
│ SoundPlayer  (mimo EDT, vlastní vlákno)      │
│  · poslední přebíjí, hlasitost, mute         │
│  · veškeré výjimky odchyceny a zalogovány    │
└─────────────────────────────────────────────┘
```

### 4.2 Hlavní komponenty

- **`SoundPlayerService`** — application-level service (`@Service`). Vlastní jedno přehrávací vlákno (single-thread executor). Drží referenci na právě hrající `Clip`; při novém požadavku ji `stop()`-ne a zavře. Veškerá práce s `javax.sound` je obalená v `try/catch`.
- **`SoundEventRouter`** — application-level service. Překládá `EventId` na konkrétní zvukový soubor podle aktivního soundpacku a uživatelských override.
- **`SoundSettingsState`** — `PersistentStateComponent`, lokální nastavení IDE (viz §6).
- **`SoundpackManager`** — načítání, validace, import/export soundpacků.
- **Listenery** — viz §5.

### 4.3 Vlákna a EDT

- Listenery běží na EDT — musí být **bleskové**: pouze vytvoří `EventId` a předají ho routeru. Žádné I/O, žádné dekódování zvuku na EDT.
- Načítání a přehrávání zvuku probíhá výhradně na přehrávacím vlákně `SoundPlayerService`.
- Zvukové soubory se po prvním načtení **cachují v paměti** (dekódovaný `byte[]` / pre-loaded `Clip` factory), aby se neopakovalo čtení z disku.

---

## 5. Zachytávání akcí

Plugin pracuje **dvouvrstvě**.

### 5.1 Vrstva A — generické akce (`AnActionListener`)

Registrace `AnActionListener` přes message bus zachytí **každou** akci IDE před a po provedení (`beforeActionPerformed` / `afterActionPerformed`). Každá akce má stabilní ID (`ActionManager.getId(action)`).

- Plugin nabídne v nastavení **prohledávatelný seznam všech registrovaných akcí** (čteno z `ActionManager`), na které lze zvuk namapovat.
- Výchozí stav: namapovaná je jen malá kurátorovaná podmnožina (viz §5.3), zbytek je vypnutý — jinak by IDE neustále "cinkalo".
- Příklady akcí: `$Copy`, `$Paste`, `EditorDeleteLine`, `Build`, `Run`, `Stop`, `CommentByLineComment`, `FindInPath`, `RenameElement`, `SaveAll`, `ReformatCode`.

### 5.2 Vrstva B — životní události (kurátorovaný seznam)

Události, které **nejsou** klasické `AnAction`. Každá se napojí na příslušný platform listener / topic. Pokud konkrétní API není v 2023.1 dostupné, položka se degraduje gracefully (viz §8).

| EventId | Zdroj / API | Poznámka |
|---|---|---|
| `build.finished.success` | `BuildProgressListener` / `CompilationStatusListener` | Build bez chyb. |
| `build.finished.failed` | `CompilationStatusListener` (errors > 0) | Build s chybami. |
| `run.process.started` | `ExecutionListener#processStarted` | Spuštění run/debug konfigurace. |
| `run.process.terminated` | `ExecutionListener#processTerminated` | Ukončení procesu (exit code != 0 → varianta `…failed`). |
| `run.process.terminated.success` | `ExecutionListener#processTerminated` (exit 0) | Úspěšné dokončení. |
| `debug.breakpoint.hit` | `XDebuggerManagerListener` / breakpoint listener | Zastavení na breakpointu. |
| `test.finished.passed` | `SMTRunnerEventsListener#onTestingFinished` | Všechny testy prošly. |
| `test.finished.failed` | `SMTRunnerEventsListener` (failures > 0) | Aspoň jeden test selhal. |
| `vcs.commit.completed` | `CheckinHandler` / commit listener | Dokončení commitu. |
| `vcs.update.completed` | `VcsRepositoryMappingListener` / update listener | Pull/update hotový. |
| `indexing.finished` | `DumbService` (`smartModeStarted`) | IDE dokončilo indexaci. |
| `notification.shown` | `Notifications.Bus` topic | Obecná notifikace; lze rozlišit `NotificationType` (INFO/WARNING/ERROR). |
| `file.saved` | `FileDocumentManagerListener#beforeAllDocumentsSaving` | Uložení souboru/souborů. |
| `project.opened` / `project.closed` | `ProjectManagerListener` | Otevření/zavření projektu. |
| `app.frame.activated` | `ApplicationActivationListener` | Návrat fokusu do IDE. |

> **Pravidlo rozsahu:** Vrstva B je záměrně kurátorovaná a konečná. Necílíme na "úplně všechno" — cílíme na události, které lze **pohodlně a stabilně** zachytit veřejným API. Nové položky se přidávají vědomě, ne paušálně.

### 5.3 Výchozí mapování (přibalený Stronghold pack)

Po instalaci je aktivní jen decentní podmnožina, aby IDE nebylo hlučné:

- `build.finished.success`, `build.finished.failed`
- `run.process.terminated.success`, `run.process.terminated` (failed varianta)
- `test.finished.passed`, `test.finished.failed`
- `vcs.commit.completed`
- `notification.shown` (jen `ERROR` typ)

Vše ostatní je dostupné, ale ve výchozím stavu ztlumené.

---

## 6. Nastavení (Settings UI)

Plugin přidá vlastní stránku do **Settings/Preferences → Tools → SoundPlugin** (`Configurable` / `SearchableConfigurable`).

### 6.1 Sekce nastavení

1. **Globální**
   - Posuvník *General volume* 0–100 %. Hodnota 0 = **Mute** (nehraje nic).
   - Zaškrtávátko *Enable SoundPlugin* (rychlý master vypínač).
   - Výběr aktivního soundpacku (dropdown).
   - Tlačítka *Import soundpack…* a *Export current soundpack…*.

2. **Mapování akcí** (tabulka)
   - Sloupce: **Událost/Akce** · **Zvuk** (dropdown ze souborů v packu, + „— žádný —") · **Hlasitost** (posuvník 0–100 %, relativní k globální) · **Zapnuto** (checkbox) · **▶ Test**.
   - Záložky / filtr: *Životní události* (vrstva B) a *IDE akce* (vrstva A) odděleně.
   - Vyhledávací pole pro filtrování dlouhého seznamu IDE akcí.

3. **Pokročilé**
   - *Reset to soundpack defaults* — zahodí uživatelské override.
   - *Open soundpacks folder* — otevře složku s nainstalovanými packy.

### 6.2 Persistence (lokální stav IDE)

`SoundSettingsState : PersistentStateComponent<…>` ukládá:

```
enabled: Boolean
globalVolume: Int            // 0–100, 0 = mute
activeSoundpackId: String
overrides: Map<EventId, MappingOverride>
   MappingOverride { soundFile: String?, volume: Int?, enabled: Boolean }
```

Tento stav je **lokální pro dané IDE** a podléhá Settings Sync. Override mají přednost před výchozím mapováním ze soundpacku.

---

## 7. Soundpacky — formát a sdílení

Soundpack je **přenositelná jednotka**: složka (nebo `.zip`) obsahující zvukové soubory + manifest. Manifest je **sdílitelný**, nedrží osobní nastavení IDE.

### 7.1 Struktura

```
my-soundpack/
├── soundpack.json
└── sounds/
    ├── build_ok.wav
    ├── build_fail.wav
    ├── test_pass.wav
    └── …
```

### 7.2 `soundpack.json`

```json
{
  "schemaVersion": 1,
  "id": "stronghold-classic",
  "name": "Stronghold 1 Classic",
  "author": "SoundPlugin",
  "description": "Zvuky ze hry Stronghold 1 (použito se svolením autora).",
  "version": "1.0.0",
  "defaultMappings": [
    { "eventId": "build.finished.success", "soundFile": "sounds/build_ok.wav",   "volume": 80 },
    { "eventId": "build.finished.failed",  "soundFile": "sounds/build_fail.wav", "volume": 90 },
    { "eventId": "test.finished.passed",   "soundFile": "sounds/test_pass.wav",  "volume": 75 }
  ]
}
```

- `schemaVersion` — pro budoucí migrace formátu.
- `defaultMappings` — výchozí mapování, které pack nabízí. Uživatel je může v IDE přepsat (override v §6.2), ale samotný pack tím zůstane nedotčený.
- `eventId` neznámé danému IDE / verzi se **ignoruje** (warning do logu), neshodí import.

### 7.3 Import / Export

- **Export** — vezme aktivní soundpack a aktuální (override-nuté) mapování, vygeneruje `soundpack.json` a zabalí spolu se zvukovými soubory do `<name>.soundpack.zip`.
- **Import** — uživatel vybere `.zip` nebo složku; `SoundpackManager` ověří manifest (schéma, existence souborů, formát zvuku), rozbalí do `…/config/SoundPlugin/soundpacks/<id>/` a zaregistruje.
- Tím lze pack i s osobním laděním přenést do jiného IDE.

### 7.4 Přibalený obsah

Stronghold 1 pack je součástí distribuce pluginu (v `resources/soundpacks/stronghold-classic/`). Při prvním spuštění se rozbalí do uživatelské config složky, aby byl editovatelný a sloužil jako vzor.

---

## 8. Stabilita a bezpečnost (KRITICKÉ)

> Plugin **nesmí za žádných okolností** shodit IDE ani jeho proces. Toto je tvrdý požadavek.

### 8.1 Izolace chyb

- Každý listener callback je obalen `try/catch (Throwable)`. Chyba se zaloguje přes `Logger` a **nikdy** se nepropaguje dál.
- Veškerá práce s `javax.sound` (otevření linky, dekódování, přehrání) je v `try/catch`. Chybějící zvukové zařízení, nepodporovaný formát nebo zamčená audio linka → tichý fallback (nic se nepřehraje), warning do logu.
- Při I/O chybě (chybějící soubor, poškozený WAV) se mapování pro danou událost dočasně deaktivuje, aby nespamovalo log.

### 8.2 Žádné blokování EDT

- Listenery na EDT smí pouze sestavit `EventId` a předat ho — žádné I/O, žádné `Clip` operace.
- Přehrávání běží na dedikovaném vlákně. Pokud je vlákno zaneprázdněné, uplatní se pravidlo „poslední přebíjí".

### 8.3 Ochrana proti zahlcení

- **Debounce / rate limit:** stejná `EventId` se nepřehraje častěji než např. 1× za 120 ms (konfigurovatelná konstanta) — chrání před sériemi akcí (držené `Ctrl+Z`, hromadné notifikace).
- Single active voice + max 1 čekající zvuk: nemůže vzniknout neomezeně rostoucí fronta.

### 8.4 Zdroje a životní cyklus

- `Clip` se po dohrání (`LineEvent.Type.STOP`) vždy uzavře (`close()`), aby neunikaly audio linky.
- `SoundPlayerService` implementuje `Disposable` — při zavření aplikace zastaví přehrávání, uzavře linky a shutdownne executor.
- Paměťová cache zvuků má strop velikosti (např. 32 MB); při překročení se nejstarší položky uvolní (LRU).

### 8.5 Graceful degradation

- Pokud platform API pro nějakou událost (vrstva B) v dané verzi/IDE neexistuje, položka se prostě neregistruje a v UI se buď skryje, nebo zobrazí jako nedostupná. Plugin běží dál.

---

## 9. Edge cases

| Situace | Chování |
|---|---|
| Stroj bez zvukové karty / headless | Tichý fallback, jednorázový warning do logu. |
| Audio linka obsazená jinou aplikací | Pokus o přehrání selže tiše, retry při dalším eventu. |
| Soundpack odkazuje na chybějící soubor | Mapování deaktivováno, warning; zbytek packu funguje. |
| Nepodporovaný formát zvuku | Soubor přeskočen při validaci importu; uživatel upozorněn. |
| Velmi dlouhý zvuk + rychlá akce | Předchozí zvuk se zastaví (poslední přebíjí). |
| Mute (globalVolume = 0) | Listenery běží, ale `SoundPlayer` nic nepřehraje (žádná zbytečná práce s linkou). |
| Import packu se stejným `id` | Uživatel dostane volbu přepsat / přejmenovat. |
| `eventId` v manifestu neznámé | Ignorováno s warningem, import pokračuje. |
| Více oken/projektů | Akce se zachytí jednou na úrovni aplikace; zvuk se nepřehraje vícekrát. |

---

## 10. Podporované formáty zvuku

- **Primárně WAV** (`javax.sound.sampled` ho podporuje nativně, bez závislostí). Doporučený formát pro přibalený Stronghold pack.
- AU, AIFF — podporováno Java Sound API.
- *(Předpoklad)* MP3/OGG **mimo MVP** — vyžadovaly by externí dekodér (např. `mp3spi`/`vorbisspi`), což zvětší plugin a přidá rizika. Lze přidat v pozdější fázi, pokud bude poptávka.
- Délka zvuku není omezena (potvrzeno zadavatelem); dlouhé zvuky řeší pravidlo „poslední přebíjí".

---

## 11. Struktura projektu

```
soundplugin/
├── build.gradle.kts
├── gradle.properties
├── src/main/
│   ├── kotlin/com/example/soundplugin/
│   │   ├── player/        SoundPlayerService, SoundCache
│   │   ├── events/        SoundEventRouter, EventId
│   │   ├── listeners/     ActionSoundListener, BuildListener,
│   │   │                  ExecutionListener, TestListener, …
│   │   ├── soundpack/     SoundpackManager, SoundpackManifest,
│   │   │                  ImportExportService
│   │   ├── settings/      SoundSettingsState, SoundConfigurable,
│   │   │                  SoundSettingsPanel (UI)
│   │   └── util/          SafeRunner (try/catch wrapper), RateLimiter
│   └── resources/
│       ├── META-INF/plugin.xml
│       └── soundpacks/stronghold-classic/
└── src/test/kotlin/…
```

### 11.1 `plugin.xml` — klíčové registrace

- `<applicationListeners>` pro `AnActionListener`, `ExecutionListener`, `ProjectManagerListener` apod.
- `<applicationService>` pro `SoundPlayerService`, `SoundEventRouter`, `SoundpackManager`.
- `<applicationConfigurable>` pro stránku nastavení (parentId `tools`).
- `<idea-version since-build="231"/>`.

---

## 12. Fáze implementace

### Fáze 1 — Jádro (MVP)
- Skeleton projektu, Gradle, `plugin.xml`.
- `SoundPlayerService` s přehráváním WAV mimo EDT, globální hlasitost + mute.
- `AnActionListener` (vrstva A) + `SoundEventRouter`.
- Přibalený Stronghold pack se rozbalí při startu.
- Základní Settings stránka: globální hlasitost, master vypínač, tabulka životních událostí.
- Kompletní izolace chyb (§8) — od začátku, ne dodatečně.

### Fáze 2 — Životní události a UX
- Vrstva B: build, run/debug, testy, VCS, indexace, notifikace.
- Per-akce hlasitost, tlačítko Test, vyhledávání v seznamu IDE akcí.
- Rate limiting a single-voice ladění.

### Fáze 3 — Soundpacky a sdílení
- `soundpack.json` parser + validace.
- Import/export `.soundpack.zip`.
- Správa více packů, přepínání.

### Fáze 4 — Doladění (volitelné / budoucí)
- Profily / témata (rychlé přepínání sad).
- Případná podpora MP3/OGG.
- Publikace na JetBrains Marketplace.

---

## 13. Akceptační kritéria

- [ ] Plugin se nainstaluje na čisté IDE 2023.1 i na aktuální verzi bez chyb.
- [ ] Build úspěšný/neúspěšný, dokončení/selhání procesu a test pass/fail spolehlivě přehrají zvuk.
- [ ] Posuvník globální hlasitosti funguje; hodnota 0 = naprosté ticho.
- [ ] Libovolnou IDE akci lze v nastavení namapovat na zvuk do 3 kliknutí.
- [ ] Export vytvoří `.soundpack.zip`, který jde naimportovat do druhého IDE i se zvuky a mapováním.
- [ ] Vyvolání chyby (smazaný zvukový soubor, odpojené audio) **neshodí** IDE ani build proces — ověřeno testem.
- [ ] Série rychlých akcí nezpůsobí kakofonii ani růst fronty (rate limit + single voice).
- [ ] Žádná operace pluginu neblokuje EDT (ověřeno v profileru / `ThreadingAssertions`).

---

## 14. Otevřené otázky

1. Konkrétní mapování jednotlivých Stronghold zvuků na události — doladit po dodání zvukových souborů.
2. Má `notification.shown` rozlišovat zvuk podle `NotificationType` (INFO/WARNING/ERROR), nebo stačí jeden zvuk? *(Návrh: rozlišovat — tři samostatné EventId.)*
3. Cílí se na publikaci na JetBrains Marketplace, nebo zůstane plugin interní/komunitní? Ovlivní to požadavky na ikonu, popis a kompatibilitní matici.

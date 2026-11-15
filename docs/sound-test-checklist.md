# SoundPlugin – testovací checklist (demo pack „Demo (MIDI)“)

Checklist všech událostí, které plugin detekuje: co se má ozvat, jak událost vyvolat a co ověřit.
Projdi ho v sandbox IDE (`./gradlew runIde`) s aktivním packem **Demo (MIDI)**.

Zkratky platí pro výchozí Windows keymapu IntelliJ IDEA 2023.1.

---

## 0. Příprava

- [ ] **Spustit sandbox**: `.\gradlew.bat runIde` (nebo run konfigurace *Run IDE with Plugin*).
- [ ] **Aktivní pack**: *Settings → Tools → SoundPlugin → Soundpack* = **Demo (MIDI)**.
  - Na čisté instalaci je výchozí. Pokud sandbox pamatuje `stronghold-classic`, přepni ho ručně.
- [ ] **Hlasitost**: zapnutý master switch a globální hlasitost (výchozí 70 %).
- [ ] **Testovací projekt**: *New Project → Java*, build system **IntelliJ**, ne Gradle ani Maven.
  - `build.finished.*` poslouchá `CompilationStatusListener` a ten se při buildu delegovaném na Gradle nespustí.
- [ ] **Kód v projektu**: třída `Main` s `main()` a jeden JUnit test.
  - JUnit přidáš přes Alt+Enter na `@Test` → *Add 'JUnit5' to classpath*.
- [ ] **Git**: *VCS → Enable Version Control Integration → Git*.
- [ ] **Lokální remote** (kvůli Push a Update), v terminálu projektu:
  ```
  git init --bare ..\demo-remote.git
  git remote add origin ..\demo-remote.git
  ```
  Pak první commit a `git push -u origin master` (nebo `main`).

**Jak funguje přehrávání (ať víš, co čekat):**
- **Last wins**: hraje vždy jen jeden zvuk. Nový zvuk okamžitě utne předchozí.
- **Debounce 120 ms**: stejná událost se během 120 ms přehraje jen jednou. Rychlé Ctrl+C Ctrl+C tedy cvakne jednou.
- **Tlačítko Test**: sloupec **▶ Test** v tabulce nastavení přehraje zvuk bez vyvolání události. Hodí se na rychlé poslechnutí celého packu. Obchází master switch, globální hlasitost i debounce.

**Ladění, když něco nehraje:**
1. V *Tools → SoundPlugin* zapni **Verbose logging**, dej *Apply* a vyvolej událost.
2. Tlačítkem **Show idea.log** otevřeš složku s logem. Hledej řádky začínající `[SoundPlugin]`.
3. Co v logu najdeš:
   - Každá událost a akce má řádek s výsledkem, buď `-> soubor.wav (…, gain −X dB)`, nebo `silent: <důvod>`. Důvodem je třeba `no sound mapped`, `debounced` nebo `disabled by user override`.
   - Když zvuk přebije jiný, uvidíš `cut after N ms by '…'` nebo `superseded`.
   - U každého přehrání je čas od události po start zvuku a jak dlouho trvalo čtení souboru, `getClip` a `open`.
   - Když pro událost **žádný řádek není**, IDE ji pluginu vůbec neposlalo, takže chyba je v listeneru nebo v API.
4. Pozor, když verbose zapneš, plugin zaloguje i každou nenamapovanou akci (šipky, Enter…), aby šlo zjistit id akce, kterou chceš ozvučit. Po ladění ho zase vypni.
5. Bez verbose plugin loguje jen:
   - souhrn při startu (packy, aktivní pack, hlasitost, audio zařízení),
   - varování, když zpracování události trvá na EDT déle než 15 ms (`handling … took`),
   - varování, když zvuk začne hrát déle než 150 ms po události (`started … ms after the event`).

**Styl packu:** všechno je v D dur a hrají jen drnkací a úderové nástroje s přirozeným doznívání. Každá skupina událostí má vlastní barvu zvuku, ať se dají poznat i tiché jednonotové zvuky:

| Nástroj | Skupina |
|---|---|
| celesta | úspěch (build, testy, projekt) |
| marimba | chyby a destruktivní editace (cut, delete line) |
| vibrafon | run, hledání |
| kalimba | VCS |
| pizzicato smyčce | schránka (copy, paste, duplicate) |
| harfa | historie (undo/redo), indexace |
| elektrické piano | navigace (goto) |
| nylonová kytara | úpravy kódu a refaktoring |
| steel drum | debugger |
| hrací skříňka | notifikace |
| woodblock | uložení souboru |
| flétna | focus okna IDE |
| zvonkohra | nápověda (Alt+Enter) |

Úspěchy stoupají, chyby klesají. Velké milníky kombinují víc nástrojů.

U každé položky odškrtni:
- **Z**: zazní,
- **H**: hlasitost je OK,
- **N**: neotravuje.

Do *Pozn.* si piš dojmy.

---

## 1. Lifecycle události (vrstva B)

Najdeš je v nastavení v tabulce lifecycle událostí.

### Build
- [ ] **`build.finished.success`**: build projektu bez chyb
  - Zvuk: celesta D5-F#5-A5-D6, stoupající arpeggio nad pizzicato basem D3 (vol 70)
  - Trigger: *Build → Build Project* (**Ctrl+F9**) v projektu bez chyb. Pokud je vše up-to-date, použij *Build → Rebuild Project*.
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`build.finished.failed`**: build s chybami
  - Zvuk: marimba A4-F4-D4, sestupně, na konci pizzicato D3 (vol 75)
  - Trigger: vlož do kódu syntaktickou chybu (např. smaž `;`) a dej **Ctrl+F9**.
  - Pozor: přerušený (aborted) build nehraje nic. To je záměr.
  - Z ☐ H ☐ N ☐ Pozn.:

### Run
- [ ] **`run.process.started`**: spuštění procesu
  - Zvuk: vibrafon D5→A5 (vol 45)
  - Trigger: **Shift+F10** (Run) nad `Main`. Hraje i při Debug a při spuštění testů.
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`run.process.terminated.success`**: proces skončil s exit code 0
  - Zvuk: vibrafon, dyáda A5+D6 (vol 55)
  - Trigger: `main()`, který normálně doběhne (např. jen `println`).
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`run.process.terminated`**: proces skončil s nenulovým exit code
  - Zvuk: marimba F4→C#4 (vol 65)
  - Trigger: `System.exit(1)` nebo nezachycená výjimka v `main()`. Případně spusť nekonečnou smyčku a zastav ji přes **Ctrl+F2** (Stop).
  - Z ☐ H ☐ N ☐ Pozn.:

### Debug
- [ ] **`debug.breakpoint.hit`**: debugger se zastavil
  - Zvuk: steel drum F#5→A5 (vol 50)
  - Trigger: breakpoint v `main()` (**Ctrl+F8**), pak **Shift+F9** (Debug).
  - Hraje i při každém kroku (**F8** Step Over, **F7** Step Into), protože platforma nerozlišuje breakpoint a krok.
  - Z ☐ H ☐ N ☐ Pozn.:

### Testy
- [ ] **`test.finished.passed`**: běh testů prošel
  - Zvuk: celesta D5-F#5-A5, pak brnknutí harfy D5-F#5-A5-D6 (vol 70)
  - Trigger: zelený JUnit test → **Ctrl+Shift+F10** nebo zelená šipka v gutteru.
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`test.finished.failed`**: běh testů selhal
  - Zvuk: marimba D4-C#4-A3, sestupně (vol 75)
  - Trigger: test s `assertEquals(1, 2)` → spustit.
  - Z ☐ H ☐ N ☐ Pozn.:
  - **Pozor, překryv:** viz sekce 3, bod 1.

### VCS
- [ ] **`vcs.commit.completed`**: commit proběhl
  - Zvuk: kalimba A4→D5, „razítko“ (vol 60)
  - Trigger: změň soubor → **Ctrl+K** (Commit) → zpráva → *Commit*.
  - Z ☐ H ☐ N ☐ Pozn.:
  - **Pozor, překryv:** viz sekce 3, bod 2.
- [ ] **`vcs.update.completed`**: update projektu doběhl
  - Zvuk: kalimba D5-B4-A4, sestupně (vol 50)
  - Trigger: *Git → Update Project* (**Ctrl+T**). Vyžaduje remote z přípravy.
  - Z ☐ H ☐ N ☐ Pozn.:

### Indexing
- [ ] **`indexing.finished`**: IDE dokončilo indexaci (konec dumb mode)
  - Zvuk: harfa A4-D5-E5-A5, rychlé glissando nahoru (vol 40)
  - Trigger: otevření projektu. Případně *File → Project Structure* → přidat nebo změnit SDK či knihovnu, nebo *File → Invalidate Caches…* a restart.
  - Z ☐ H ☐ N ☐ Pozn.:

### Notifikace
Spolehlivý ruční trigger pro každý typ neznám jistě. Postupy níže jsou označené ⚠ (neověřeno). Napiš si, co fungovalo.

- [ ] **`notification.shown.info`**: info notifikace (balónek)
  - Zvuk: hrací skříňka A5→D6 (vol 30)
  - Trigger: úspěšný commit ukazuje info balónek „N files committed“. ⚠ Případně v sandboxu (`idea.is.internal=true`) *Tools → Internal Actions → UI* → akce pro testovací notifikaci, pokud v 2023.1 existuje.
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`notification.shown.warning`**: warning notifikace
  - Zvuk: hrací skříňka F5 F5, opakovaná nota (vol 50)
  - Trigger: ⚠ testovací notifikace z Internal Actions (viz výše). Jinak cokoli, co ukáže žlutý balónek.
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`notification.shown.error`**: error notifikace
  - Zvuk: marimba D5→G#4, tritón (vol 65)
  - Trigger: ⚠ push na neexistující remote. Udělej `git remote set-url origin ..\neexistuje.git`, pak **Ctrl+Shift+K** → *Push*, mělo by přijít „Push failed“. Potom remote vrať.
  - Z ☐ H ☐ N ☐ Pozn.:

### Soubory, projekt, okno
- [ ] **`file.saved`**: ukládání dokumentů
  - Zvuk: woodblock, dřevěné „ťok“ (vol 30)
  - Trigger: změň soubor → **Ctrl+S**. Hraje i při **autosave**, např. když přepneš z IDE do jiného okna.
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`project.opened`**: otevření projektu
  - Zvuk: harfa D4-A4, pak celesta D5-F#5-A5, stoupající „roll“ (vol 55)
  - Trigger: *File → Open…* jiného projektu nebo *File → Recent Projects*.
  - Z ☐ H ☐ N ☐ Pozn.:
  - **Ověřit:** zazní i u **prvního** projektu po startu IDE? Viz sekce 3, bod 5.
- [ ] **`project.closed`**: zavření projektu
  - Zvuk: celesta A5-F#5-D5, pak harfa A4-D4, klesající „roll“ (vol 50)
  - Trigger: *File → Close Project*.
  - Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`app.frame.activated`**: okno IDE získalo focus
  - Zvuk: flétna A5, krátký „nádech“ (vol 20)
  - Trigger: **Alt+Tab** jinam a zpět do IDE.
  - Z ☐ H ☐ N ☐ Pozn.:

---

## 2. Akce IDE (vrstva A)

Najdeš je v nastavení na záložce akcí, kde se dá hledat podle id. Plugin umí ozvučit **libovolnou** akci. Demo pack mapuje těchto 20.

Akce se ozvou jen při spuštění přes ActionManager (zkratka, menu, kontextové menu). Myší gesta s vlastní obsluhou nemusí hrát; to platí třeba pro klik do gutteru na breakpoint nebo Ctrl+klik na symbol.

### Schránka a řádky (pizzicato smyčce, destruktivní = marimba)
- [ ] **`$Copy`**: **Ctrl+C**. Pizzicato A5, jedno brnknutí (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`$Paste`**: **Ctrl+V**. Pizzicato A5→D5, „položit“ (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`EditorDuplicate`**: **Ctrl+D**. Pizzicato D5 D5, dvojité brnknutí (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`$Cut`**: **Ctrl+X**. Marimba B5 ťuk (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`EditorDeleteLine`**: **Ctrl+Y**. Marimba D4, nízký ťuk (vol 30). Z ☐ H ☐ N ☐ Pozn.:

### Historie (harfa)
- [ ] **`$Undo`**: **Ctrl+Z**. Harfa D6-A5-F#5, glissando dolů (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`$Redo`**: **Ctrl+Shift+Z**. Harfa F#5-A5-D6, glissando nahoru (vol 30). Z ☐ H ☐ N ☐ Pozn.:

### Úpravy kódu a refaktoring (nylonová kytara, každá akce jiné gesto)
- [ ] **`CommentByLineComment`**: **Ctrl+/**. Kytara E5, jedno brnknutí (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`ReformatCode`**: **Ctrl+Alt+L**. Kytara, akord „strum“ D4-A4-D5-F#5 (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`OptimizeImports`**: **Ctrl+Alt+O**. Kytara A4→D5 (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`RenameElement`**: **Shift+F6** na symbolu. Kytara, dvojzvuk D5+F#5 naráz (vol 35). Hraje při spuštění rename, ne až po potvrzení. Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`ExtractMethod`**: označ kus kódu → **Ctrl+Alt+M**. Kytara A4-E5-A5, stoupá (vol 35). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`ShowIntentionActions`**: **Alt+Enter**. Zvonkohra B5, tiché cinknutí (vol 20). Z ☐ H ☐ N ☐ Pozn.:

### Navigace (elektrické piano) a hledání (vibrafon)
- [ ] **`GotoFile`**: **Ctrl+Shift+N**. El. piano D5→A5 (vol 35). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`GotoClass`**: **Ctrl+N**. El. piano D5-F#5-A5 (vol 35). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`GotoDeclaration`**: **Ctrl+B** na symbolu (Ctrl+klik možná nehraje). El. piano A5→D5, „ponor“ dolů (vol 35). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`FindInPath`**: **Ctrl+Shift+F**. Vibrafon A5-D6-A6, rychlý běh (vol 30). Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`SearchEverywhere`**: **Shift Shift**. Vibrafon, dyáda D6+A6 (vol 30). Z ☐ H ☐ N ☐ Pozn.:

### VCS (kalimba) a debug (steel drum)
- [ ] **`Vcs.Push`**: **Ctrl+Shift+K**. Kalimba D5-A5-D6, stoupá, „odeslat“ (vol 45). Hraje při otevření Push dialogu. Z ☐ H ☐ N ☐ Pozn.:
- [ ] **`ToggleLineBreakpoint`**: **Ctrl+F8**. Steel drum F#5 ťuk (vol 30). Klik do gutteru možná nehraje. Z ☐ H ☐ N ☐ Pozn.:

**Záměrně nenamapované akce:** `Build`, `Run`, `Debug`, `Stop`, `SaveAll`. Každá z nich vyvolá i lifecycle událost a kvůli „last wins“ by se zvuky přebíjely. Pokud je chceš zkusit, namapuj je ručně v nastavení.

---

## 3. Známé překryvy a věci k ověření

1. **Testy:** jeden běh testů vyvolá `run.process.started` → `test.finished.*` → `run.process.terminated*`.
   - Testovací zvuk může hned přebít zvuk ukončení procesu (při failu „run failed“, při úspěchu „run ok“).
   - Zapiš si, co skutečně slyšíš. Pokud test zvuk zaniká, opravíme to v kódu (např. potlačit terminated po testech).
2. **Commit:** po úspěšném commitu IDE často ukáže info balónek, takže `notification.shown.info` může přebít `vcs.commit.completed`.
3. **Otevření projektu:** `project.opened`, `indexing.finished` a `app.frame.activated` přijdou rychle po sobě a hraje ten poslední.
4. **Autosave:** `file.saved` hraje i při ztrátě focusu, ne jen na Ctrl+S.
5. **První projekt po startu IDE:** soundpacky se načítají v `postStartupActivity`, která běží až **po** `projectOpened`.
   - První `project.opened` po startu IDE proto možná nezazní (pack ještě není načtený).
   - Druhé otevření projektu už by mělo hrát. Ověř a zapiš.
6. **Debugger:** `debug.breakpoint.hit` hraje při každém kroku. Posuď, jestli to není otravné.

---

## 4. Celkový dojem

- [ ] Zvuky k sobě ladí (stejný styl a tónina).
- [ ] Úspěch vs. chyba se dá rozeznat bez dívání.
- [ ] Časté akce (Copy/Paste, uložení, focus okna) nejsou otravné ani po delší práci.
- [ ] Hlasitosti jsou vůči sobě vyvážené.

Poznámky:

---

*Zvuky jsou vygenerované z `src/soundgen/kotlin/cz/jarda/soundplugin/soundgen/DemoSoundCatalog.kt` příkazem `.\gradlew.bat generateDemoSounds`. Volitelně s lepší SoundFont: `-Psoundfont=C:\cesta\banka.sf2`. Zdrojové `.mid` soubory jsou v `src/soundgen/midi/`. Po změně zvuků zvyš `PACK_VERSION` v `GenerateDemoPack.kt`, aby se pack v nainstalovaném IDE obnovil.*

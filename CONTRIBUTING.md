# Bijdragen aan SwitchRecompHelper

SwitchRecompHelper pakt een XCI-dump van je **eigen** Switch-cartridge uit naar ExeFS en RomFS. De
opbouw is bewust gescheiden: `core/` is pure Kotlin zonder Android-afhankelijkheden, `app/` is de UI
met het Storage Access Framework.

## Oriëntatie

- `core/src/main/kotlin/.../fs/` — `Xci.kt` en `Containers.kt` (partities en containers), `Nca.kt`
  (header, secties, titelsleutels), `Ncz.kt` (compressie) en `Ticket.kt`.
- `core/src/main/kotlin/.../Extractor.kt` — van dump naar mappen, met `safeName`/`safePath`
  (regels 347-353) voor namen uit de ROM en `closeLeftovers` voor afgebroken runs; ernaast
  `KeySet.kt`, `crypto/` (`Aes.kt`) en `io/` (`RandomAccessSource.kt`).
- `core/src/main/kotlin/.../fs/Nso.kt` en `Lz4.kt` — NSO-header, LZ4-blokdecompressie en segmenthashes;
  `RecompInfo.kt` schrijft `recomp_info.toml`.
- `core/src/main/kotlin/.../fs/Bktr.kt` — updates: bucket tree, de AES-CTR-ex-laag met subsectie-counters
  en de relocatielaag die base en patch samenvoegt (`Nca.openPatchedSection`). `HashVerify.kt` — de
  hashcontrole van IVFC (RomFS) en HierarchicalSha256 (PFS0), die bij elke gelezen blok de hash naloopt.
- `app/src/main/java/.../ExtractionService.kt` — foreground service (achtergrond, melding, annuleren) die
  de extractie in `MainViewModel` in leven houdt; de koppeling loopt via `ExtractionBridge`.
- `app/src/main/java/.../MainViewModel.kt` — keuzes van de gebruiker, voortgang en
  SAF-uitvoer (`SafSink.kt`), plus `MainActivity.kt` en `OnboardingActivity.kt`.

## Testen en bouwen

```sh
./gradlew :core:test          # synthetische XCI/NSP/NSZ-cases + IEEE 1619 XTS-vector
./gradlew :app:assembleDebug
```

CI draait precies deze twee stappen en publiceert daarna de debug-APK.

## Regels

- **`core/` blijft Android-vrij.** Alles wat formaatkennis of cryptografie raakt, hoort in `core/`
  met een JVM-test erbij; `app/` wordt niet getest in CI en is dus geen plek voor parsers.
- **Synthetische fixtures, eigen sleutels.** De tests bouwen hun eigen XCI/NSP/NSZ met zstd-compressie
  en testsleutels in dezelfde layout die de parser leest. Er staat geen Nintendo-materiaal in deze
  repo, en er komt geen `prod.keys`-voorbeeld bij: een dump van een echte cartridge kun je niet
  meesturen.
- **Elke niet-ondersteunde sectie geeft een melding, geen stiltesucces.** Updates in NSZ/XCZ, SHA-3-hashes,
  patch-tabellen met L2-knopen en console-gebonden tickets zijn expliciet buiten scope (`README.md`); een
  verbetering daar verandert dus ook die lijst en de release-notities. Een hashcontrole die faalt, stopt het
  uitpakken met een melding; er is alleen een uitweg via de optie "Hashes controleren" (`verifyHashes`).
- **Namen uit een ROM zijn ongecontroleerd.** Een pad dat buiten de gekozen uitvoermap wijst, moet
  door `safePath` afgeschermd blijven; gedraagt een dump zich anders, meld dat dan via
  [SECURITY.md](SECURITY.md).
- **Resultaatnetheid.** Na annuleren of een fout blijft er een half-uitgepakte map staan; verander
  dat gedrag alleen samen met een test die laat zien wat er achterblijft.

## Documenten

Bij een nieuwe versie: kop in `README.md` (Nederlands, per release), licentie- en
derde-rechtenvermelding (`zstd-jni`) laten staan, en bij formeetwerk dat niet tegen een echte dump
gevalideerd is blijft die zin in `Testen` staan.

# Bijdragen aan SwitchRecompHelper

SwitchRecompHelper pakt een XCI-dump van je **eigen** Switch-cartridge uit naar ExeFS en RomFS. De
opbouw is bewust gescheiden: `core/` is pure Kotlin zonder Android-afhankelijkheden, `app/` is de UI
met het Storage Access Framework.

## Oriëntatie

- `core/src/main/kotlin/.../fs/` — `Xci.kt` en `Containers.kt` (partities en containers), `Nca.kt`
  (header, secties, titelsleutels), `Ncz.kt` (compressie) en `Ticket.kt`.
- `core/src/main/kotlin/.../Extractor.kt` — van dump naar mappen, met `safeName`/`safePath`
  (regels 302-309) voor namen uit de ROM en `closeLeftovers` voor afgebroken runs; ernaast
  `KeySet.kt`, `crypto/` (`Aes.kt`) en `io/` (`RandomAccessSource.kt`).
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
- **Elke niet-ondersteunde sectie geeft een melding, geen stiltesucces.** Updates/DLC (BKTR),
  hash-controle (IVFC), NSO-decompressie en console-gebonden tickets zijn nu expliciet buiten
  scope (`README.md`); een verbetering daar verandert dus ook die lijst en de release-notities.
- **Namen uit een ROM zijn ongecontroleerd.** Een pad dat buiten de gekozen uitvoermap wijst, moet
  door `safePath` afgeschermd blijven; gedraagt een dump zich anders, meld dat dan via
  [SECURITY.md](SECURITY.md).
- **Resultaatnetheid.** Na annuleren of een fout blijft er een half-uitgepakte map staan; verander
  dat gedrag alleen samen met een test die laat zien wat er achterblijft.

## Documenten

Bij een nieuwe versie: kop in `README.md` (Nederlands, per release), licentie- en
derde-rechtenvermelding (`zstd-jni`) laten staan, en bij formeetwerk dat niet tegen een echte dump
gevalideerd is blijft die zin in `Testen` staan.

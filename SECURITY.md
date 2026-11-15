# Beveiliging en onderhoud

SwitchRecompHelper werkt met je eigen `prod.keys` en met dumpbestanden van je eigen console. Deel
die bestanden nooit in een bugmelding: plakt alleen de foutmelding uit de app zelf.

Gebruik GitHub's private vulnerability reporting **als de eigenaar die route heeft ingeschakeld**.
Deze repo kent geen afzonderlijk vertrouwelijk contactadres. Werk dat niet, open dan een korte
issue waarin je een private contactroute vraagt, zonder exploitdetails of persoonlijke gegevens.

## Wat de app vertrouwt

- **`prod.keys` staat onverslüsseld** in de app-privémap (`filesDir/prod.keys`,
  `app/src/main/java/.../MainViewModel.kt`). `android:allowBackup="false"` houdt Android-backups
  buiten de deur, maar een geworteld toestel of een uitleesbare app-data-map leest het bestand direct.
  Er is nog geen "sleutels verwijderen"-knop: vervangen kan alleen door een ander bestand te kiezen.
- **Geen netwerkrechten.** De app heeft geen `INTERNET`-machtiging; uitpakken gebeurt volledig lokaal
  en de uitvoer gaat via het Storage Access Framework naar een map die jij kiest.
- **Geverifieerde releases bestaan nog niet.** De workflow publiceert een debug-gesigneerde APK
  (`.github/workflows/build.yml`). Gebruikers die die installeren, delen het Android-debugcertificaat;
  een eigen release-keystore is de bedoelde follow-up.

## Onderhoud

- Draai `./gradlew :core:test` (synthetische XCI/NSP/NSZ-cases en de IEEE 1619 XTS-vector) en
  `./gradlew :app:assembleDebug` vóór elke release; verifieer daarna op een echte cartridge-dump en
  meld die verificatie expliciet in de release-notities.
- Padnamen uit een ROM worden afgeknipt/weigerd (`safePath` in `core/`); gedraagt een dump zich
  anders, behandel dat dan als een beveiligingsprobleem en niet als een cosmeticafout.

Deze tekst beschrijft het huidige ontwerp, geen audit. Er wordt geen reactietijd toegezegd.

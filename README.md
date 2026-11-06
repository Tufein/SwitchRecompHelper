# SwitchRecompHelper

Android-app die een XCI-dump van je **eigen** Switch-cartridge uitpakt naar **ExeFS** en **RomFS**
(bijvoorbeeld als input voor static recompilation).

## Gebruik
1. Installeer de APK (`./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`).
2. Kies je `prod.keys` (van je eigen console, bv. met Lockpick_RCM). De app bevat **geen** sleutels en bewaart ze alleen in app-privé opslag.
3. Kies de `.xci`-dump en een uitvoermap (Storage Access Framework, geen opslagrechten nodig).
4. Uitpakken. Resultaat: `<naam>_extracted/<programId>/exefs/…` en `…/romfs/…`.

## Nieuw in 0.2.0
- Nieuw icoon (cartridge + uitpak-pijl, ook als themed icon) en een opgeschoonde Material 3-layout met licht/donker thema.
- Sneller uitpakken: lezen/ontsleutelen en schrijven lopen parallel, en het volgende uitvoerbestand wordt alvast aangemaakt. Voortgang toont nu ook snelheid en resterende tijd.
- Onboarding bij de eerste start (opnieuw te openen via het vraagteken rechtsboven).
- Fix: prod.keys kiezen crashte op Android 8–12.

## Ondersteund
- XCI (ook met 0x1000-certificaatblok), HFS0, NCA2/NCA3, AES-CTR secties, PFS0 (ExeFS), RomFS (IVFC).
- Standaard key-area-crypto en titlekeys (via `title.keys`-regels in hetzelfde bestand).

## Niet ondersteund (nog)
- NSP/NSZ/XCZ, updates/DLC (BKTR-patchsecties), NSO-decompressie, integriteitscontrole van hashes.
- Uitpakken draait in de voorgrond (scherm blijft aan); geen foreground-service.

## Opbouw
- `core/` – pure Kotlin, geen Android-afhankelijkheden, met unit tests (`./gradlew :core:test`).
- `app/` – Android UI en SAF-uitvoer.

## Testen
De tests gebruiken een synthetisch XCI met eigen testsleutels (zelfde layout als de parser leest) en de
IEEE 1619 XTS-testvector. Ze zijn **niet** gevalideerd tegen een echte cartridge-dump.

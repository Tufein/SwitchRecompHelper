# SwitchRecompHelper

Android-app die een XCI-dump van je **eigen** Switch-cartridge uitpakt naar **ExeFS** en **RomFS**
(bijvoorbeeld als input voor static recompilation).

## Gebruik
1. Installeer de APK (`./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`).
2. Kies je `prod.keys` (van je eigen console, bv. met Lockpick_RCM). De app bevat **geen** sleutels en bewaart ze alleen in app-privé opslag.
3. Kies de `.xci`-dump en een uitvoermap (Storage Access Framework, geen opslagrechten nodig).
4. Uitpakken. Resultaat:

```
<naam>_extracted/<programId>/
    exefs/                main, main.npdm, rtld, sdk, subsdk… (ongewijzigd)
    exefs_uncompressed/   dezelfde NSO's zonder LZ4-compressie, hashes gecontroleerd
    romfs/                gamedata
    recomp_info.toml      title-ID, build-ID's en SHA-256 van de ExeFS-bestanden
```

## Nieuw in 0.5.0: recomp-conventies
- **Ongecomprimeerde NSO's** in `exefs_uncompressed/`: de LZ4-gecomprimeerde segmenten (.text, .rodata, .data) worden uitgepakt
  en als geldig, ongecomprimeerd NSO weggeschreven (compressievlaggen uit, segmenten achter elkaar), zoals recompilers,
  disassemblers en loaders het verwachten. `exefs/` blijft byte-voor-byte gelijk aan de dump.
- **Hashcontrole**: de SHA-256 van elk segment wordt vergeleken met de hash in de NSO-header. Klopt die niet, dan stopt het
  uitpakken met een duidelijke melding in plaats van een stil, verkeerd uitvoerbestand.
- **`recomp_info.toml`** per titel: title-ID, bron-NCA, en per ExeFS-bestand grootte en SHA-256, plus voor NSO's de
  build-ID en de SHA-256 van de ongecomprimeerde versie. Zo kun je nagaan of je dump de versie is die een recomp verwacht.
- Beide staan standaard aan (`ExtractOptions(uncompressedNso, recompInfo)` in `core/`).

## Nieuw in 0.4.0
- Onboarding begint nu met **wat de app doet** (met een schema: dump → ontsleutelen → ExeFS + RomFS) en een pagina die uitlegt wat ExeFS en RomFS zijn en wat je ervan nodig hebt voor een recomp. Bestaande gebruikers zien de uitleg na de update één keer opnieuw.
- Korte uitleg bij de keuze ExeFS/RomFS op het hoofdscherm.
- Sneller bij veel kleine bestanden (RomFS): uitvoerbestanden worden met 4 tegelijk aangemaakt, sluiten gebeurt op de schrijfthread en het lezen van het volgende bestand wacht niet meer op het vorige. In een simulatie met trage bestandsaanmaak (3 ms aanmaken, 1 ms sluiten, 600 bestanden) ging dat van 1,9 s naar 0,7 s.
- Minder geheugengebruik per bestand (geen extra buffer van 1 MB per uitvoerbestand) en een lichtere UI tijdens het uitpakken (het logboek wordt alleen opgemaakt als het zichtbaar is).
- Sleutels worden bij het opstarten op de achtergrond geladen.

## Nieuw in 0.3.0
- **NSP en NSZ** (en XCZ) worden nu ook uitgepakt, naast XCI. Het type wordt aan de inhoud herkend, niet aan de extensie.
- NSP/NSZ: de titelsleutel komt uit de tickets (`.tik`) in het bestand, of uit `title.keys`-regels in je keys-bestand.
- NSZ/NCZ: zstd-decompressie (zowel losse stream als `NCZBLOCK`-blokken).
- Onboarding uitgebreid (formaten, titelsleutels, probleemoplossing); dubbele nummering in de stapkaarten weg.
- Patch-secties (updates) worden overgeslagen met een melding in plaats van de hele extractie te laten falen.

## Nieuw in 0.2.0
- Nieuw icoon (cartridge + uitpak-pijl, ook als themed icon) en een opgeschoonde Material 3-layout met licht/donker thema.
- Sneller uitpakken: lezen/ontsleutelen en schrijven lopen parallel, en het volgende uitvoerbestand wordt alvast aangemaakt. Voortgang toont nu ook snelheid en resterende tijd.
- Onboarding bij de eerste start (opnieuw te openen via het vraagteken rechtsboven).
- Fix: prod.keys kiezen crashte op Android 8–12.

## Ondersteund
- XCI (ook met 0x1000-certificaatblok), NSP, NSZ, XCZ; HFS0/PFS0, NCA2/NCA3, NCZ, AES-CTR secties, ExeFS, RomFS (IVFC).
- Standaard key-area-crypto en titlekeys (uit tickets of `title.keys`-regels). Persoonlijke (console-gebonden) tickets niet.

## Niet ondersteund (nog)
- Updates/DLC (BKTR-patchsecties), integriteitscontrole van de NCA/RomFS-hashlagen (IVFC, HierarchicalSha256). NSO-segmenthashes worden wel gecontroleerd.
- Uitpakken draait in de voorgrond (scherm blijft aan); geen foreground-service.

## Opbouw
- `core/` – pure Kotlin, geen Android-afhankelijkheden, met unit tests (`./gradlew :core:test`).
- `app/` – Android UI en SAF-uitvoer.

## Testen
De tests gebruiken synthetische XCI/NSP/NSZ-bestanden (met echte zstd-compressie en NSO's met echte LZ4-compressie) met eigen testsleutels (zelfde layout als de parser leest) en de
IEEE 1619 XTS-testvector. Ze zijn **niet** gevalideerd tegen een echte cartridge-dump.

## Licentie

SwitchRecompHelper staat onder de [GPL-3.0-or-later](LICENSE). De app bevat geen console-sleutels,
geen spelgegevens en geen Nintendo-materiaal; `prod.keys` en je dumps blijven op je eigen toestel.

## Beveiliging

Zie [SECURITY.md](SECURITY.md) voor hoe je een kwetsbaarheid meldt en welke geheimen dit project bewaart.

## Documentation

[Meedoen](CONTRIBUTING.md) · [Beveiliging](SECURITY.md)

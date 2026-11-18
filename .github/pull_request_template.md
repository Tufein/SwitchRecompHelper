# Pull request checklist

- [ ] `./gradlew :core:test` slaagt (synthetische XCI/NSP/NSZ-cases en de IEEE 1619 XTS-vector)
- [ ] `./gradlew :app:assembleDebug` bouwt; CI voert precies deze twee stappen uit
- [ ] Nieuwe logica staat in `core/` (pure Kotlin, geen Android-afhankelijkheden) en heeft daar een
      JVM-test; `app/` blijft UI en SAF-uitvoer
- [ ] Namen uit een ROM gaan nog steeds door `safeName`/`safePath`; een uitpakpad dat buiten de
      gekozen uitvoermap wijst, is een beveiligingsmelding en geen cosmetische fout
- [ ] Geen `prod.keys`, titelsleutel, dump of Nintendo-materiaal in de diff
- [ ] gedrag na annuleren of een fout (half-uitgepakte map) is bewust, of er staat een test bij die
      laat zien wat er achterblijft
- [ ] `README.md` (Nederlands) en de unsupported-lijst (BKTR/updates, IVFC,
      console-gebonden tickets) zijn aangepast als dit pakket daar iets aan verandert
- [ ] Zie [SECURITY.md](../SECURITY.md) voor wat wel en niet gedeeld mag worden

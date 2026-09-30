# OpenKNX Studio

Een experimentele, eenvoudige KNX-programmeertool voor eigen installaties.

## Doel van v0.1

De eerste versie richt zich op veilige, leesgerichte functies:

- KNXnet/IP-interfaces automatisch zoeken
- specifiek getest ontwerp voor de Weinzierl KNX IP Router 751 (5243)
- tunnelingverbinding testen
- duidelijke Nederlandstalige desktop-interface
- basis leggen voor busmonitor, groepsadressen en apparaatscan

**Belangrijk:** deze eerste versie schrijft nog geen adressen of parameters naar KNX-apparaten. Dat voegen we pas toe nadat discovery en tunneling stabiel getest zijn.

## Techniek

- Java 21
- JavaFX
- Calimero 3
- Gradle

Calimero levert de KNXnet/IP-communicatielaag; OpenKNX Studio bouwt daar een eenvoudige gebruikersinterface en projectlaag bovenop.

## Benodigd

1. Windows-pc op hetzelfde netwerk als de KNX/IP-router
2. JDK 21
3. Gradle 8+
4. Weinzierl KNX IP Router 751 aangesloten op LAN en KNX TP

## Starten

```bash
gradle run
```

Klik daarna op **Zoek KNX/IP**. De Weinzierl 751 hoort binnen enkele seconden in de resultaten te verschijnen.

## Roadmap

### v0.1
- [x] projectbasis
- [x] KNXnet/IP discovery
- [x] tunnelingverbinding testen
- [ ] busmonitor
- [ ] scan van individuele KNX-adressen
- [ ] programmeermodus detecteren

### v0.2
- [ ] groepsadressen beheren
- [ ] groepswaarden lezen/schrijven
- [ ] lokaal projectbestand
- [ ] ETS/KNX projectimport onderzoeken

### v0.3
- [ ] .knxprod import
- [ ] communicatieobjecten en parameters tonen
- [ ] gecontroleerde apparaatdownload voor één geselecteerde apparaatfamilie

## Veiligheidsprincipe

Schrijffuncties worden pas toegevoegd met:

- expliciete bevestiging
- dry-run/weergave van wat zal veranderen
- waar technisch mogelijk eerst een back-up of uitlezing
- duidelijke logging

OpenKNX Studio is in ontwikkeling en is geen vervanging voor ETS in professionele of gecertificeerde installaties.

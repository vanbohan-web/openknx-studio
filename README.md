# OpenKNX Studio

Een experimentele, eenvoudige KNX-programmeertool voor eigen installaties.

## Doel van v0.1

De eerste versie richt zich op veilige, leesgerichte functies:

- KNXnet/IP-interfaces automatisch zoeken
- specifiek getest ontwerp voor de Weinzierl KNX IP Router 751 (5243)
- tunnelingverbinding testen
- live KNX-groepstelegrammen monitoren
- duidelijke Nederlandstalige desktop-interface
- basis leggen voor groepsadressen en apparaatscan

**Belangrijk:** deze eerste versie schrijft nog geen adressen of parameters naar KNX-apparaten. Dat voegen we pas toe nadat discovery, tunneling en monitoring stabiel getest zijn.

## Techniek

- Java 21
- JavaFX
- Calimero 3.0-M2
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

Vul vervolgens het IP-adres van de router in en klik op:

1. **Test verbinding**
2. **Start busmonitor**

Druk daarna op een KNX-drukknop in huis. In het logvenster hoort een regel te verschijnen zoals:

```text
08:42:15.221 1.1.12 -> 1/0/4  WRITE  ASDU=01
```

De precieze adressen en data verschillen uiteraard per installatie.

## Roadmap

### v0.1
- [x] projectbasis
- [x] KNXnet/IP discovery
- [x] tunnelingverbinding testen
- [x] busmonitor voor groepstelegrammen
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

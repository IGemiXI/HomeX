# Automation – Szenen und Regeln für HomeX

**Szenen** schalten mehrere Geräte auf einmal („Gute Nacht“, „Kino“). **Regeln** lassen HomeX selbst handeln:
zu einer Uhrzeit, bei Sonnenauf- oder -untergang, oder wenn ein Messwert eine Grenze überschreitet.

Beide stehen als einfache Textdateien in `plugins/Automation/`. Die Dateien erklären ihr Format selbst und
bringen Beispiele für die Test-Geräte mit. Nach einer Änderung tippst du in der Konsole `automation neu`.

## Szenen – `szenen.txt`

```
szene Gute Nacht | mond
  typ:licht power aus
  typ:rollladen position 0
  licht-flur power an

szene Kino | film
  raum:Wohnzimmer+typ:licht power aus
  stehlampe-wohnzimmer helligkeit 15
```

| Ziel | Bedeutung |
|---|---|
| `licht-kueche` | genau dieses Gerät (IDs stehen im Dashboard unter System) |
| `raum:Wohnzimmer` | alle Geräte im Raum |
| `typ:licht` | alle Lichter (`licht`, `steckdose`, `rollladen`, `klima`) |
| `alle` | alle Geräte |
| `raum:Wohnzimmer+typ:licht` | beides muss passen |

Bei Gruppen schaltet HomeX nur Geräte, die den Befehl kennen. Ein Rollladen bekommt also kein `power aus`.
Symbole: `mond`, `sonne`, `aus`, `film`, `haus`, `weg`, `kaffee`, `stern`, `schnee`, `feuer`.

## Regeln – `regeln.txt`

```
regel Morgenlicht | um 06:30 werktags | licht-kueche power an; steckdose-kaffee power an
regel Außenlicht abends | bei sonnenuntergang | licht-aussen power an
regel Rollläden morgens | bei sonnenaufgang +30 min | typ:rollladen position 100
regel Gute Nacht | um 23:45 | szene Gute Nacht
regel Flurlicht nicht vergessen | wenn licht-flur.power = an für 30 min | licht-flur power aus
regel Solarstrom zum Kühlen | wenn solar.einspeisung_w > 2000 für 10 min | klima-wohnzimmer power an
```

| Wann | Beispiel |
|---|---|
| Uhrzeit | `um 06:30`, `um 06:30 werktags`, `um 09:00 wochenende`, `um 18:00 mo,mi,fr` |
| Sonne | `bei sonnenuntergang`, `bei sonnenaufgang -15 min` (braucht den Standort in `homex.properties`) |
| Bedingung | `wenn <id>.<wert> > 2000`, `… = an`, `… für 10 min` (Vergleiche `> < >= <= = !=`) |

Eine Bedingung löst **einmal** aus, wenn sie wahr wird. Erst wenn sie wieder falsch war, kann sie erneut
auslösen. So schaltet die Klima nicht alle paar Sekunden.

Die Sonnenzeiten berechnet HomeX selbst, ohne Internet, auf etwa eine Minute genau.

## Bedienen

- **Dashboard → Automation:** Szenen ausführen, Regeln an- und ausschalten, Regeln sofort **testen**.
  Dort siehst du auch, wann eine Regel das nächste Mal läuft und was beim letzten Mal passiert ist.
- **Konsole:** `szene` (Liste), `szene Gute Nacht`, `regel` (Liste), `regel Morgenlicht aus`, `regel Morgenlicht test`.

Ob eine Regel aktiv ist, merkt sich HomeX in `regeln-status.properties`. Die Beispiele für Solar und Klima
sind zuerst pausiert, bis diese Geräte da sind.

Szenen und Regeln sind für HomeX ganz normale Geräte (Typ `SCENE` bzw. `AUTOMATION`). Deshalb funktionieren
sie auch über die Web-API: `POST /api/devices/szene-gute-nacht {"key":"ausfuehren","value":1}`.

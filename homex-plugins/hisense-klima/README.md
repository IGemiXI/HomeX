# HisenseKlima – HomeX-Plugin für Hisense-Klimaanlagen (ConnectLife)

Steuert Hisense-Klimas, die in der **ConnectLife-App** eingerichtet sind: an/aus, Modus, Solltemperatur,
Lüfterstufe, Eco, Turbo und Leise-Modus. Außerdem liest es die Raumtemperatur aus.

## So funktioniert es

```
HomeX ──HTTP──▶ ConnectLife API Connector ──▶ Hisense-Cloud ──▶ Klimaanlagen
               (Docker, im Heimnetz)
```

Hisense bietet für ConnectLife keine offizielle API an. Der Open-Source-Dienst
[ConnectLife API Connector](https://github.com/bilan/connectlife-api-connector) übernimmt die Anmeldung
bei der Hisense-Cloud und bietet im Heimnetz eine einfache Schnittstelle an. Das Plugin spricht nur diese
Schnittstelle an. Wenn Hisense etwas an der Cloud ändert, musst du deshalb nur die Bridge aktualisieren
und nicht HomeX.

> ⚠️ Die Hisense-Cloud ist inoffiziell angebunden. Hisense kann sie jederzeit ändern. Frag sie nicht zu oft ab,
> 30 Sekunden sind ein guter Wert.

## 1. Bridge starten (einmalig)

Auf dem Heimserver oder einem anderen Rechner mit Docker:

1. Trag in `bridge/docker-compose.yml` deine ConnectLife-Zugangsdaten ein.
2. Starte die Bridge:
   ```bash
   cd homex-plugins/hisense-klima/bridge
   docker compose up -d --build
   curl http://localhost:8000/api/devices     # sollte deine Klimas zeigen
   ```

Falls die Anmeldung fehlschlägt, schau ins Log mit `docker logs connectlife-bridge`. Wenn sich dein
ConnectLife-Konto über Google oder Apple anmeldet, vergib in der App zusätzlich ein Passwort.

## 2. Plugin einrichten

Kopiere `HisenseKlima.jar` nach `plugins/`, starte HomeX und trag in `plugins/HisenseKlima/config.properties`
die Adresse der Bridge ein:

```properties
bridge.url=http://192.168.178.10:8000
```

Die Klimas werden automatisch erkannt. Die ID entsteht aus dem Namen in der App, z. B.
„Schlafzimmer“ → `klima-schlafzimmer`. Eine eigene ID vergibst du so:

```properties
id.pu1234567890=klima-buero
name.pu1234567890=Klima Büro
```

## Befehle

```
klima                          Liste aller Klimas mit Zustand
klima schlafzimmer an          einschalten   (aus = ausschalten)
klima alle aus                 alle ausschalten
klima schlafzimmer temp 22     Solltemperatur (16–30 °C, in der Config einstellbar)
klima schlafzimmer modus kuehlen     kuehlen | heizen | entfeuchten | lueften | auto
klima schlafzimmer luefter auto      auto | sehr_leise | leise | mittel | hoch | sehr_hoch
klima schlafzimmer eco an            eco | turbo | leise   an/aus
```

Statt `klima-schlafzimmer` reicht auch `schlafzimmer`. Mit dem eingebauten Befehl geht es auch:
`device klima-schlafzimmer solltemp 22`.

## Gerät in HomeX

Typ `CLIMATE`, Zustände:

| Schlüssel | Werte | steuerbar |
|---|---|---|
| `power` | true / false | ✔ |
| `modus` | kuehlen, heizen, entfeuchten, lueften, auto | ✔ |
| `solltemp` | °C | ✔ |
| `luefter` | auto, sehr_leise, leise, mittel, hoch, sehr_hoch | ✔ |
| `eco`, `turbo`, `leise` | true / false | ✔ |
| `ist_temp` | Raumtemperatur °C | – |
| `online` | true / false | – |
| `puid` | ConnectLife-Geräte-ID | – |

Jede Änderung löst zuerst ein `DeviceStateChangeEvent` aus. Andere Plugins können sie also **abbrechen
oder anpassen**, bevor der Befehl an die Klima geht. Ein Beispiel ist eine Automation, die keine
Solltemperatur unter 20 °C zulässt. Schlägt der Befehl fehl, wird die Anzeige zurückgesetzt.

## ConnectLife-Eigenschaften (für Entwickler)

Laut Connector-Doku für Split-Geräte (Typcode 009):

| ConnectLife | Bedeutung |
|---|---|
| `t_power` | 0 = aus, 1 = an |
| `t_work_mode` | 0 = Lüften, 1 = Heizen, 2 = Kühlen, 3 = Entfeuchten, 4 = Auto |
| `t_temp` | Solltemperatur |
| `t_fan_speed` | 0 = Auto, 5 = sehr leise, 6 = leise, 7 = mittel, 8 = hoch, 9 = sehr hoch |
| `t_eco` / `t_super` / `t_fan_mute` | Eco / Turbo / Leise |
| `f_temp_in` | Raumtemperatur |

Je nach Modell können die Eigenschaften abweichen. Was dein Gerät wirklich meldet, siehst du mit
`curl http://<bridge>:8000/api/devices`.

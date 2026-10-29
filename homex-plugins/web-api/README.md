# WebAPI – REST-API und Dashboard für HomeX

Dieses Plugin startet einen Webserver und braucht dafür keine externe Bibliothek. Er bietet:

- eine **REST-API**, über die Apps und Webseiten Geräte auslesen und steuern,
- **Live-Updates** per Server-Sent Events, damit jede Änderung sofort im Browser ankommt,
- das **HomeX-Dashboard** als installierbare Web-App (PWA).

![Dashboard](../../docs/dashboard-desktop.png)

## Start

1. Kopiere `WebAPI.jar` nach `plugins/` und starte HomeX.
2. Im Log stehen die Adresse und der Zugangsschlüssel:
   ```
   [WebAPI] Dashboard: http://192.168.178.10:8080/
   [WebAPI] Zugangsschlüssel (Token): otkLDySdoB3FAMjZh3Bz...
   ```
3. Öffne die Adresse im Browser und gib den Schlüssel ein. Er bleibt im Browser gespeichert.
   Ein Link mit `?token=...` meldet direkt an und eignet sich z. B. für einen QR-Code am Tablet.
4. Auf dem Handy: *Zum Startbildschirm hinzufügen*. HomeX öffnet sich dann wie eine App.

Port, Adresse und Schlüssel stellst du in `plugins/WebAPI/config.properties` ein. Löschst du dort den `token`
und startest neu, erzeugt HomeX einen neuen Schlüssel. Alle bisher angemeldeten Geräte müssen sich dann
neu anmelden.

## API

Jede Anfrage braucht den Schlüssel, entweder als Header `Authorization: Bearer <token>` oder als `?token=<token>`.

| Methode | Pfad | |
|---|---|---|
| `GET` | `/api/info` | HomeX-Version, Plugins |
| `GET` | `/api/devices` | alle Geräte mit Zustand |
| `GET` | `/api/devices/{id}` | ein Gerät |
| `POST` | `/api/devices/{id}` | Gerät steuern |
| `GET` | `/api/events` | Live-Updates (Server-Sent Events) |
| `GET` | `/api/activity?limit=100` | Aktivitätsprotokoll (neueste zuerst) |
| `GET` | `/api/history?id=solar` | welche Messwerte es für ein Gerät gibt |
| `GET` | `/api/history?id=solar&key=pv_w&stunden=24` | Verlauf eines Messwerts (1 Wert pro Minute, bis 48 Std.) |

### Beispiele

```bash
TOKEN=dein-token

curl -H "Authorization: Bearer $TOKEN" http://homex:8080/api/devices

# ein Wert
curl -H "Authorization: Bearer $TOKEN" -X POST \
     -d '{"key":"solltemp","value":22}' http://homex:8080/api/devices/klima-schlafzimmer

# mehrere Werte auf einmal
curl -H "Authorization: Bearer $TOKEN" -X POST \
     -d '{"power":true,"modus":"kuehlen","solltemp":22}' http://homex:8080/api/devices/klima-schlafzimmer
```

Antwort beim Steuern: `200` mit `{"ok":true,"device":{...}}`. Kommt `409` mit `"abgelehnt":[...]` zurück,
war ein Wert ungültig, ein anderes Plugin hat ihn blockiert oder das Gerät war nicht erreichbar.

Schicke beim Steuern den Header `X-HomeX-Client: <Name>` mit (z. B. `Handy-App`). Dann steht dieser Name als
Auslöser in Konsole und Aktivitätsprotokoll:

```
[WebAPI] Dashboard (192.168.1.23): licht-kueche power true → OK
[WebAPI] Web-API (192.168.1.40): licht-flur helligkeit 50 → NICHT ausgeführt
```

### Gerät

```json
{ "id": "licht-kueche", "name": "Deckenlicht", "type": "LIGHT", "room": "Küche",
  "states": { "power": true }, "changed": 1793307853007, "source": "Dashboard" }
```

### Live-Updates

```js
const es = new EventSource("/api/events?token=" + token);
es.addEventListener("snapshot", e => /* alle Geräte (beim Verbinden) */ JSON.parse(e.data));
es.addEventListener("state",    e => /* {"id","key","value","source","changed"} */ JSON.parse(e.data));
es.addEventListener("activity", e => /* neuer Eintrag im Aktivitätsprotokoll */ JSON.parse(e.data));
es.addEventListener("device",   e => /* neues Gerät */ JSON.parse(e.data));
es.addEventListener("removed",  e => /* {"id"} */ JSON.parse(e.data));
es.addEventListener("plugins",  e => /* Plugin an/aus */ JSON.parse(e.data));
```

## Sicherheit

- Der Schlüssel wird in konstanter Zeit verglichen, damit er sich nicht über Zeitmessungen erraten lässt.
- Die Verbindung läuft über **HTTP ohne Verschlüsselung**. Im eigenen WLAN ist das in Ordnung. Für den Zugriff
  von unterwegs solltest du HomeX **nicht** einfach per Portfreigabe ins Internet stellen. Nimm stattdessen ein
  VPN (z. B. WireGuard in der Fritzbox) oder einen Reverse-Proxy mit HTTPS.
- `adresse=127.0.0.1` beschränkt den Zugriff auf den Rechner, auf dem HomeX läuft.

## Dashboard

Die Dateien liegen in `src/main/resources/web/` (HTML, CSS, JavaScript, ohne Build-Schritt und ohne Bibliotheken).

- **Übersicht:** Alle Geräte nach Räumen, als leuchtende Waben. **Tippen schaltet**, gedrückt halten oder
  Rechtsklick öffnet die Einstellungen (Helligkeit, Rollladen-Position, Klima). Darüber liegen die Szenen,
  daneben Energie und die letzten Ereignisse.
- **Energie:** Energiefluss zwischen Sonne, Batterie, Netz und Haus (die Leitungen laufen in die echte
  Stromrichtung), Tagesverlauf von PV und Verbrauch, Sonnenprognose und Wetter.
- **Klima:** alle Klimas mit Raum- und Solltemperatur, Modus, Lüfter, Eco/Turbo/Leise.
- **Automation:** Szenen ausführen, Regeln an- und ausschalten oder sofort testen.
- **Aktivität:** Was wann passiert ist und wer es ausgelöst hat, mit Filter für Fehler.
- **System:** Plugins, alle Geräte-IDs zum Kopieren und ein Anmelde-Link für Tablets.

Ein Befehl gilt erst als ausgeführt, wenn HomeX ihn bestätigt. Bis dahin pulsiert die Wabe. Schlägt er fehl,
wackelt sie und färbt sich magenta. Den Grund zeigt die Konsole.

Neue Geräte aus anderen Plugins erscheinen automatisch, ohne dass du das Dashboard ändern musst.

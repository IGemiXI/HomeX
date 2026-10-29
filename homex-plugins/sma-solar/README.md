# SmaSolar – HomeX-Plugin für SMA-Wechselrichter

Liest einen SMA Sunny Tripower Smart Energy aus, und auch andere SMA-Geräte mit SMA-Modbus-Profil.
Es gibt zwei Quellen. Du schaltest in `plugins/SmaSolar/config.properties` oder per Befehl um:

| | `modbus` (lokal, empfohlen) | `cloud` (SMA Monitoring API) |
|---|---|---|
| Verbindung | direkt im Heimnetz, Port 502 | über smaapis.de, OAuth2 |
| Aktualität | jede Sekunde möglich | Daten 5–15 min alt, Rate-Limit |
| Voraussetzung | Modbus TCP im Wechselrichter aktivieren | Client-ID/Secret von SMA |

## Modbus einrichten

1. Öffne im Browser die IP des Wechselrichters und melde dich **als Installateur** an.
2. Unter *Geräteparameter → Externe Kommunikation → Modbus* den **TCP-Server** einschalten (Port 502) und speichern.
   Dafür brauchst du kein OK von SMA, nur das Installateur-Passwort. Das legt der Installateur bei der Inbetriebnahme fest.
3. Fertig – mit `modbus.host=auto` (Standard) **sucht HomeX den Wechselrichter selbst** im Heimnetz. Ändert sich
   seine IP, sucht HomeX automatisch neu. Das ist ideal für Router ohne feste IPs, z. B. den Starlink-Router.
   - Mehrere SMA-Geräte im Netz? Trag die Seriennummer vom Typenschild in `modbus.seriennummer` ein.
   - Lieber eine feste Adresse? `modbus.host=192.168.1.200` (oder ein Gerätename) geht auch.
4. Die Unit-ID ist bei SMA standardmäßig `3`.

Diese Register werden gelesen (SMA-Modbus-Profil):

| Register | Wert | Typ |
|---|---|---|
| 30201 | Gerätestatus (307 = OK) | U32 |
| 30513 / 30517 | Ertrag gesamt / heute (Wh) | U64 |
| 30773 + 30961 | DC-Leistung Eingang A + B (W) | S32 |
| 30775 | AC-Leistung (W) | S32 |
| 30845 | Batterie-Ladestand (%) | U32 |
| 30865 / 30867 | Netzbezug / Einspeisung (W), nur mit Energy Meter / Home Manager | S32 |
| 30953 | Innentemperatur (0,1 °C) | S32 |
| 31393 / 31395 | Batterie laden / entladen (W) | U32 |

Register, die dein Gerät nicht hat, werden einfach ausgelassen. SMA kann die Belegung mit Firmware-Updates ändern.
Die Liste steht in `ModbusQuelle.java`.

## Cloud einrichten

1. Beantrage bei SMA (api-developer-support@sma.de) eine Client-ID und ein Client-Secret. Dafür verlangt SMA eine
   Redirect-URL (z. B. `http://localhost/homex/sma`) und Links zu Datenschutz und Nutzungsbedingungen.
2. Trag in die Config ein: `cloud.client-id`, `cloud.client-secret`, `cloud.redirect-uri` und `cloud.plant-id`.
   Für deine echte Anlage setzt du außerdem `cloud.sandbox=false`.
3. In der HomeX-Konsole:
   ```
   sma quelle cloud
   sma login          → Link öffnen, bei SMA einloggen, zustimmen
   sma code <URL>     → die komplette Adresse, auf der du gelandet bist
   ```
   Der Refresh-Token (`offline_access`) wird automatisch in der Config gespeichert. Danach läuft alles von allein.

> Den Token-Endpunkt und den Login-Ablauf beschreibt SMA öffentlich. Die genauen Pfade der Monitoring-API
> stehen nur in SMAs Swagger-Doku. Weichen sie ab, passt du `cloud.api-url.*` und `cloud.messwerte-pfad`
> in der Config an, ohne neu zu kompilieren.

## Befehle

| Befehl | |
|---|---|
| `sma` / `sma status` | aktuelle Werte |
| `sma jetzt` | sofort neu abfragen |
| `sma quelle <modbus\|cloud>` | Quelle umschalten (wird gespeichert) |
| `sma login` / `sma code <code>` | Cloud-Anmeldung |

## Gerät in HomeX

Das Gerät hat die ID `solar` (Typ `SOLAR_INVERTER`) und diese Zustände:
`pv_w, ac_w, einspeisung_w, netzbezug_w, verbrauch_w, batterie_soc, batterie_laden_w, batterie_entladen_w,
ertrag_heute_kwh, ertrag_gesamt_kwh, temperatur_c, status, quelle, zeitpunkt`.

Andere Plugins können über `DeviceStateChangeEvent` mithören, z. B. „Klima an, wenn die Einspeisung über 2000 W liegt“.

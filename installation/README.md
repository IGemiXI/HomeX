# HomeX auf dem Server (Proxmox)

HomeX läuft dauerhaft in einem kleinen Debian-Container auf deinem Proxmox. Er startet nach einem Neustart
von selbst, und nach einem Absturz startet er sich innerhalb von 10 Sekunden neu.

## 1. Container anlegen (Proxmox-Weboberfläche)

1. Falls noch keine Vorlage da ist: *local → CT Templates → Templates* → **debian-13-standard** (oder 12) herunterladen.
2. Oben rechts auf **Create CT** klicken:
   - **Hostname:** `homex`, Passwort für root vergeben
   - **Template:** debian-13-standard
   - **Disks:** 16 GB
   - **CPU:** 2 Kerne
   - **Memory:** 2048 MB, Swap 512 MB
   - **Network:** IPv4 **Static**, z. B. `192.168.1.200/24`, Gateway `192.168.1.1`
     (die Adresse eines Starlink-Routers; prüfe in `ip route` auf dem Proxmox-Host, welches Gateway dein Netz hat)
   - **DNS:** leer lassen (übernimmt den Host)
3. Nach dem Anlegen: Container → **Options → Features** → **Nesting** ✔ und **keyctl** ✔.
   Das brauchst du nur für die Klimas (Docker), schaden tut es aber nicht.
4. **Options → Start at boot:** Ja.
5. Container starten.

## 2. HomeX installieren

In Proxmox den Container auswählen → **Console**, als `root` anmelden und eingeben:

```bash
apt update && apt install -y git
git clone https://github.com/IGemiXI/HomeX.git /root/HomeX
bash /root/HomeX/installation/install.sh
```

Das Skript fragt nach dem Namen deines Zuhauses und nach deinem Standort (für Wetter und Sonnenzeiten).
Am Ende zeigt es die Adresse des Dashboards und den Zugangsschlüssel an. Beim ersten Mal dauert das etwa
5 bis 10 Minuten, weil Java und die Maven-Bibliotheken heruntergeladen werden.

> Ist das GitHub-Repo privat? Dann im `git clone` und beim Skript einen Token mitgeben:
> `HOMEX_REPO=https://<token>@github.com/IGemiXI/HomeX.git bash /root/HomeX/installation/install.sh`

## 3. Bedienen

| Befehl | |
|---|---|
| `homex konsole` | die HomeX-Konsole öffnen. **Verlassen ohne HomeX zu beenden: Strg+B, dann D** |
| `homex befehl <text>` | einen Konsolenbefehl schicken und die Antwort sehen, z. B. `homex befehl sma` |
| `homex log` | Log live ansehen (Strg+C beendet nur das Ansehen) |
| `homex adresse` | Dashboard-Adresse, Zugangsschlüssel und Direkt-Link fürs Handy |
| `homex config SmaSolar` | Config eines Plugins im Editor öffnen; `homex config homex` öffnet `homex.properties` |
| `homex plugin aus DummyGeraete` | ein Plugin abschalten (bleibt auch nach Updates aus); `an` schaltet es wieder ein |
| `homex update` | neuesten Stand von GitHub holen, bauen und neu starten. **Einstellungen bleiben erhalten** |
| `homex start` / `stop` / `neustart` / `status` | |

Dateien: Programm und Einstellungen liegen in `/opt/homex/server`, das Log in `/opt/homex/server/logs/homex.log`.

## 4. Solaranlage (Modbus) einbinden

Sobald am Wechselrichter **Modbus TCP** eingeschaltet ist:

```bash
homex befehl sma quelle modbus
homex befehl sma
```

HomeX sucht den Wechselrichter im Heimnetz selbst. Im Log steht `Wechselrichter gefunden: 192.168.1.x`.
Dafür muss der Container im **selben Netz** wie der Wechselrichter sein (siehe Schritt 1). Wird er nicht
gefunden, trag die IP fest ein: `homex config SmaSolar` → `modbus.host=192.168.1.x`, dann `homex befehl reload`.

## 5. Klimas einbinden (optional)

```bash
bash /opt/homex/src/installation/klima-bridge.sh
```

Das Skript installiert Docker, fragt nach deinen ConnectLife-Zugangsdaten (dieselben wie in der App),
startet die Bridge und verbindet HomeX damit. Die Zugangsdaten bleiben nur auf dem Server
(`/opt/homex/bridge/.env`, nur für root lesbar).

Die Anbindung an die Hisense-Cloud ist inoffiziell. Klappt sie nicht, zeigt das Skript das Log der Bridge an.

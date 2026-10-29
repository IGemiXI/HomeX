# HomeX – pluginfähiges Smarthome in Java

Aufgebaut wie ein Spigot-Server: `HomeX.jar` starten, alle `.jar`-Dateien im Ordner `plugins/`
werden beim Start geladen und über `onLoad()` → `onEnable()` → `onDisable()` gesteuert.

![HomeX Dashboard](docs/dashboard-desktop.png)

| Energie (mit Testdaten) | Automation | Handy |
|---|---|---|
| ![Energie](docs/dashboard-energie.png) | ![Automation](docs/dashboard-automation.png) | ![Handy](docs/dashboard-handy.png) |

## Projektaufbau

```
homex/
├── homex-api/            Die Schnittstelle für Plugins (wie die Spigot-API)
│   └── de.homex.api
│       ├── HomeX                     Zentraler Zugriff (wie Bukkit.getServer())
│       ├── plugin/JavaPlugin         Basisklasse jedes Plugins
│       ├── event/                    Events, @EventHandler, Listener
│       ├── device/                   Device, DeviceType, DeviceRegistry
│       ├── command/                  Konsolenbefehle
│       └── scheduler/                Wiederkehrende/verzögerte Aufgaben
├── homex-core/           Der eigentliche Server → baut HomeX.jar
│   └── de.homex.core
│       ├── Main                      Einstiegspunkt
│       ├── HomeXServer               Startet alles, eingebaute Befehle
│       ├── SimplePluginManager       Lädt/aktiviert/deaktiviert die Plugin-JARs
│       ├── PluginClassLoader         Eigener ClassLoader pro Plugin
│       └── DescriptionParser         Liest plugin.yml (ohne externe Bibliothek)
└── homex-plugins/
    ├── dummy-geraete/    Test-Geräte mit Konsolen-Feedback → baut DummyGeraete.jar
    ├── automation/       Szenen und Regeln → baut Automation.jar
    ├── wetter/           Wetter + Sonnenprognose (Open-Meteo) → baut Wetter.jar
    ├── sma-solar/        SMA-Wechselrichter (Modbus/Cloud) → baut SmaSolar.jar
    ├── hisense-klima/    Hisense-Klimas (ConnectLife) → baut HisenseKlima.jar
    └── web-api/          REST-API + Dashboard (PWA) → baut WebAPI.jar
```

## Bauen & Starten

Voraussetzung: Java 25 (JDK) und Maven 3.6.3 oder neuer.

```bash
mvn package

mkdir -p server/plugins
cp homex-core/target/HomeX.jar server/
cp homex-plugins/*/target/*.jar server/plugins/
cd server
java -jar HomeX.jar
```

Oder einfach `start.bat` (Windows) bzw. `./start.sh` (Linux/Mac) – das baut und startet in einem Rutsch.

> Umlaute kaputt in der Windows-Konsole? Vorher `chcp 65001` eingeben.

### homex.properties

Beim ersten Start legt HomeX neben der `HomeX.jar` eine `homex.properties` an. Dort stehen der **Name deines
Zuhauses** und der **Standort** (Breiten- und Längengrad). Den Standort brauchen das Wetter-Plugin und Regeln
mit Sonnenauf- und -untergang. Deine Koordinaten bekommst du in Google Maps: Rechtsklick auf dein Haus, die
erste Zeile sind die Zahlen.

## Konsolenbefehle

| Befehl | Was er tut |
|---|---|
| `help` | alle Befehle |
| `plugins` | alle Plugins (grün = aktiv, rot = inaktiv) |
| `plugin <name>` | Infos zu einem Plugin |
| `enable <plugin>` / `disable <plugin>` | Plugin zur Laufzeit an-/ausschalten |
| `reload` | alle Plugins entladen, `plugins/` neu einlesen, wieder aktivieren |
| `devices` | alle Geräte mit Raum und Zustand |
| `device <id> <schlüssel> <wert>` | Gerät steuern, z. B. `device licht-kueche power an` |
| `dummy` | Test-Geräte anzeigen; `dummy fehler 20` lässt 20 % der Befehle scheitern |
| `szene [Name]` | Szenen anzeigen oder ausführen, z. B. `szene Gute Nacht` |
| `regel [Name an\|aus\|test]` | Regeln anzeigen, schalten oder sofort testen |
| `automation neu` | `szenen.txt` und `regeln.txt` neu einlesen |
| `wetter` | aktuelles Wetter und Prognose |
| `sma`, `klima` | Solaranlage bzw. Klimas, siehe deren READMEs |
| `stop` | HomeX beenden |

## Ein eigenes Plugin schreiben

### 1. pom.xml – API als `provided`

```xml
<dependency>
    <groupId>de.homex</groupId>
    <artifactId>homex-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

### 2. `src/main/resources/plugin.yml` – Pflicht in jeder JAR

```yaml
name: HisenseKlima
version: 1.0.0
main: de.homex.hisense.HisensePlugin
author: Dennis
description: Steuert die Hisense-Klimaanlagen
depend: []          # Plugins, die vorher da sein MÜSSEN
softdepend: []      # Plugins, die vorher geladen werden, WENN vorhanden
```

### 3. Hauptklasse

```java
public class HisensePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getHomeX().getDeviceRegistry().register(new HisenseKlima("klima-schlafzimmer", "Klima Schlafzimmer"), this);
        registerListener(new MeinListener());
        registerCommand("klima", "klima <an|aus>", (sender, label, args) -> { ... return true; });
        getHomeX().getScheduler().runRepeating(this, this::abfragen, 0, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        // Verbindungen schließen. Geräte, Listener, Befehle und Tasks räumt HomeX selbst auf.
    }
}
```

### 4. Gerät

```java
public class HisenseKlima extends Device {
    // Der Raum ist optional – das Dashboard gruppiert die Geräte danach
    public HisenseKlima(String id, String name, String raum) { super(id, name, DeviceType.CLIMATE, raum); }

    @Override
    public boolean control(String key, String value) {
        // 1. Befehl an die echte Klima schicken
        // 2. Zustand speichern → löst DeviceStateChangeEvent aus
        return switch (key) {
            case "power" -> updateState("power", value.equals("on"));
            case "mode"  -> updateState("mode", value.toUpperCase());   // COOL, HEAT, DRY, FAN, AUTO
            case "temp"  -> updateState("temp", Integer.parseInt(value));
            default -> false;
        };
    }
}
```

Wer den Befehl gegeben hat ("Dashboard", "Konsole", "Szene Gute Nacht" …), steht in
`ControlSource.current()` und landet automatisch im Aktivitätsprotokoll. Gerätetypen: `LIGHT`, `SWITCH`,
`SHUTTER`, `CLIMATE`, `SENSOR`, `POWER_METER`, `SOLAR_INVERTER`, `BATTERY`, `WEATHER`, `SCENE`, `AUTOMATION`.
Das Dashboard zeigt jeden Typ passend an.

### 5. Auf Events reagieren

```java
public class MeinListener implements Listener {
    @EventHandler
    public void onChange(DeviceStateChangeEvent e) {
        if (e.getDevice().getType() == DeviceType.CLIMATE && e.getKey().equals("temp")
                && (int) e.getNewValue() < 18) {
            e.setCancelled(true);   // unter 18 °C verboten
        }
    }
}
```

## Was der Plugin-Manager für dich erledigt

- **Lade-Reihenfolge** nach `depend` / `softdepend` (topologische Sortierung, Zyklen werden erkannt)
- **Eigener ClassLoader** pro Plugin; Plugins können Klassen ihrer Abhängigkeiten benutzen
- **Fehler-Isolation**: Wirft ein Plugin im `onEnable()` einen Fehler, wird nur dieses Plugin deaktiviert
- **Automatisches Aufräumen** beim Deaktivieren: Tasks, Listener, Befehle und Geräte des Plugins
- **Abhängige Plugins** werden mit deaktiviert (wer `depend: [X]` hat, geht aus, wenn X ausgeht)
- **Reload** gibt die JAR-Dateien frei – Plugin austauschen, `reload` tippen, fertig
- **Eigener Datenordner** `plugins/<Name>/` mit `config.properties`

## Plugins

- [`SmaSolar`](homex-plugins/sma-solar/README.md) – Sunny Tripower per Modbus TCP oder SMA Cloud-API
- [`HisenseKlima`](homex-plugins/hisense-klima/README.md) – Hisense-Klimas (ConnectLife) steuern
- [`WebAPI`](homex-plugins/web-api/README.md) – REST-API mit Live-Updates, Aktivitätsprotokoll, Verlauf und das HomeX-Dashboard
- [`DummyGeraete`](homex-plugins/dummy-geraete/README.md) – Test-Lichter, Dimmer, Steckdosen und Rollläden mit OK/FEHLER in der Konsole
- [`Automation`](homex-plugins/automation/README.md) – Szenen und Regeln (Uhrzeit, Sonne, Bedingungen)
- [`Wetter`](homex-plugins/wetter/README.md) – Wetter, Vorhersage und PV-Prognose von Open-Meteo

## Ideen für die nächsten Plugins

- Echte Lichter und Steckdosen, z. B. Shelly oder Tasmota (lokale HTTP-API, ohne Cloud)
- Push-Nachrichten aufs Handy (z. B. über ntfy), wenn eine Regel auslöst oder ein Gerät ausfällt
- Szenen direkt im Dashboard anlegen und bearbeiten

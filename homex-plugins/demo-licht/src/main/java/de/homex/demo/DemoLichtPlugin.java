package de.homex.demo;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;
import de.homex.api.plugin.JavaPlugin;

import java.util.concurrent.TimeUnit;

/** Hauptklasse – steht in plugin.yml unter "main". */
public class DemoLichtPlugin extends JavaPlugin {

    private boolean nachtmodus = false;

    @Override
    public void onLoad() {
        getLogger().info("onLoad: Ich wurde geladen, bin aber noch nicht aktiv.");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cfg = getConfig();

        // 1) Geräte registrieren
        for (String raum : cfg.getProperty("lampen", "wohnzimmer").split(",")) {
            raum = raum.trim();
            if (raum.isEmpty()) continue;
            getHomeX().getDeviceRegistry().register(new SimLampe("licht-" + raum, "Licht " + raum), this);
        }
        SimStromzaehler zaehler = new SimStromzaehler();
        getHomeX().getDeviceRegistry().register(zaehler, this);

        // 2) Listener registrieren
        int max = Integer.parseInt(cfg.getProperty("nachtmodus.max-helligkeit", "30"));
        registerListener(new NachtmodusListener(this, max));

        // 3) Befehl registrieren
        registerCommand("nachtmodus", "nachtmodus <an|aus> – Lampen nachts dimmen", (sender, label, args) -> {
            if (args.length != 1) return false;
            nachtmodus = args[0].equalsIgnoreCase("an");
            sender.sendMessage("Nachtmodus ist jetzt " + (nachtmodus ? "AN (max " + max + " %)" : "AUS"));
            return true;
        });

        // 4) Wiederkehrende Aufgabe: Stromzähler simulieren
        long sek = Long.parseLong(cfg.getProperty("stromzaehler.intervall-sekunden", "5"));
        getHomeX().getScheduler().runRepeating(this, () -> {
            long lampenAn = getHomeX().getDeviceRegistry().getByType(DeviceType.LIGHT).stream()
                    .filter(d -> Boolean.TRUE.equals(d.getState("power"))).count();
            zaehler.messwert(180 + lampenAn * 9 + (int) (Math.random() * 40));
        }, 0, sek, TimeUnit.SECONDS);

        getLogger().info("Aktiv! " + getHomeX().getDeviceRegistry().getAll().size() + " Gerät(e) bekannt.");
    }

    @Override
    public void onDisable() {
        // Geräte, Listener, Befehle und Tasks räumt HomeX automatisch weg.
        // Hier nur eigene Sachen schließen (Netzwerkverbindungen, Dateien ...).
        getLogger().info("Tschüss – Lampen werden nicht mehr verwaltet.");
    }

    boolean isNachtmodus() { return nachtmodus; }

    // ------------------------------------------------------------------
    // Beispiel-Geräte
    // ------------------------------------------------------------------

    /** Simulierte Lampe. Ein echtes Plugin würde hier z. B. Shelly/Hue/Tasmota per HTTP ansprechen. */
    static final class SimLampe extends Device {
        SimLampe(String id, String name) {
            super(id, name, DeviceType.LIGHT);
            updateState("power", false);
            updateState("helligkeit", 100);
        }

        @Override
        public boolean control(String key, String value) {
            return switch (key) {
                case "power" -> updateState("power", value.equalsIgnoreCase("on") || value.equalsIgnoreCase("an")
                        || value.equalsIgnoreCase("true"));
                case "helligkeit" -> {
                    try {
                        int v = Math.max(0, Math.min(100, Integer.parseInt(value)));
                        yield updateState("helligkeit", v);
                    } catch (NumberFormatException e) {
                        yield false;
                    }
                }
                default -> false;
            };
        }
    }

    /** Simulierter Stromzähler – nur lesbar. */
    static final class SimStromzaehler extends Device {
        SimStromzaehler() {
            super("strom-haus", "Hausverbrauch", DeviceType.POWER_METER);
            updateState("watt", 0);
        }

        void messwert(long watt) { updateState("watt", watt); }

        @Override
        public boolean control(String key, String value) { return false; }
    }
}

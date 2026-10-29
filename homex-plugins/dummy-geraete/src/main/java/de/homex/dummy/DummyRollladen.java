package de.homex.dummy;

import de.homex.api.device.ControlSource;
import de.homex.api.device.DeviceType;
import de.homex.api.scheduler.Scheduler;
import de.homex.api.scheduler.Task;
import de.homex.api.plugin.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Test-Rollladen, der wie ein echter Zeit zum Fahren braucht.
 * Zustände: position (0 = zu … 100 = offen), bewegung (steht | faehrt_auf | faehrt_zu)
 * Steuerbar: position 0–100, fahren auf|zu|stopp
 */
final class DummyRollladen extends DummyGeraet {

    private static final int TAKT_MS = 250;

    private final JavaPlugin plugin;
    private final Scheduler scheduler;
    private Task fahrt;
    private double position = 100;
    private int ziel = 100;

    DummyRollladen(String id, String name, String raum, Verhalten v, Logger log, JavaPlugin plugin) {
        super(id, name, DeviceType.SHUTTER, raum, v, log);
        this.plugin = plugin;
        this.scheduler = plugin.getHomeX().getScheduler();
        updateState("position", 100);
        updateState("bewegung", "steht");
    }

    @Override String typName() { return "Rollladen"; }

    @Override
    public synchronized boolean control(String key, String value) {
        try {
            int neuesZiel;
            switch (key) {
                case "position" -> neuesZiel = prozent(value);
                case "fahren" -> {
                    switch (value.trim().toLowerCase(Locale.ROOT)) {
                        case "auf", "hoch", "oben", "offen" -> neuesZiel = 100;
                        case "zu", "runter", "unten" -> neuesZiel = 0;
                        case "stopp", "stop", "halt" -> {
                            if (!senden("stopp")) return false;
                            anhalten();
                            ok("gestoppt bei " + getState("position") + " %");
                            return true;
                        }
                        default -> throw new IllegalArgumentException("auf, zu oder stopp erwartet");
                    }
                }
                default -> { abgelehnt(key, value, "unbekannter Befehl (position, fahren)"); return false; }
            }
            if (!senden("fahre auf " + neuesZiel + " %")) return false;
            starten(neuesZiel);
            return true;
        } catch (IllegalArgumentException e) {
            abgelehnt(key, value, e.getMessage());
            return false;
        }
    }

    private void starten(int neuesZiel) {
        ziel = neuesZiel;
        if (Math.round(position) == ziel) {
            ok("steht schon bei " + ziel + " %");
            return;
        }
        boolean auf = ziel > position;
        updateState("bewegung", auf ? "faehrt_auf" : "faehrt_zu");
        log.info(String.format("…      %s%s: fährt %s auf %d %%", getName(),
                getRoom() == null ? "" : " (" + getRoom() + ")", auf ? "hoch" : "runter", ziel));
        if (fahrt != null) fahrt.cancel();
        double schritt = 100.0 / Math.max(1, verhalten.fahrzeitS) * TAKT_MS / 1000.0;
        // Der Auslöser (z. B. "Dashboard") soll auch bei den Zwischenschritten sichtbar bleiben
        Runnable takt = ControlSource.wrap(() -> takt(schritt));
        fahrt = scheduler.runRepeating(plugin, takt, TAKT_MS, TAKT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void takt(double schritt) {
        if (Math.abs(ziel - position) <= schritt) {
            position = ziel;
            updateState("position", ziel);
            anhalten();
            ok("Position " + ziel + " %" + (ziel == 0 ? " (zu)" : ziel == 100 ? " (offen)" : ""));
            return;
        }
        position += ziel > position ? schritt : -schritt;
        updateState("position", (int) Math.round(position));
    }

    private void anhalten() {
        if (fahrt != null) fahrt.cancel();
        fahrt = null;
        ziel = (int) Math.round(position);
        updateState("bewegung", "steht");
    }

    @Override
    void wiederherstellen(Map<String, String> w) {
        try {
            if (w.containsKey("position")) {
                position = ziel = Integer.parseInt(w.get("position"));
                updateState("position", ziel);
            }
        } catch (NumberFormatException ignored) {}
    }
}

package de.homex.dummy;

import de.homex.api.device.DeviceType;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Test-Licht. Mit {@code dimmbar} zusätzlich "helligkeit" 1–100.
 * Steuerbar: power an|aus|um, helligkeit 0–100 (0 = aus)
 */
final class DummyLicht extends DummyGeraet {

    private final boolean dimmbar;

    DummyLicht(String id, String name, String raum, boolean dimmbar, Verhalten v, Logger log) {
        super(id, name, DeviceType.LIGHT, raum, v, log);
        this.dimmbar = dimmbar;
        updateState("power", false);
        if (dimmbar) updateState("helligkeit", 100);
    }

    @Override String typName() { return dimmbar ? "Dimmer" : "Licht"; }

    @Override
    public synchronized boolean control(String key, String value) {
        try {
            switch (key) {
                case "power" -> {
                    Boolean ziel = anAus(value);
                    boolean an = ziel != null ? ziel : !Boolean.TRUE.equals(getState("power"));
                    if (!senden(an ? "an" : "aus")) return false;
                    if (!updateState("power", an)) return false;
                    ok(an ? (dimmbar ? "an (" + getState("helligkeit") + " %)" : "an") : "aus");
                    return true;
                }
                case "helligkeit" -> {
                    if (!dimmbar) { abgelehnt(key, value, "dieses Licht ist nicht dimmbar"); return false; }
                    int p = prozent(value);
                    if (!senden("Helligkeit " + p + " %")) return false;
                    if (p == 0) {
                        if (!updateState("power", false)) return false;
                        ok("aus");
                    } else {
                        if (!updateState("helligkeit", p) || !updateState("power", true)) return false;
                        ok("Helligkeit " + p + " %");
                    }
                    return true;
                }
                default -> { abgelehnt(key, value, "unbekannter Befehl (power" + (dimmbar ? ", helligkeit" : "") + ")"); return false; }
            }
        } catch (IllegalArgumentException e) {
            abgelehnt(key, value, e.getMessage());
            return false;
        }
    }

    @Override
    void wiederherstellen(Map<String, String> w) {
        if (w.containsKey("power")) updateState("power", Boolean.parseBoolean(w.get("power")));
        if (dimmbar && w.containsKey("helligkeit")) {
            try { updateState("helligkeit", Integer.parseInt(w.get("helligkeit"))); } catch (NumberFormatException ignored) {}
        }
    }
}

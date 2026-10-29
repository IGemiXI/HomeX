package de.homex.dummy;

import de.homex.api.device.DeviceType;

import java.util.Map;
import java.util.logging.Logger;

/** Test-Steckdose / Schalter. Steuerbar: power an|aus|um */
final class DummySteckdose extends DummyGeraet {

    DummySteckdose(String id, String name, String raum, Verhalten v, Logger log) {
        super(id, name, DeviceType.SWITCH, raum, v, log);
        updateState("power", false);
    }

    @Override String typName() { return "Steckdose"; }

    @Override
    public synchronized boolean control(String key, String value) {
        if (!key.equals("power")) { abgelehnt(key, value, "unbekannter Befehl (power)"); return false; }
        try {
            Boolean ziel = anAus(value);
            boolean an = ziel != null ? ziel : !Boolean.TRUE.equals(getState("power"));
            if (!senden(an ? "an" : "aus")) return false;
            if (!updateState("power", an)) return false;
            ok(an ? "an" : "aus");
            return true;
        } catch (IllegalArgumentException e) {
            abgelehnt(key, value, e.getMessage());
            return false;
        }
    }

    @Override
    void wiederherstellen(Map<String, String> w) {
        if (w.containsKey("power")) updateState("power", Boolean.parseBoolean(w.get("power")));
    }
}

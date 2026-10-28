package de.homex.sma;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Die Solaranlage als HomeX-Gerät. Nur lesbar – {@link #control} lehnt alles ab.
 *
 * Zustände (fehlt ein Wert, liefert die Quelle ihn nicht):
 *  pv_w, ac_w, einspeisung_w, netzbezug_w, verbrauch_w,
 *  batterie_soc, batterie_laden_w, batterie_entladen_w,
 *  ertrag_heute_kwh, ertrag_gesamt_kwh, temperatur_c, status, quelle, zeitpunkt
 */
public final class SolarGeraet extends Device {

    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("HH:mm:ss");

    SolarGeraet(String id, String name) {
        super(id, name, DeviceType.SOLAR_INVERTER);
        updateState("status", "START");
    }

    void uebernehmen(SolarData d, String quelle) {
        setze("pv_w", d.pvLeistungW());
        setze("ac_w", d.acLeistungW());
        setze("einspeisung_w", d.einspeisungW());
        setze("netzbezug_w", d.netzbezugW());
        setze("verbrauch_w", d.verbrauchW());
        setze("batterie_soc", d.batterieSoc());
        setze("batterie_laden_w", d.batterieLadenW());
        setze("batterie_entladen_w", d.batterieEntladenW());
        setze("ertrag_heute_kwh", d.ertragHeuteKwh());
        setze("ertrag_gesamt_kwh", d.ertragGesamtKwh());
        setze("temperatur_c", d.temperaturC());
        updateState("status", d.status());
        updateState("quelle", quelle);
        updateState("zeitpunkt", d.zeitpunkt() != null ? d.zeitpunkt() : LocalTime.now().format(ZEIT));
        if (getState("fehler") != null) updateState("fehler", "-");
    }

    /** Quelle ist noch nicht eingerichtet (z. B. Zugangsdaten fehlen) – kein Fehler, nur ein Hinweis. */
    void wartet(String grund) {
        updateState("status", "WARTET");
        updateState("fehler", grund);
    }

    void offline(String grund) {
        updateState("status", "OFFLINE");
        updateState("fehler", grund);
    }

    private void setze(String key, Double wert) {
        if (wert == null) return;
        // ganze Zahlen ohne ".0" anzeigen
        updateState(key, wert == Math.rint(wert) && Math.abs(wert) < 1e15 ? (Object) wert.longValue() : wert);
    }

    @Override
    public boolean control(String key, String value) {
        return false;   // ein Wechselrichter wird hier nur gelesen
    }
}

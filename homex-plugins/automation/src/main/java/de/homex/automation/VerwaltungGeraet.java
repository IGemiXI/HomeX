package de.homex.automation;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;

/**
 * Damit das Dashboard Szenen und Regeln anlegen, ändern und löschen kann, ohne dass die Web-API dieses
 * Plugin kennen muss. Es nimmt die Befehle als normale Geräte-Befehle entgegen.
 * <ul>
 *   <li>{@code regel-speichern}: 1. Zeile alte ID (leer = neu), 2. Zeile {@code <Name> | <Wann> | <Was>}</li>
 *   <li>{@code szene-speichern}: 1. Zeile alte ID, 2. Zeile {@code <Name> | <Symbol>}, danach ein Befehl pro Zeile</li>
 *   <li>{@code regel-loeschen}, {@code szene-loeschen}: die ID</li>
 * </ul>
 * Geht etwas schief, steht der Grund im Zustand {@code fehler}.
 */
final class VerwaltungGeraet extends Device {

    static final String ID = "automation";

    /** Bekommt Schlüssel und Wert, liefert null bei Erfolg oder den Fehlertext. */
    private final java.util.function.BiFunction<String, String, String> ausfuehren;

    VerwaltungGeraet(java.util.function.BiFunction<String, String, String> ausfuehren) {
        super(ID, "Automation", DeviceType.AUTOMATION);
        this.ausfuehren = ausfuehren;
        updateState("verwaltung", true);
    }

    @Override
    public boolean control(String key, String value) {
        if (!key.matches("(regel|szene)-(speichern|loeschen)")) return false;
        String fehler;
        try {
            fehler = ausfuehren.apply(key, value == null ? "" : value);
        } catch (RuntimeException e) {
            fehler = "Interner Fehler: " + e.getMessage();
        }
        if (fehler == null) removeState("fehler"); else updateState("fehler", fehler);
        return fehler == null;
    }
}

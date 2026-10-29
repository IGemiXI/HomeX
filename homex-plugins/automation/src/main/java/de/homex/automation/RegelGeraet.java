package de.homex.automation;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;
import de.homex.automation.Modell.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Eine Regel als HomeX-Gerät. Steuern: {@code aktiv an|aus}, {@code ausfuehren 1} (zum Testen).
 * Zustände: aktiv, wann, was, zuletzt, ergebnis, naechste, hinweis
 */
final class RegelGeraet extends Device {

    final Parser.RegelDef def;
    private final BiConsumer<RegelGeraet, String> ausloesen;
    private final BiConsumer<RegelGeraet, Boolean> aktivGeaendert;

    // Laufzeit-Zustand (nur vom Automation-Thread benutzt)
    LocalDate zuletztAm;              // Zeit-/Sonnenregeln: an welchem Tag schon ausgelöst
    LocalDateTime bedingungSeit;      // Bedingungen: seit wann erfüllt
    boolean bedingungAusgeloest;      // Bedingungen: schon ausgelöst, bis sie wieder falsch wird

    RegelGeraet(Parser.RegelDef def, boolean aktiv,
                BiConsumer<RegelGeraet, String> ausloesen, BiConsumer<RegelGeraet, Boolean> aktivGeaendert) {
        super("regel-" + def.id(), def.name(), DeviceType.AUTOMATION);
        this.def = def;
        this.ausloesen = ausloesen;
        this.aktivGeaendert = aktivGeaendert;
        updateState("aktiv", aktiv);
        updateState("art", switch (def.ausloeser()) {
            case Zeit z -> "zeit";
            case SonnenEreignis s -> "sonne";
            case Bedingung b -> "bedingung";
        });
        updateState("wann", Modell.beschreibung(def.ausloeser()));
        updateState("was", def.aktionen().stream().map(Object::toString).collect(Collectors.joining("; ")));
    }

    boolean aktiv() { return Boolean.TRUE.equals(getState("aktiv")); }

    void zustand(String key, Object wert) {
        if (wert == null) removeState(key); else updateState(key, wert);
    }

    @Override
    public boolean control(String key, String value) {
        switch (key) {
            case "aktiv" -> {
                Boolean an = switch (value.trim().toLowerCase(Locale.ROOT)) {
                    case "an", "ein", "on", "true", "1" -> true;
                    case "aus", "off", "false", "0" -> false;
                    default -> null;
                };
                if (an == null) return false;
                if (!updateState("aktiv", an)) return false;
                bedingungSeit = null;
                bedingungAusgeloest = false;
                aktivGeaendert.accept(this, an);
                return true;
            }
            case "ausfuehren", "start", "test" -> {
                ausloesen.accept(this, "von Hand");
                return true;
            }
            default -> { return false; }
        }
    }

    List<Aktion> aktionen() { return def.aktionen(); }
}

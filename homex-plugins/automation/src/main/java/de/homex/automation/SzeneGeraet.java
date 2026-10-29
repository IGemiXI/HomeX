package de.homex.automation;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * Eine Szene als HomeX-Gerät – so taucht sie automatisch in der API und im Dashboard auf.
 * Steuern: {@code ausfuehren 1}
 * Zustände: symbol, befehle, zuletzt, ergebnis
 */
final class SzeneGeraet extends Device {

    static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Function<SzeneGeraet, Ausfuehrer.Ergebnis> ausfuehren;
    final Modell.Szene szene;

    SzeneGeraet(Modell.Szene szene, Function<SzeneGeraet, Ausfuehrer.Ergebnis> ausfuehren) {
        super("szene-" + szene.id(), szene.name(), DeviceType.SCENE);
        this.szene = szene;
        this.ausfuehren = ausfuehren;
        updateState("symbol", szene.symbol());
        updateState("befehle", szene.befehle().size());
    }

    @Override
    public boolean control(String key, String value) {
        if (!key.equals("ausfuehren") && !key.equals("start")) return false;
        Ausfuehrer.Ergebnis e = ausfuehren.apply(this);
        updateState("ergebnis", e.toString());
        updateState("zuletzt", LocalDateTime.now().format(ZEIT));
        return e.ok() > 0 || e.gesamt() == 0;
    }
}

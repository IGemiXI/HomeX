package de.homex.automation;

import de.homex.api.HomeX;
import de.homex.api.device.ControlSource;
import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;
import de.homex.automation.Modell.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Führt Befehle und Szenen aus und zählt, was geklappt hat. */
final class Ausfuehrer {

    record Ergebnis(int ok, int gesamt, List<String> fehler) {
        boolean allesOk() { return ok == gesamt; }
        @Override public String toString() {
            return gesamt == 0 ? "kein passendes Gerät" : ok + " von " + gesamt + " ausgeführt";
        }
    }

    private static final Map<String, DeviceType> TYPEN = Map.of(
            "licht", DeviceType.LIGHT, "steckdose", DeviceType.SWITCH, "schalter", DeviceType.SWITCH,
            "rollladen", DeviceType.SHUTTER, "jalousie", DeviceType.SHUTTER, "klima", DeviceType.CLIMATE);

    /** Befehle, die ein Gerät kennt, ohne dass sie in seinen Zuständen stehen. */
    private static final Map<DeviceType, List<String>> EXTRA = Map.of(DeviceType.SHUTTER, List.of("fahren"));

    private final HomeX homeX;
    private final Function<String, Szene> szenen;

    Ausfuehrer(HomeX homeX, Function<String, Szene> szenen) {
        this.homeX = homeX;
        this.szenen = szenen;
    }

    Ergebnis ausfuehren(List<? extends Aktion> aktionen, String quelle) {
        return ControlSource.call(quelle, () -> {
            int ok = 0, gesamt = 0;
            List<String> fehler = new ArrayList<>();
            for (Aktion a : aktionen) {
                Ergebnis e = switch (a) {
                    case Befehl b -> befehl(b);
                    case SzenenAufruf s -> szene(s.name());
                };
                ok += e.ok();
                gesamt += e.gesamt();
                fehler.addAll(e.fehler());
            }
            return new Ergebnis(ok, gesamt, fehler);
        });
    }

    private Ergebnis szene(String name) {
        Szene s = szenen.apply(name);
        if (s == null) return new Ergebnis(0, 1, List.of("Szene '" + name + "' gibt es nicht"));
        return ControlSource.call("Szene " + s.name(), () -> ausfuehren(s.befehle(), "Szene " + s.name()));
    }

    private Ergebnis befehl(Befehl b) {
        List<Device> ziele = ziele(b.ziel(), b.key());
        if (ziele == null) return new Ergebnis(0, 1, List.of("Gerät '" + b.ziel() + "' gibt es nicht"));
        int ok = 0;
        List<String> fehler = new ArrayList<>();
        for (Device d : ziele) {
            boolean geklappt;
            try {
                geklappt = d.control(b.key(), b.wert());
            } catch (RuntimeException ex) {
                geklappt = false;
            }
            if (geklappt) ok++;
            else fehler.add(d.getName() + ": " + b.key() + " " + b.wert());
        }
        return new Ergebnis(ok, ziele.size(), fehler);
    }

    /**
     * Welche Geräte meint das Ziel? Bei Gruppen (raum:/typ:/alle) nur Geräte, die den Schlüssel kennen.
     * @return null, wenn eine einzelne Geräte-ID nicht existiert
     */
    List<Device> ziele(String ziel, String key) {
        String z = ziel.toLowerCase(Locale.ROOT);
        boolean gruppe = z.equals("alle") || z.startsWith("raum:") || z.startsWith("typ:");
        if (!gruppe) return homeX.getDeviceRegistry().get(z).map(List::of).orElse(null);

        List<Device> out = new ArrayList<>();
        for (Device d : homeX.getDeviceRegistry().getAll()) {
            if (d.getType() == DeviceType.SCENE || d.getType() == DeviceType.AUTOMATION) continue;
            if (!passt(d, z)) continue;
            boolean kennt = d.getStates().containsKey(key) || EXTRA.getOrDefault(d.getType(), List.of()).contains(key);
            if (kennt) out.add(d);
        }
        return out;
    }

    private static boolean passt(Device d, String ziel) {
        if (ziel.equals("alle")) return true;
        for (String teil : ziel.split("\\+")) {
            if (teil.startsWith("raum:")) {
                if (d.getRoom() == null || !normal(d.getRoom()).equals(normal(teil.substring(5)))) return false;
            } else if (teil.startsWith("typ:")) {
                DeviceType t = TYPEN.get(teil.substring(4));
                if (t == null || d.getType() != t) return false;
            } else if (!teil.equals("alle")) {
                return false;
            }
        }
        return true;
    }

    /** "Küche" = "kueche" = "küche" */
    private static String normal(String s) {
        return s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss").replaceAll("\\s+", "");
    }
}

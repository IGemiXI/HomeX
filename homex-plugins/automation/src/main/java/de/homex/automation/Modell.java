package de.homex.automation;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bausteine von Szenen und Regeln. */
final class Modell {

    private Modell() {}

    // ------------------------------------------------------------------ Aktionen

    sealed interface Aktion permits Befehl, SzenenAufruf {}

    /** "licht-kueche power an" oder "typ:licht power aus" */
    record Befehl(String ziel, String key, String wert) implements Aktion {
        @Override public String toString() { return ziel + " " + key + " " + wert; }
    }

    /** "szene Gute Nacht" */
    record SzenenAufruf(String name) implements Aktion {
        @Override public String toString() { return "szene " + name; }
    }

    record Szene(String id, String name, String symbol, List<Befehl> befehle) {}

    // ------------------------------------------------------------------ Auslöser

    sealed interface Ausloeser permits Zeit, SonnenEreignis, Bedingung {}

    record Zeit(LocalTime uhrzeit, Set<DayOfWeek> tage) implements Ausloeser {}

    record SonnenEreignis(boolean aufgang, int versatzMin, Set<DayOfWeek> tage) implements Ausloeser {}

    record Bedingung(String geraet, String key, String op, String wert, int dauerMin) implements Ausloeser {}

    // ------------------------------------------------------------------ Hilfen

    static final Set<DayOfWeek> TAEGLICH = EnumSet.allOf(DayOfWeek.class);

    static String tageText(Set<DayOfWeek> tage) {
        if (tage.size() == 7) return "";
        if (tage.equals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))) return " werktags";
        if (tage.equals(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))) return " am Wochenende";
        StringBuilder sb = new StringBuilder(" ");
        for (DayOfWeek d : tage) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(switch (d) {
                case MONDAY -> "Mo"; case TUESDAY -> "Di"; case WEDNESDAY -> "Mi"; case THURSDAY -> "Do";
                case FRIDAY -> "Fr"; case SATURDAY -> "Sa"; case SUNDAY -> "So";
            });
        }
        return sb.toString();
    }

    /** Beschreibung für Menschen, z. B. "um 06:30 werktags" oder "wenn Einspeisung > 2000 für 10 min" */
    static String beschreibung(Ausloeser a) {
        return switch (a) {
            case Zeit z -> "um " + z.uhrzeit() + tageText(z.tage());
            case SonnenEreignis s -> "bei Sonnen" + (s.aufgang() ? "aufgang" : "untergang")
                    + (s.versatzMin() == 0 ? "" : (s.versatzMin() > 0 ? " +" : " ") + s.versatzMin() + " min")
                    + tageText(s.tage());
            case Bedingung b -> "wenn " + b.geraet() + "." + b.key() + " " + b.op() + " " + b.wert()
                    + (b.dauerMin() > 0 ? " für " + b.dauerMin() + " min" : "");
        };
    }

    /** "Außenlicht abends" → "aussenlicht-abends" */
    static String slug(String s) {
        String t = s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        t = t.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return t.isEmpty() ? "unbenannt" : t;
    }
}

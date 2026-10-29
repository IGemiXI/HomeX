package de.homex.automation;

import de.homex.automation.Modell.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Liest szenen.txt und regeln.txt. Fehler werden gesammelt (mit Zeilennummer), statt alles abzubrechen. */
final class Parser {

    record RegelDef(String id, String name, Ausloeser ausloeser, List<Aktion> aktionen) {}

    final List<String> fehler = new ArrayList<>();

    // ------------------------------------------------------------------ Szenen

    List<Szene> szenen(List<String> zeilen) {
        List<Szene> out = new ArrayList<>();
        String name = null, symbol = null;
        List<Befehl> befehle = null;
        int nr = 0;
        for (String roh : zeilen) {
            nr++;
            String z = ohneKommentar(roh);
            if (z.isBlank()) continue;
            if (z.trim().toLowerCase(Locale.ROOT).startsWith("szene ") && !Character.isWhitespace(roh.charAt(0))) {
                if (name != null) out.add(new Szene(Modell.slug(name), name, symbol, List.copyOf(befehle)));
                String rest = z.trim().substring(6);
                int bar = rest.indexOf('|');
                name = (bar < 0 ? rest : rest.substring(0, bar)).trim();
                symbol = bar < 0 ? "stern" : rest.substring(bar + 1).trim().toLowerCase(Locale.ROOT);
                befehle = new ArrayList<>();
                if (name.isEmpty()) { fehler.add("szenen.txt Zeile " + nr + ": Szene ohne Namen"); name = null; }
                continue;
            }
            if (name == null) {
                fehler.add("szenen.txt Zeile " + nr + ": Befehl ohne Szene darüber: " + z.trim());
                continue;
            }
            try {
                befehle.add(befehl(z.trim()));
            } catch (IllegalArgumentException e) {
                fehler.add("szenen.txt Zeile " + nr + ": " + e.getMessage());
            }
        }
        if (name != null) out.add(new Szene(Modell.slug(name), name, symbol, List.copyOf(befehle)));
        return out;
    }

    // ------------------------------------------------------------------ Regeln

    List<RegelDef> regeln(List<String> zeilen) {
        List<RegelDef> out = new ArrayList<>();
        int nr = 0;
        for (String roh : zeilen) {
            nr++;
            String z = ohneKommentar(roh).trim();
            if (z.isBlank()) continue;
            try {
                if (!z.toLowerCase(Locale.ROOT).startsWith("regel "))
                    throw new IllegalArgumentException("Zeile muss mit 'regel' beginnen");
                String[] teile = z.substring(6).split("\\|");
                if (teile.length != 3)
                    throw new IllegalArgumentException("Format: regel <Name> | <Wann> | <Was>");
                String name = teile[0].trim();
                if (name.isEmpty()) throw new IllegalArgumentException("Regel ohne Namen");
                Ausloeser wann = ausloeser(teile[1].trim());
                List<Aktion> was = new ArrayList<>();
                for (String a : teile[2].split(";")) if (!a.isBlank()) was.add(aktion(a.trim()));
                if (was.isEmpty()) throw new IllegalArgumentException("keine Aktion angegeben");
                out.add(new RegelDef(Modell.slug(name), name, wann, List.copyOf(was)));
            } catch (IllegalArgumentException e) {
                fehler.add("regeln.txt Zeile " + nr + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static final Pattern UM = Pattern.compile("(?i)um\\s+(\\d{1,2})[:.](\\d{2})(?:\\s+(.+))?");
    private static final Pattern SONNE = Pattern.compile(
            "(?i)bei\\s+sonnen(aufgang|untergang)(?:\\s*([+-])\\s*(\\d+)\\s*min)?(?:\\s+(.+))?");
    private static final Pattern WENN = Pattern.compile(
            "(?i)wenn\\s+([a-z0-9_-]+)\\.([a-z0-9_]+)\\s*(>=|<=|!=|==|=|>|<)\\s*(.+?)(?:\\s+f(?:ü|ue)r\\s+(\\d+)\\s*min)?");

    Ausloeser ausloeser(String s) {
        Matcher m = UM.matcher(s);
        if (m.matches()) {
            try {
                return new Zeit(LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), tage(m.group(3)));
            } catch (java.time.DateTimeException e) {
                throw new IllegalArgumentException("ungültige Uhrzeit: " + s);
            }
        }
        m = SONNE.matcher(s);
        if (m.matches()) {
            int versatz = m.group(3) == null ? 0 : Integer.parseInt(m.group(3)) * ("-".equals(m.group(2)) ? -1 : 1);
            return new SonnenEreignis(m.group(1).equalsIgnoreCase("aufgang"), versatz, tage(m.group(4)));
        }
        m = WENN.matcher(s);
        if (m.matches()) {
            String op = m.group(3).equals("==") ? "=" : m.group(3);
            return new Bedingung(m.group(1).toLowerCase(Locale.ROOT), m.group(2), op, m.group(4).trim(),
                    m.group(5) == null ? 0 : Integer.parseInt(m.group(5)));
        }
        throw new IllegalArgumentException("'" + s + "' nicht verstanden – erwartet 'um 06:30', 'bei sonnenuntergang' oder 'wenn gerät.wert > zahl'");
    }

    private static Set<DayOfWeek> tage(String s) {
        if (s == null || s.isBlank()) return Modell.TAEGLICH;
        String t = s.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "täglich", "taeglich", "jeden tag" -> { return Modell.TAEGLICH; }
            case "werktags" -> { return EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY); }
            case "wochenende", "am wochenende" -> { return EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY); }
            default -> { }
        }
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String d : t.split("[,\\s]+")) {
            out.add(switch (d) {
                case "mo" -> DayOfWeek.MONDAY; case "di" -> DayOfWeek.TUESDAY; case "mi" -> DayOfWeek.WEDNESDAY;
                case "do" -> DayOfWeek.THURSDAY; case "fr" -> DayOfWeek.FRIDAY; case "sa" -> DayOfWeek.SATURDAY;
                case "so" -> DayOfWeek.SUNDAY;
                default -> throw new IllegalArgumentException("unbekannte Tage '" + s + "' (werktags, wochenende, mo,di,…)");
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ Aktionen

    Aktion aktion(String s) {
        if (s.toLowerCase(Locale.ROOT).startsWith("szene ")) {
            String name = s.substring(6).trim();
            if (name.isEmpty()) throw new IllegalArgumentException("szene ohne Namen");
            return new SzenenAufruf(name);
        }
        return befehl(s);
    }

    static Befehl befehl(String s) {
        String[] t = s.trim().split("\\s+", 3);
        if (t.length != 3) throw new IllegalArgumentException("Befehl braucht <Ziel> <Schlüssel> <Wert>: " + s);
        return new Befehl(t[0], t[1].toLowerCase(Locale.ROOT), t[2].trim());
    }

    private static String ohneKommentar(String z) {
        String t = z.stripLeading();
        return t.startsWith("#") ? "" : z.stripTrailing();
    }

}

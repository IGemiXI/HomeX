package de.homex.web;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Aktivitätsprotokoll: merkt sich die letzten Ereignisse im Haus in verständlicher Form
 * ("Deckenlicht (Küche) eingeschaltet – Dashboard"). Messwerte, die sich ständig ändern, landen
 * nicht hier, sondern im {@link Verlauf}.
 */
final class Aktivitaet {

    private static final int MAX = 400;
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Typen, die laufend Messwerte liefern: nur Status-Wechsel protokollieren. */
    private static final Set<DeviceType> MESSGERAETE = Set.of(DeviceType.SOLAR_INVERTER, DeviceType.WEATHER,
            DeviceType.POWER_METER, DeviceType.SENSOR, DeviceType.BATTERY);
    private static final Set<String> NIE = Set.of("zeitpunkt", "aktualisiert", "fehler", "hinweis", "naechste",
            "ergebnis", "puid", "quelle", "ist_temp", "wann", "was", "art", "symbol", "befehle");

    private final Deque<Map<String, Object>> eintraege = new ArrayDeque<>();
    /** Geräte, die schon einmal erreichbar waren – erst danach ist "wieder erreichbar" eine Nachricht wert. */
    private final Set<String> warOnline = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Consumer<Map<String, Object>> neu;

    /** @param neu wird für jeden neuen Eintrag aufgerufen (→ Live-Update an die Browser) */
    Aktivitaet(Consumer<Map<String, Object>> neu) { this.neu = neu; }

    synchronized List<Map<String, Object>> liste(int max) {
        List<Map<String, Object>> out = new ArrayList<>(Math.min(max, eintraege.size()));
        for (Map<String, Object> e : eintraege) {
            if (out.size() >= max) break;
            out.add(e);
        }
        return out;
    }

    /** Eine Zustandsänderung – wird nur aufgenommen, wenn sie für Menschen interessant ist. */
    void aenderung(Device d, String key, Object alt, Object neuWert, String quelle) {
        if (NIE.contains(key)) return;
        DeviceType t = d.getType();
        boolean ausgefuehrt = key.equals("zuletzt") && (t == DeviceType.SCENE || t == DeviceType.AUTOMATION);
        if (alt == null && !ausgefuehrt) return;          // Gerät meldet sich zum ersten Mal

        String text;
        if (MESSGERAETE.contains(t)) {
            if (!key.equals("status") || "START".equals(alt)) return;      // Start eines Plugins ist keine Nachricht
            text = d.getName() + ": Status " + neuWert;
        } else if (t == DeviceType.SCENE) {
            if (!key.equals("zuletzt")) return;
            text = "Szene „" + d.getName() + "“ ausgeführt – " + d.getState("ergebnis");
        } else if (t == DeviceType.AUTOMATION) {
            if (key.equals("zuletzt")) text = "Regel „" + d.getName() + "“ ausgeführt – " + d.getState("ergebnis");
            else if (key.equals("aktiv")) text = "Regel „" + d.getName() + "“ " + (Boolean.TRUE.equals(neuWert) ? "aktiviert" : "pausiert");
            else return;
        } else if (t == DeviceType.SHUTTER) {
            if (key.equals("position")) return;                       // zu viele Zwischenschritte
            if (!key.equals("bewegung")) text = name(d) + ": " + key + " " + wert(neuWert);
            else if ("steht".equals(neuWert)) text = name(d) + " steht bei " + d.getState("position") + " %";
            else text = name(d) + " fährt " + ("faehrt_auf".equals(neuWert) ? "hoch" : "runter");
        } else if (key.equals("power") && neuWert instanceof Boolean an) {
            text = name(d) + (an ? " eingeschaltet" : " ausgeschaltet");
        } else if (key.equals("online") && neuWert instanceof Boolean on) {
            if (on && warOnline.add(d.getId())) return;                     // erstes Melden nach dem Start
            if (!on && !warOnline.contains(d.getId())) return;
            text = name(d) + (on ? " ist wieder erreichbar" : " ist nicht erreichbar");
        } else if (neuWert instanceof Number && quelle == null) {
            return;                                                   // Messwert ohne Auslöser
        } else {
            text = name(d) + ": " + beschriftung(key) + " " + wert(neuWert);
        }
        hinzufuegen("aenderung", d, text, quelle);
    }

    void fehlgeschlagen(Device d, String key, String wert, String quelle) {
        hinzufuegen("fehler", d, name(d) + ": „" + beschriftung(key) + " " + wert + "“ wurde nicht ausgeführt", quelle);
    }

    void system(String text) {
        hinzufuegen("system", null, text, null);
    }

    private void hinzufuegen(String art, Device d, String text, String quelle) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("zeit", LocalDateTime.now().format(ZEIT));
        e.put("art", art);
        if (d != null) {
            e.put("id", d.getId());
            e.put("typ", d.getType());
            if (d.getRoom() != null) e.put("raum", d.getRoom());
        }
        e.put("text", text);
        if (quelle != null) e.put("quelle", quelle);
        synchronized (this) {
            eintraege.addFirst(e);
            while (eintraege.size() > MAX) eintraege.removeLast();
        }
        neu.accept(e);
    }

    private static String name(Device d) {
        return d.getName() + (d.getRoom() == null || d.getName().contains(d.getRoom()) ? "" : " (" + d.getRoom() + ")");
    }

    private static String beschriftung(String key) {
        return switch (key) {
            case "helligkeit" -> "Helligkeit";
            case "solltemp" -> "Solltemperatur";
            case "modus" -> "Modus";
            case "luefter" -> "Lüfter";
            case "position" -> "Position";
            default -> key.replace('_', ' ');
        };
    }

    private static String wert(Object v) {
        if (v instanceof Boolean b) return b ? "an" : "aus";
        return String.valueOf(v);
    }
}

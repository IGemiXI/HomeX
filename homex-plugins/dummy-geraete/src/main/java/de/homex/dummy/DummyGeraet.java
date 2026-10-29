package de.homex.dummy;

import de.homex.api.device.ControlSource;
import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Gemeinsame Basis aller Test-Geräte: simuliert Antwortzeit und (auf Wunsch) Fehler
 * und meldet jeden Schaltvorgang in der Konsole.
 */
abstract class DummyGeraet extends Device {

    /** Einstellungen, die zur Laufzeit geändert werden können ("dummy fehler 20"). */
    static final class Verhalten {
        volatile int verzoegerungMs;
        volatile int fehlerquote;
        volatile int fahrzeitS;
    }

    protected final Verhalten verhalten;
    protected final Logger log;

    DummyGeraet(String id, String name, DeviceType type, String raum, Verhalten verhalten, Logger log) {
        super(id, name, type, raum);
        this.verhalten = verhalten;
        this.log = log;
    }

    /** Für die Konsolenliste, z. B. "Licht" oder "Rollladen". */
    abstract String typName();

    /** Stellt einen gespeicherten Zustand nach einem Neustart wieder her (ohne Konsolenausgabe). */
    abstract void wiederherstellen(Map<String, String> werte);

    /**
     * Simuliert die Kommunikation mit dem Gerät.
     * @return false, wenn ein (simulierter) Fehler aufgetreten ist – dann wurde schon geloggt
     */
    protected boolean senden(String was) {
        int ms = verhalten.verzoegerungMs;
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (verhalten.fehlerquote > 0 && ThreadLocalRandom.current().nextInt(100) < verhalten.fehlerquote) {
            log.warning(zeile("FEHLER", was + " – Gerät antwortet nicht (simulierter Fehler)"));
            return false;
        }
        return true;
    }

    protected void ok(String was) {
        log.info(zeile("OK    ", was));
    }

    protected void abgelehnt(String key, String value, String grund) {
        log.warning(zeile("FEHLER", "'" + key + " " + value + "' – " + grund));
    }

    /** "OK     Deckenlicht (Wohnzimmer): an   ← Dashboard" */
    private String zeile(String ergebnis, String was) {
        String quelle = ControlSource.current();
        return String.format("%s %s%s: %s%s", ergebnis, getName(),
                getRoom() == null ? "" : " (" + getRoom() + ")", was,
                quelle == null ? "" : "   ← " + quelle);
    }

    // ------------------------------------------------------------------ Werte lesen

    static Boolean anAus(String v) {
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "an", "ein", "on", "true", "1" -> Boolean.TRUE;
            case "aus", "off", "false", "0" -> Boolean.FALSE;
            case "um", "toggle", "umschalten" -> null;   // umschalten: Aufrufer entscheidet
            default -> throw new IllegalArgumentException("an oder aus erwartet");
        };
    }

    static int prozent(String v) {
        double d = Double.parseDouble(v.trim().replace(',', '.').replace("%", ""));
        if (d < 0 || d > 100) throw new IllegalArgumentException("0 bis 100 erwartet");
        return (int) Math.round(d);
    }
}

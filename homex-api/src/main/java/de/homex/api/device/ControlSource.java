package de.homex.api.device;

import java.util.function.Supplier;

/**
 * Merkt sich, <b>wer</b> gerade etwas an einem Gerät ändert – z. B. "Dashboard", "Konsole",
 * "Szene Gute Nacht" oder "Regel Morgenlicht". Jede Zustandsänderung bekommt diesen Auslöser mit
 * ({@link de.homex.api.event.DeviceStateChangeEvent#getSource()}), damit Log und Aktivitätsprotokoll
 * zeigen können, woher eine Änderung kam.
 *
 * <pre>
 * ControlSource.run("Szene Gute Nacht", () -> lampe.control("power", "aus"));
 * </pre>
 *
 * Ändert ein Gerät seinen Zustand von selbst (Sensor, Schalter an der Wand), gibt es keinen Auslöser
 * ({@code null}).
 */
public final class ControlSource {

    private static final ThreadLocal<String> AKTUELL = new ThreadLocal<>();

    private ControlSource() {}

    /** Der aktuelle Auslöser in diesem Thread, oder {@code null}. */
    public static String current() { return AKTUELL.get(); }

    public static void run(String source, Runnable aktion) {
        call(source, () -> { aktion.run(); return null; });
    }

    public static <T> T call(String source, Supplier<T> aktion) {
        String vorher = AKTUELL.get();
        AKTUELL.set(source);
        try {
            return aktion.get();
        } finally {
            if (vorher == null) AKTUELL.remove(); else AKTUELL.set(vorher);
        }
    }

    /**
     * Nimmt den aktuellen Auslöser in eine spätere/parallele Aufgabe mit, z. B. wenn ein Rollladen
     * schrittweise im Hintergrund fährt.
     */
    public static Runnable wrap(Runnable aktion) {
        String source = AKTUELL.get();
        return source == null ? aktion : () -> run(source, aktion);
    }
}

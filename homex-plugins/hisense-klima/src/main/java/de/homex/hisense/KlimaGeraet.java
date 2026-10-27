package de.homex.hisense;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Eine Hisense-Klimaanlage in HomeX.
 *
 * Steuerbar ({@code device <id> <schlüssel> <wert>}):
 * <pre>
 *   power    an | aus
 *   modus    kuehlen | heizen | entfeuchten | lueften | auto
 *   solltemp 16 … 30
 *   luefter  auto | sehr_leise | leise | mittel | hoch | sehr_hoch
 *   eco      an | aus
 *   turbo    an | aus
 *   leise    an | aus
 * </pre>
 * Nur lesbar: ist_temp, online, puid
 */
public final class KlimaGeraet extends Device {

    // ConnectLife-Codes  ↔  HomeX-Namen
    static final Map<String, String> MODI = Map.of(
            "0", "lueften", "1", "heizen", "2", "kuehlen", "3", "entfeuchten", "4", "auto");
    static final Map<String, String> LUEFTER = Map.of(
            "0", "auto", "5", "sehr_leise", "6", "leise", "7", "mittel", "8", "hoch", "9", "sehr_hoch");

    static final List<String> STEUERBAR = List.of("power", "modus", "solltemp", "luefter", "eco", "turbo", "leise");

    private final String puid;
    private final ConnectLifeBridge bridge;
    private final int tempMin, tempMax;
    private final Logger log;

    KlimaGeraet(String id, String name, String puid, ConnectLifeBridge bridge, int tempMin, int tempMax, Logger log) {
        super(id, name, DeviceType.CLIMATE);
        this.puid = puid;
        this.bridge = bridge;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.log = log;
        updateState("puid", puid);
        updateState("online", false);
    }

    String puid() { return puid; }

    // ------------------------------------------------------------------
    // Zustand von der Bridge übernehmen
    // ------------------------------------------------------------------

    void uebernehmen(ConnectLifeBridge.GeraetStatus s) {
        Map<String, String> w = s.werte();
        updateState("online", s.online());
        if (w.containsKey("t_power"))     updateState("power", "1".equals(w.get("t_power")));
        if (w.containsKey("t_work_mode")) updateState("modus", MODI.getOrDefault(w.get("t_work_mode"), "code_" + w.get("t_work_mode")));
        zahl("solltemp", w.get("t_temp"));
        zahl("ist_temp", w.get("f_temp_in"));
        if (w.containsKey("t_fan_speed")) updateState("luefter", LUEFTER.getOrDefault(w.get("t_fan_speed"), "code_" + w.get("t_fan_speed")));
        if (w.containsKey("t_eco"))       updateState("eco", "1".equals(w.get("t_eco")));
        if (w.containsKey("t_super"))     updateState("turbo", "1".equals(w.get("t_super")));
        if (w.containsKey("t_fan_mute"))  updateState("leise", "1".equals(w.get("t_fan_mute")));
    }

    void offline() { updateState("online", false); }

    private void zahl(String key, String wert) {
        if (wert == null) return;
        try {
            double d = Double.parseDouble(wert);
            // 128 o. ä. = "kein Sensor" bei manchen Geräten
            if (d > -50 && d < 100) updateState(key, d == Math.rint(d) ? (Object) (long) d : d);
        } catch (NumberFormatException ignored) {}
    }

    // ------------------------------------------------------------------
    // Steuern
    // ------------------------------------------------------------------

    @Override
    public boolean control(String key, String value) {
        key = key.toLowerCase(Locale.ROOT);
        if (key.equals("temp") || key.equals("temperatur")) key = "solltemp";
        if (!STEUERBAR.contains(key)) return false;

        Object neu = parsen(key, value);
        if (neu == null) return false;

        Object alt = getState(key);
        // Erst das Event auslösen: andere Plugins dürfen abbrechen oder den Wert anpassen
        if (!updateState(key, neu)) return false;
        Object endgueltig = getState(key);

        try {
            bridge.setzen(puid, befehl(key, endgueltig));
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warning(getName() + ": Befehl " + key + "=" + endgueltig + " fehlgeschlagen – " + HisenseKlimaPlugin.grund(e));
            if (alt != null) updateState(key, alt);   // Anzeige zurücksetzen
            return false;
        }
    }

    /** Text aus der Konsole/UI → HomeX-Wert, oder null wenn ungültig. */
    private Object parsen(String key, String v) {
        String s = v.trim().toLowerCase(Locale.ROOT).replace('ü', 'u').replace("ue", "u");
        return switch (key) {
            case "power", "eco", "turbo", "leise" -> switch (s) {
                case "an", "on", "true", "1", "ein" -> Boolean.TRUE;
                case "aus", "off", "false", "0" -> Boolean.FALSE;
                default -> null;
            };
            case "modus" -> MODI.values().stream()
                    .filter(m -> m.replace("ue", "u").equals(s) || (s.length() >= 3 && m.replace("ue", "u").startsWith(s)))
                    .findFirst().orElse(switch (s) {   // englische Namen auch erlauben
                        case "cool" -> "kuehlen";
                        case "heat" -> "heizen";
                        case "dry" -> "entfeuchten";
                        case "fan" -> "lueften";
                        default -> null;
                    });
            case "luefter" -> LUEFTER.values().stream().filter(l -> l.equals(s.replace(' ', '_'))).findFirst().orElse(null);
            case "solltemp" -> {
                try {
                    long t = Math.round(Double.parseDouble(v.replace(',', '.')));
                    yield t < tempMin || t > tempMax ? null : (Object) t;
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    /** HomeX-Wert → ConnectLife-Eigenschaft(en). */
    private static Map<String, Object> befehl(String key, Object wert) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (key) {
            case "power" -> m.put("t_power", Boolean.TRUE.equals(wert) ? 1 : 0);
            case "eco" -> m.put("t_eco", Boolean.TRUE.equals(wert) ? 1 : 0);
            case "turbo" -> m.put("t_super", Boolean.TRUE.equals(wert) ? 1 : 0);
            case "leise" -> m.put("t_fan_mute", Boolean.TRUE.equals(wert) ? 1 : 0);
            case "solltemp" -> m.put("t_temp", ((Number) wert).intValue());
            case "modus" -> m.put("t_work_mode", Integer.parseInt(code(MODI, (String) wert)));
            case "luefter" -> m.put("t_fan_speed", Integer.parseInt(code(LUEFTER, (String) wert)));
            default -> throw new IllegalArgumentException(key);
        }
        return m;
    }

    private static String code(Map<String, String> tabelle, String name) {
        return tabelle.entrySet().stream().filter(e -> e.getValue().equals(name))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }
}

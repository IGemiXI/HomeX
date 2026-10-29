package de.homex.web;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speichert Zahlenwerte (Leistung, Temperaturen …) für Diagramme: höchstens ein Wert pro Minute,
 * 48 Stunden lang. Wird regelmäßig in {@code verlauf.dat} gesichert, damit ein Neustart nichts löscht.
 */
final class Verlauf {

    private static final long MINUTE = 60_000;
    private static final int AUFBEWAHREN_MIN = 48 * 60;
    private static final Set<DeviceType> NIE = Set.of(DeviceType.SCENE, DeviceType.AUTOMATION);
    private static final Set<String> NIE_KEYS = Set.of("befehle", "position");

    /** "id|key" → (Minute seit 1970 → Wert) */
    private final Map<String, TreeMap<Long, Double>> reihen = new ConcurrentHashMap<>();

    void aufnehmen(Device d, String key, Object wert) {
        if (!(wert instanceof Number n) || NIE.contains(d.getType()) || NIE_KEYS.contains(key)) return;
        long minute = System.currentTimeMillis() / MINUTE;
        TreeMap<Long, Double> r = reihen.computeIfAbsent(d.getId() + "|" + key, k -> new TreeMap<>());
        synchronized (r) {
            r.put(minute, n.doubleValue());
            r.headMap(minute - AUFBEWAHREN_MIN).clear();
        }
    }

    /** @return [[Zeit in ms, Wert], …] der letzten {@code stunden} Stunden */
    List<List<Number>> punkte(String id, String key, int stunden) {
        TreeMap<Long, Double> r = reihen.get(id + "|" + key);
        List<List<Number>> out = new ArrayList<>();
        if (r == null) return out;
        long ab = System.currentTimeMillis() / MINUTE - stunden * 60L;
        synchronized (r) {
            r.tailMap(ab).forEach((m, v) -> out.add(List.of(m * MINUTE, v)));
        }
        return out;
    }

    /** Welche Werte es für ein Gerät gibt, z. B. ["pv_w", "verbrauch_w"] */
    List<String> schluessel(String id) {
        List<String> out = new ArrayList<>();
        for (String k : reihen.keySet()) if (k.startsWith(id + "|")) out.add(k.substring(id.length() + 1));
        out.sort(null);
        return out;
    }

    // ------------------------------------------------------------------ Datei

    void speichern(Path datei) throws IOException {
        Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (var e : reihen.entrySet()) {
                TreeMap<Long, Double> r = e.getValue();
                synchronized (r) {
                    for (var p : r.entrySet()) {
                        w.write(e.getKey() + "|" + p.getKey() + "|" + p.getValue());
                        w.newLine();
                    }
                }
            }
        }
        Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    void laden(Path datei) throws IOException {
        if (!Files.exists(datei)) return;
        long ab = System.currentTimeMillis() / MINUTE - AUFBEWAHREN_MIN;
        for (String z : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
            String[] t = z.split("\\|");
            if (t.length != 4) continue;
            try {
                long minute = Long.parseLong(t[2]);
                if (minute < ab) continue;
                reihen.computeIfAbsent(t[0] + "|" + t[1], k -> new TreeMap<>()).put(minute, Double.parseDouble(t[3]));
            } catch (NumberFormatException ignored) {}
        }
    }
}

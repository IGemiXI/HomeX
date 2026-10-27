package de.homex.hisense;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spricht den "ConnectLife API Connector" an (https://github.com/bilan/connectlife-api-connector).
 * Der übernimmt die Anmeldung bei der Hisense-Cloud und bietet im Heimnetz:
 * <pre>
 *   GET  /api/devices          → alle Geräte mit Zustand
 *   POST /api/devices/{puid}   → Eigenschaften setzen, z. B. {"t_temp":22}
 * </pre>
 * Das Antwortformat wird tolerant gelesen, damit kleine Änderungen am Connector nichts kaputt machen.
 */
final class ConnectLifeBridge {

    /** Zustand eines Geräts, wie die Bridge ihn liefert. Werte immer als Text ("1", "22" ...). */
    record GeraetStatus(String puid, String name, boolean online, Map<String, String> werte) {}

    /** Geräte-Typcodes von ConnectLife, die Klimaanlagen sind (009 = Split, 006/008 = weitere Klimatypen). */
    private static final Set<String> KLIMA_TYPEN = Set.of("006", "008", "009");

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;

    ConnectLifeBridge(String baseUrl, int timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    List<GeraetStatus> geraete() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/devices"))
                .timeout(timeout).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new IOException("Bridge antwortet HTTP " + res.statusCode() + ": " + kurz(res.body()));
        return auswerten(Json.parse(res.body()));
    }

    void setzen(String puid, Map<String, Object> werte) throws IOException, InterruptedException {
        String json = werte.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + (e.getValue() instanceof Number ? e.getValue() : "\"" + e.getValue() + "\""))
                .collect(Collectors.joining(",", "{", "}"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/devices/"
                        + URLEncoder.encode(puid, StandardCharsets.UTF_8)))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new IOException("Befehl abgelehnt (HTTP " + res.statusCode() + "): " + kurz(res.body()));
    }

    // ------------------------------------------------------------------
    // Antwort lesen
    // ------------------------------------------------------------------

    static List<GeraetStatus> auswerten(Object json) {
        Object liste = json;
        if (json instanceof Map<?, ?>) {
            Map<String, Object> m = Json.obj(json);
            for (String k : List.of("data", "devices", "result", "deviceList")) {
                if (m.get(k) instanceof List<?>) { liste = m.get(k); break; }
            }
        }
        List<GeraetStatus> out = new ArrayList<>();
        if (!(liste instanceof List<?> l)) return out;

        for (Object o : l) {
            Map<String, Object> d = Json.obj(o);
            String typ = text(erstes(d, "deviceTypeCode", "typeCode"));
            if (typ != null && !KLIMA_TYPEN.contains(typ)) continue;   // z. B. Kühlschrank, Waschmaschine

            String puid = text(erstes(d, "puid", "puId", "deviceId", "id"));
            if (puid == null) continue;
            String name = text(erstes(d, "deviceNickName", "nickName", "name", "deviceName"));
            Object online = erstes(d, "online", "isOnline");
            boolean istOnline = !(Boolean.FALSE.equals(online) || "0".equals(text(online)) || "false".equals(text(online)));

            Map<String, String> werte = new LinkedHashMap<>();
            Object status = erstes(d, "statusList", "status", "properties", "deviceStatus");
            Map<String, Object> quelle = status instanceof Map<?, ?> ? Json.obj(status) : d;
            quelle.forEach((k, v) -> {
                if ((k.startsWith("t_") || k.startsWith("f_")) && v != null && !(v instanceof Map) && !(v instanceof List))
                    werte.put(k, text(v));
            });
            out.add(new GeraetStatus(puid, name == null ? puid : name, istOnline, werte));
        }
        return out;
    }

    private static Object erstes(Map<String, Object> m, String... keys) {
        for (String k : keys) if (m.get(k) != null) return m.get(k);
        return null;
    }

    /** 22.0 → "22", "22" → "22" */
    private static String text(Object o) {
        if (o == null) return null;
        if (o instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(o);
    }

    private static String kurz(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s; }
}

package de.homex.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import de.homex.api.HomeX;
import de.homex.api.device.ControlSource;
import de.homex.api.device.Device;
import de.homex.api.plugin.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST-API unter /api:
 * <pre>
 *   GET  /api/info                 HomeX-Version, Plugins
 *   GET  /api/devices              alle Geräte mit Zustand
 *   GET  /api/devices/{id}         ein Gerät
 *   POST /api/devices/{id}         steuern: {"key":"solltemp","value":22}  oder  {"power":true,"modus":"kuehlen"}
 *   GET  /api/events               Live-Updates (Server-Sent Events)
 * </pre>
 * Jede Anfrage braucht den Token: Header {@code Authorization: Bearer <token>} oder {@code ?token=<token>}.
 */
final class ApiHandler implements HttpHandler {

    private static final int MAX_BODY = 64 * 1024;

    private final HomeX homeX;
    private final byte[] token;
    private final Set<String> corsErlaubt;
    private final EventStream events;
    private final Aktivitaet aktivitaet;
    private final Verlauf verlauf;
    private final Logger log;
    private final long gestartet = System.currentTimeMillis();

    ApiHandler(HomeX homeX, String token, Set<String> corsErlaubt, EventStream events,
               Aktivitaet aktivitaet, Verlauf verlauf, Logger log) {
        this.homeX = homeX;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.corsErlaubt = corsErlaubt;
        this.events = events;
        this.aktivitaet = aktivitaet;
        this.verlauf = verlauf;
        this.log = log;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            cors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) { leer(ex, 204); return; }
            if (!angemeldet(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                fehler(ex, 401, "Token fehlt oder ist falsch");
                return;
            }
            route(ex);
        } catch (Exception e) {
            log.log(Level.WARNING, "Fehler bei " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), e);
            try { fehler(ex, 500, "Interner Fehler: " + e.getMessage()); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        if (path.equals("/api/info") && method.equals("GET")) { json(ex, 200, info()); return; }
        if (path.equals("/api/devices") && method.equals("GET")) { json(ex, 200, geraete()); return; }
        if (path.equals("/api/events") && method.equals("GET")) { events.bedienen(ex, this::geraete); return; }
        if (path.equals("/api/activity") && method.equals("GET")) {
            json(ex, 200, aktivitaet.liste(zahl(query(ex.getRequestURI()).get("limit"), 100, 1, 400)));
            return;
        }
        if (path.equals("/api/history") && method.equals("GET")) { verlauf(ex); return; }

        if (path.startsWith("/api/devices/")) {
            String id = URLDecoder.decode(path.substring("/api/devices/".length()), StandardCharsets.UTF_8);
            Device d = homeX.getDeviceRegistry().get(id).orElse(null);
            if (d == null) { fehler(ex, 404, "Gerät nicht gefunden: " + id); return; }
            switch (method) {
                case "GET" -> json(ex, 200, geraet(d));
                case "POST", "PUT", "PATCH" -> steuern(ex, d);
                default -> fehler(ex, 405, "Methode nicht erlaubt");
            }
            return;
        }
        fehler(ex, 404, "Unbekannter Pfad: " + path);
    }

    // ------------------------------------------------------------------
    // Endpunkte
    // ------------------------------------------------------------------

    private Map<String, Object> info() {
        List<Object> plugins = new ArrayList<>();
        for (JavaPlugin p : homeX.getPluginManager().getPlugins()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("version", p.getDescription().version());
            m.put("enabled", p.isEnabled());
            plugins.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", homeX.getName());
        m.put("version", homeX.getVersion());
        m.put("haus", homeX.getSetting("haus.name", "Mein Zuhause"));
        m.put("standort", homeX.getLocation().isPresent());
        m.put("plugins", plugins);
        m.put("live_clients", events.anzahl());
        m.put("geraete", homeX.getDeviceRegistry().getAll().size());
        m.put("laeuft_seit_s", (System.currentTimeMillis() - gestartet) / 1000);
        return m;
    }

    private Object geraete() {
        return homeX.getDeviceRegistry().getAll().stream().map(ApiHandler::geraet).toList();
    }

    static Map<String, Object> geraet(Device d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("type", d.getType());
        if (d.getRoom() != null) m.put("room", d.getRoom());
        m.put("states", d.getStates());
        if (d.getLastChanged() != null) {
            m.put("changed", d.getLastChanged().toEpochMilli());
            if (d.getLastSource() != null) m.put("source", d.getLastSource());
        }
        return m;
    }

    private void steuern(HttpExchange ex, Device d) throws IOException {
        Object body;
        try {
            body = Json.parse(lesen(ex));
        } catch (IllegalArgumentException | IOException e) {
            fehler(ex, 400, "Ungültiges JSON: " + e.getMessage());
            return;
        }
        Map<String, Object> m = Json.obj(body);
        Map<String, Object> befehle = new LinkedHashMap<>();
        if (m.containsKey("key") && m.containsKey("value")) befehle.put(String.valueOf(m.get("key")), m.get("value"));
        else befehle.putAll(m);
        if (befehle.isEmpty()) { fehler(ex, 400, "Erwartet {\"key\":...,\"value\":...} oder {\"schlüssel\":wert}"); return; }

        // Wer steuert? Das Dashboard schickt "X-HomeX-Client: Dashboard", andere Programme können sich selbst benennen
        String client = ex.getRequestHeaders().getFirst("X-HomeX-Client");
        String quelle = client == null || client.isBlank() ? "Web-API" : client.trim();
        if (quelle.length() > 40) quelle = quelle.substring(0, 40);
        String wer = quelle + " (" + ex.getRemoteAddress().getAddress().getHostAddress() + ")";

        List<String> abgelehnt = new ArrayList<>();
        for (var e : befehle.entrySet()) {
            String wert = text(e.getValue());
            boolean ok;
            try {
                ok = ControlSource.call(quelle, () -> d.control(e.getKey(), wert));
            } catch (RuntimeException ex2) {
                log.log(Level.WARNING, "Fehler beim Steuern von " + d.getId(), ex2);
                ok = false;
            }
            if (ok) {
                log.info(wer + ": " + d.getId() + " " + e.getKey() + " " + wert + " → OK");
            } else {
                abgelehnt.add(e.getKey() + "=" + wert);
                log.warning(wer + ": " + d.getId() + " " + e.getKey() + " " + wert + " → NICHT ausgeführt");
                aktivitaet.fehlgeschlagen(d, e.getKey(), wert, quelle);
            }
        }
        Map<String, Object> antwort = new LinkedHashMap<>();
        antwort.put("ok", abgelehnt.isEmpty());
        if (!abgelehnt.isEmpty()) antwort.put("abgelehnt", abgelehnt);
        antwort.put("device", geraet(d));
        json(ex, abgelehnt.isEmpty() ? 200 : 409, antwort);
    }

    /** GET /api/history?id=solar&key=pv_w&stunden=24 – ohne key: welche Werte es gibt */
    private void verlauf(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        String id = q.get("id");
        if (id == null) { fehler(ex, 400, "Parameter id fehlt"); return; }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        String key = q.get("key");
        if (key == null) {
            m.put("werte", verlauf.schluessel(id));
        } else {
            m.put("key", key);
            m.put("punkte", verlauf.punkte(id, key, zahl(q.get("stunden"), 24, 1, 48)));
        }
        json(ex, 200, m);
    }

    private static int zahl(String s, int standard, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(s))); } catch (Exception e) { return standard; }
    }

    /** 22.0 → "22", true → "true" */
    private static String text(Object o) {
        if (o instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(o);
    }

    // ------------------------------------------------------------------
    // Sicherheit
    // ------------------------------------------------------------------

    private boolean angemeldet(HttpExchange ex) {
        String t = null;
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) t = auth.substring(7).trim();
        if (t == null) t = query(ex.getRequestURI()).get("token");   // EventSource kann keine Header senden
        // Vergleich in konstanter Zeit, damit der Token nicht per Zeitmessung erraten werden kann
        return t != null && MessageDigest.isEqual(token, t.getBytes(StandardCharsets.UTF_8));
    }

    private void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        if (corsErlaubt.contains("*") || corsErlaubt.contains(origin)) {
            var h = ex.getResponseHeaders();
            h.set("Access-Control-Allow-Origin", corsErlaubt.contains("*") ? "*" : origin);
            h.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, OPTIONS");
            h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-HomeX-Client");
            h.set("Access-Control-Max-Age", "600");
            if (!corsErlaubt.contains("*")) h.add("Vary", "Origin");
        }
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private static String lesen(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new IOException("Anfrage zu groß");
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void json(HttpExchange ex, int status, Object daten) throws IOException {
        byte[] b = JsonOut.of(daten).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(b); }
    }

    private static void fehler(HttpExchange ex, int status, String meldung) throws IOException {
        json(ex, status, Map.of("ok", false, "fehler", meldung));
    }

    private static void leer(HttpExchange ex, int status) throws IOException {
        ex.sendResponseHeaders(status, -1);
    }
}

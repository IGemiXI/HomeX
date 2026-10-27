package de.homex.sma;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Liest die Anlage über die SMA Monitoring API (smaapis.de) mit OAuth2.
 *
 * Ablauf:
 *  1. Einmalig: {@link #loginUrl()} im Browser öffnen, bei SMA einloggen, zustimmen.
 *  2. SMA leitet auf die redirect-uri weiter: ...?code=XYZ  → {@link #codeEinloesen(String)}
 *  3. Ab dann wird mit dem Refresh-Token (scope offline_access) automatisch ein Access-Token geholt.
 */
final class CloudQuelle implements SmaQuelle {

    record Einstellungen(String authUrl, String tokenUrl, String apiUrl, String clientId, String clientSecret,
                         String redirectUri, String plantId, String messwertePfad) {}

    private final Einstellungen cfg;
    private final Consumer<String> refreshTokenSpeichern;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile String refreshToken;
    private volatile String accessToken;
    private volatile Instant accessTokenGueltigBis = Instant.EPOCH;
    private volatile String erwarteterState;

    CloudQuelle(Einstellungen cfg, String refreshToken, Consumer<String> refreshTokenSpeichern) {
        this.cfg = cfg;
        this.refreshToken = leer(refreshToken) ? null : refreshToken;
        this.refreshTokenSpeichern = refreshTokenSpeichern;
    }

    @Override
    public String name() { return "SMA Cloud"; }

    boolean istAngemeldet() { return refreshToken != null; }

    // ------------------------------------------------------------------
    // Login (Authorization Code Flow)
    // ------------------------------------------------------------------

    String loginUrl() {
        pruefeClient();
        erwarteterState = UUID.randomUUID().toString();
        return cfg.authUrl() + "?" + form(Map.of(
                "client_id", cfg.clientId(),
                "response_type", "code",
                "redirect_uri", cfg.redirectUri(),
                "scope", "offline_access",
                "state", erwarteterState));
    }

    /**
     * Tauscht den Code aus der Redirect-URL gegen Tokens. Akzeptiert den reinen Code
     * oder die komplette Redirect-URL (dann wird auch der state geprüft).
     */
    void codeEinloesen(String codeOderUrl) throws IOException, InterruptedException {
        pruefeClient();
        String code = codeOderUrl;
        if (codeOderUrl.contains("code=")) {
            Map<String, String> q = query(codeOderUrl);
            code = q.get("code");
            String state = q.get("state");
            if (erwarteterState != null && state != null && !state.equals(erwarteterState))
                throw new IOException("state passt nicht – bitte 'sma login' neu starten");
        }
        if (leer(code)) throw new IOException("Kein Code gefunden");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("client_id", cfg.clientId());
        body.put("client_secret", cfg.clientSecret());
        body.put("redirect_uri", cfg.redirectUri());
        tokenAnfrage(body);
    }

    // ------------------------------------------------------------------
    // Messwerte
    // ------------------------------------------------------------------

    @Override
    public SolarData lesen() throws IOException, InterruptedException {
        if (!istAngemeldet())
            throw new IOException("Nicht bei SMA angemeldet – in der Konsole 'sma login' ausführen");
        if (leer(cfg.plantId())) throw new IOException("cloud.plant-id fehlt in der config.properties");

        String url = cfg.apiUrl() + cfg.messwertePfad().replace("{plantId}", enc(cfg.plantId()));
        HttpResponse<String> res = get(url);
        if (res.statusCode() == 401) {          // Token abgelaufen/widerrufen → einmal neu holen
            accessTokenGueltigBis = Instant.EPOCH;
            res = get(url);
        }
        if (res.statusCode() == 429) throw new IOException("SMA Rate-Limit erreicht – Intervall erhöhen");
        if (res.statusCode() / 100 != 2)
            throw new IOException("SMA antwortet HTTP " + res.statusCode() + ": " + kurz(res.body()));

        return auswerten(Json.parse(res.body()));
    }

    /** Liest die Felder des Messwert-Sets. "set" kann ein Objekt oder eine Liste (Zeitreihe) sein. */
    static SolarData auswerten(Object json) {
        Object set = Json.obj(json).get("set");
        if (set instanceof List<?> list) set = list.isEmpty() ? null : list.get(list.size() - 1);
        Map<String, Object> m = Json.obj(set);
        if (m.isEmpty()) m = Json.obj(json);   // falls die Werte direkt oben stehen

        Double pv = Json.num(m.get("pvGeneration"));
        return new SolarData(
                pv, null,
                Json.num(m.get("gridFeedIn")),
                Json.num(m.get("gridConsumption")),
                Json.num(m.get("totalConsumption")),
                Json.num(m.get("batteryStateOfCharge")),
                Json.num(m.get("batteryCharging")),
                Json.num(m.get("batteryDischarging")),
                null, null, null,
                "OK",
                Json.str(m.get("time")));
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json")
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------
    // Tokens
    // ------------------------------------------------------------------

    private synchronized String accessToken() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(accessTokenGueltigBis)) return accessToken;
        pruefeClient();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", cfg.clientId());
        body.put("client_secret", cfg.clientSecret());
        tokenAnfrage(body);
        return accessToken;
    }

    private synchronized void tokenAnfrage(Map<String, String> body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.tokenUrl()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(body)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            if (res.statusCode() == 400 || res.statusCode() == 401) {
                // Refresh-Token ungültig oder Zustimmung widerrufen → neu anmelden
                if ("refresh_token".equals(body.get("grant_type"))) setRefreshToken(null);
            }
            throw new IOException("Token-Anfrage fehlgeschlagen (HTTP " + res.statusCode() + "): " + kurz(res.body()));
        }
        Map<String, Object> t = Json.obj(Json.parse(res.body()));
        String at = Json.str(t.get("access_token"));
        if (leer(at)) throw new IOException("Keine access_token in der Antwort");
        Double expires = Json.num(t.get("expires_in"));
        accessToken = at;
        // 30 s Puffer, damit das Token nicht mitten in der Anfrage abläuft
        accessTokenGueltigBis = Instant.now().plusSeconds((long) (expires == null ? 300 : expires) - 30);
        String rt = Json.str(t.get("refresh_token"));
        if (!leer(rt) && !rt.equals(refreshToken)) setRefreshToken(rt);   // SMA kann den Refresh-Token rotieren
    }

    private void setRefreshToken(String rt) {
        refreshToken = rt;
        if (rt == null) accessToken = null;
        refreshTokenSpeichern.accept(rt == null ? "" : rt);
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private void pruefeClient() {
        if (leer(cfg.clientId()) || leer(cfg.clientSecret()))
            throw new IllegalStateException("cloud.client-id und cloud.client-secret in der config.properties eintragen");
    }

    private static String form(Map<String, String> m) {
        return m.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> query(String url) {
        Map<String, String> m = new LinkedHashMap<>();
        int q = url.indexOf('?');
        String s = q >= 0 ? url.substring(q + 1) : url;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        for (String part : s.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) m.put(dec(part.substring(0, eq)), dec(part.substring(eq + 1)));
        }
        return m;
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
    private static String dec(String s) { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
    private static boolean leer(String s) { return s == null || s.isBlank(); }
    private static String kurz(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s; }
}

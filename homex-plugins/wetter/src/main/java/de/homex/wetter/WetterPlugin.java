package de.homex.wetter;

import de.homex.api.Location;
import de.homex.api.device.Device;
import de.homex.api.device.DeviceType;
import de.homex.api.plugin.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Holt Wetter, Vorhersage und Sonneneinstrahlung von Open-Meteo und stellt sie als Gerät "wetter" bereit.
 */
public class WetterPlugin extends JavaPlugin {

    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private WetterGeraet geraet;
    private int fehlerInFolge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        geraet = new WetterGeraet();
        getHomeX().getDeviceRegistry().register(geraet, this);

        Optional<Location> ort = getHomeX().getLocation();
        if (ort.isEmpty()) {
            geraet.setze("status", "WARTET");
            geraet.setze("fehler", "Standort fehlt in homex.properties (standort.breitengrad / standort.laengengrad)");
            getLogger().info("Wartet auf den Standort in homex.properties – danach HomeX neu starten.");
            return;
        }
        long min = Math.max(5, zahl("intervall-min", 15));
        getHomeX().getScheduler().runRepeating(this, () -> abrufen(ort.get()), 0, min, TimeUnit.MINUTES);
        registerCommand("wetter", "wetter – aktuelles Wetter und Vorhersage", (s, l, a) -> {
            geraet.getStates().forEach((k, v) -> { if (!(v instanceof List)) s.sendMessage(String.format("  %-22s %s", k, v)); });
            return true;
        });
    }

    private void abrufen(Location ort) {
        String url = getConfig().getProperty("api.url", "https://api.open-meteo.com/v1/forecast").trim()
                + "?latitude=" + ort.latitude() + "&longitude=" + ort.longitude()
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,"
                + "precipitation_probability_max,shortwave_radiation_sum"
                + "&hourly=shortwave_radiation&timezone=auto&forecast_days=2";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new IOException("Open-Meteo antwortet HTTP " + res.statusCode());
            uebernehmen(Json.obj(Json.parse(res.body())));
            if (fehlerInFolge > 0) getLogger().info("Wetterdaten wieder da.");
            fehlerInFolge = 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fehlerInFolge++;
            geraet.setze("status", "OFFLINE");
            geraet.setze("fehler", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (fehlerInFolge == 1 || fehlerInFolge % 12 == 0)
                getLogger().warning("Wetter nicht abrufbar (" + fehlerInFolge + "x): " + geraet.getState("fehler"));
        }
    }

    private void uebernehmen(Map<String, Object> j) {
        Map<String, Object> c = Json.obj(j.get("current"));
        if (c.isEmpty()) c = Json.obj(j.get("current_weather"));       // ältere API-Form
        Map<String, Object> d = Json.obj(j.get("daily"));

        Double code = erstes(c, "weather_code", "weathercode");
        boolean tag = !Double.valueOf(0).equals(Json.num(c.get("is_day")));
        geraet.setze("temperatur_c", runden(erstes(c, "temperature_2m", "temperature"), 1));
        geraet.setze("gefuehlt_c", runden(Json.num(c.get("apparent_temperature")), 1));
        geraet.setze("feuchte_prozent", runden(Json.num(c.get("relative_humidity_2m")), 0));
        geraet.setze("wind_kmh", runden(erstes(c, "wind_speed_10m", "windspeed", "wind_speed"), 0));
        geraet.setze("ist_tag", tag);
        if (code != null) {
            geraet.setze("wetter", WetterCodes.text(code.intValue()));
            geraet.setze("symbol", WetterCodes.symbol(code.intValue(), tag));
        }

        for (int i = 0; i < 2; i++) {
            String p = i == 0 ? "heute_" : "morgen_";
            geraet.setze(p + "min_c", runden(tagWert(d, "temperature_2m_min", i), 0));
            geraet.setze(p + "max_c", runden(tagWert(d, "temperature_2m_max", i), 0));
            geraet.setze(p + "regen_prozent", runden(tagWert(d, "precipitation_probability_max", i), 0));
            Double tc = tagWert(d, "weather_code", i);
            if (tc != null) geraet.setze(p + "symbol", WetterCodes.symbol(tc.intValue(), true));
            Double mj = tagWert(d, "shortwave_radiation_sum", i);              // MJ/m²
            if (mj != null) {
                double kwhM2 = mj / 3.6;
                geraet.setze(p + "sonnenenergie_kwh_m2", runden(kwhM2, 1));
                Double kwp = kwp();
                // Grobe Schätzung: Einstrahlung (horizontal) × kWp × 0,8 Systemverluste
                if (kwp != null) geraet.setze(p + "pv_prognose_kwh", runden(kwhM2 * kwp * 0.8, 0));
            }
        }
        geraet.setze("sonnenaufgang", uhrzeit(tagText(d, "sunrise", 0)));
        geraet.setze("sonnenuntergang", uhrzeit(tagText(d, "sunset", 0)));

        // Stündliche Einstrahlung heute (W/m²) – für das Prognose-Diagramm im Dashboard
        Map<String, Object> h = Json.obj(j.get("hourly"));
        if (h.get("shortwave_radiation") instanceof List<?> werte) {
            List<Integer> heute = new ArrayList<>();
            for (int i = 0; i < Math.min(24, werte.size()); i++) {
                Double w = Json.num(werte.get(i));
                heute.add(w == null ? 0 : (int) Math.round(w));
            }
            geraet.setze("strahlung_heute", List.copyOf(heute));
        }

        geraet.setze("aktualisiert", LocalTime.now().format(UHR));
        geraet.setze("status", "OK");
        geraet.entfernen("fehler");
    }

    // ------------------------------------------------------------------ Hilfen

    private Double kwp() {
        String s = getConfig().getProperty("anlage.kwp", "").trim().replace(',', '.');
        try { return s.isEmpty() ? null : Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static Double erstes(Map<String, Object> m, String... keys) {
        for (String k : keys) { Double d = Json.num(m.get(k)); if (d != null) return d; }
        return null;
    }

    private static Double tagWert(Map<String, Object> d, String key, int i) {
        return d.get(key) instanceof List<?> l && l.size() > i ? Json.num(l.get(i)) : null;
    }

    private static String tagText(Map<String, Object> d, String key, int i) {
        return d.get(key) instanceof List<?> l && l.size() > i && l.get(i) != null ? String.valueOf(l.get(i)) : null;
    }

    /** "2026-10-29T07:12" → "07:12" */
    private static String uhrzeit(String iso) {
        if (iso == null) return null;
        int t = iso.indexOf('T');
        return t >= 0 && iso.length() >= t + 6 ? iso.substring(t + 1, t + 6) : iso;
    }

    private static Number runden(Double v, int stellen) {
        if (v == null) return null;
        if (stellen == 0) return Math.round(v);
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }

    private long zahl(String key, long standard) {
        try { return Long.parseLong(getConfig().getProperty(key, "" + standard).trim()); } catch (NumberFormatException e) { return standard; }
    }

    /** Das Wetter als Gerät – nur lesbar. */
    static final class WetterGeraet extends Device {
        WetterGeraet() {
            super("wetter", "Wetter", DeviceType.WEATHER);
            updateState("status", "START");
        }
        void setze(String key, Object wert) { if (wert != null) updateState(key, wert); }
        void entfernen(String key) { removeState(key); }
        @Override public boolean control(String key, String value) { return false; }
    }

}

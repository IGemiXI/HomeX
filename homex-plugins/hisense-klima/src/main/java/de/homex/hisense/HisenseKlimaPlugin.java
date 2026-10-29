package de.homex.hisense;

import de.homex.api.command.CommandSender;
import de.homex.api.plugin.JavaPlugin;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** HomeX-Plugin für Hisense-Klimaanlagen (ConnectLife) über den ConnectLife API Connector. */
public class HisenseKlimaPlugin extends JavaPlugin {

    private ConnectLifeBridge bridge;
    /** puid → Gerät */
    private final Map<String, KlimaGeraet> klimas = new ConcurrentHashMap<>();
    private int tempMin, tempMax;
    private int fehlerInFolge = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Properties c = getConfig();
        bridge = new ConnectLifeBridge(c.getProperty("bridge.url", "http://localhost:8000").trim(),
                (int) zahl(c, "timeout-ms", 10000));
        tempMin = (int) zahl(c, "temp.min", 16);
        tempMax = (int) zahl(c, "temp.max", 30);
        long intervall = Math.max(10, zahl(c, "intervall", 30));

        registerCommand("klima", "klima [liste | <id|alle> an|aus | <id> temp <°C> | <id> modus <m> | "
                + "<id> luefter <stufe> | <id> eco|turbo|leise an|aus]", this::befehl);

        getLogger().info("Bridge: " + c.getProperty("bridge.url") + ", Abfrage alle " + intervall + " s");
        getHomeX().getScheduler().runRepeating(this, this::abfragen, 0, intervall, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        klimas.clear();   // Geräte selbst entfernt HomeX automatisch
    }

    // ------------------------------------------------------------------
    // Abfrage & automatische Erkennung
    // ------------------------------------------------------------------

    private synchronized void abfragen() {
        try {
            List<ConnectLifeBridge.GeraetStatus> liste = bridge.geraete();
            Set<String> gesehen = new HashSet<>();
            for (var s : liste) {
                gesehen.add(s.puid());
                KlimaGeraet k = klimas.computeIfAbsent(s.puid(), p -> anlegen(s));
                if (k != null) k.uebernehmen(s);
            }
            // Geräte, die nicht mehr in der Liste sind → offline
            klimas.forEach((puid, k) -> { if (!gesehen.contains(puid)) k.offline(); });

            if (fehlerInFolge > 0) getLogger().info("Bridge wieder erreichbar.");
            fehlerInFolge = 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fehlerInFolge++;
            klimas.values().forEach(KlimaGeraet::offline);
            if (fehlerInFolge == 1 || fehlerInFolge % 20 == 0)
                getLogger().warning("ConnectLife-Bridge nicht erreichbar (" + fehlerInFolge + "x): " + grund(e));
        }
    }

    private KlimaGeraet anlegen(ConnectLifeBridge.GeraetStatus s) {
        Properties c = getConfig();
        String name = c.getProperty("name." + s.puid(), "Klima " + s.name()).trim();
        String id = c.getProperty("id." + s.puid(), "").trim();
        if (id.isEmpty()) id = freieId("klima-" + slug(s.name()));
        try {
            KlimaGeraet k = new KlimaGeraet(id, name, s.puid(), bridge, tempMin, tempMax, getLogger());
            // Raum: aus der Config (raum.<puid>) oder der Name aus der ConnectLife-App
            k.setRoom(c.getProperty("raum." + s.puid(), s.name()).trim());
            getHomeX().getDeviceRegistry().register(k, this);
            getLogger().info("Klima gefunden: " + name + " → " + id + " (puid " + s.puid() + ")");
            return k;
        } catch (IllegalArgumentException e) {
            getLogger().severe("Kann " + name + " nicht anlegen: " + e.getMessage());
            return null;
        }
    }

    private String freieId(String basis) {
        String id = basis;
        for (int i = 2; getHomeX().getDeviceRegistry().get(id).isPresent(); i++) id = basis + "-" + i;
        return id;
    }

    /** "Schlafzimmer Süd" → "schlafzimmer-sued" */
    static String slug(String s) {
        String t = s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return t.isEmpty() ? "geraet" : t;
    }

    // ------------------------------------------------------------------
    // Befehl "klima"
    // ------------------------------------------------------------------

    private boolean befehl(CommandSender s, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("liste")) {
            if (klimas.isEmpty()) {
                s.sendMessage("Noch keine Klimas gefunden. Läuft die Bridge? (bridge.url in der Config)");
                return true;
            }
            for (KlimaGeraet k : sortiert()) {
                var st = k.getStates();
                s.sendMessage(String.format("  %-22s %-24s %s  %s  soll %s°C  ist %s°C  Lüfter %s%s",
                        k.getId(), k.getName(),
                        Boolean.TRUE.equals(st.get("online")) ? "online " : "OFFLINE",
                        Boolean.TRUE.equals(st.get("power")) ? "AN  " : "aus ",
                        st.getOrDefault("solltemp", "?"), st.getOrDefault("ist_temp", "?"),
                        st.getOrDefault("luefter", "?"),
                        Boolean.TRUE.equals(st.get("power")) ? "  [" + st.getOrDefault("modus", "?") + "]" : ""));
                s.sendMessage("      puid " + k.puid());
            }
            return true;
        }

        if (a.length < 2) return false;
        List<KlimaGeraet> ziele = new ArrayList<>();
        if (a[0].equalsIgnoreCase("alle")) ziele.addAll(sortiert());
        else {
            KlimaGeraet k = finden(a[0]);
            if (k == null) { s.sendMessage("Klima nicht gefunden: " + a[0] + " (siehe 'klima liste')"); return true; }
            ziele.add(k);
        }

        String key, wert;
        if (a.length == 2 && (a[1].equalsIgnoreCase("an") || a[1].equalsIgnoreCase("aus"))) {
            key = "power"; wert = a[1];
        } else if (a.length == 3) {
            key = a[1]; wert = a[2];
        } else return false;

        // Cloud-Aufrufe nicht in der Konsole blockieren
        getHomeX().getScheduler().runAsync(this, () -> {
            for (KlimaGeraet k : ziele) {
                boolean ok = k.control(key, wert);
                s.sendMessage(k.getName() + ": " + (ok ? key + " = " + k.getState(normal(key)) + " ✔"
                        : "'" + key + " " + wert + "' nicht möglich (ungültig, blockiert oder Gerät nicht erreichbar)"));
            }
        });
        return true;
    }

    private static String normal(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("temp") || k.equals("temperatur") ? "solltemp" : k;
    }

    private KlimaGeraet finden(String eingabe) {
        String e = eingabe.toLowerCase(Locale.ROOT);
        for (KlimaGeraet k : klimas.values()) {
            if (k.getId().equals(e) || k.getId().equals("klima-" + e) || k.puid().equalsIgnoreCase(e)) return k;
        }
        return null;
    }

    private List<KlimaGeraet> sortiert() {
        return klimas.values().stream().sorted((x, y) -> x.getId().compareTo(y.getId())).toList();
    }

    private static long zahl(Properties c, String key, long standard) {
        try {
            return Long.parseLong(c.getProperty(key, String.valueOf(standard)).trim());
        } catch (NumberFormatException e) {
            return standard;
        }
    }


    /** Verständliche Fehlermeldung (manche Java-Exceptions haben keinen Text). */
    static String grund(Throwable e) {
        if (e instanceof java.net.ConnectException) return "keine Verbindung (Adresse/Port prüfen, läuft das Gerät?)";
        if (e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.SocketTimeoutException)
            return "Zeitüberschreitung";
        if (e instanceof java.net.UnknownHostException) return "Adresse unbekannt: " + e.getMessage();
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}

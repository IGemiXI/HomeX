package de.homex.sma;

import de.homex.api.command.CommandSender;
import de.homex.api.plugin.JavaPlugin;
import de.homex.api.scheduler.Task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** HomeX-Plugin für SMA-Wechselrichter (lokal per Modbus oder über die SMA Cloud). */
public class SmaSolarPlugin extends JavaPlugin {

    private SolarGeraet geraet;
    private volatile SmaQuelle quelle;
    private Task abfrage;
    private int fehlerInFolge = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Properties c = getConfig();

        geraet = new SolarGeraet(c.getProperty("geraet.id", "solar"), c.getProperty("geraet.name", "Solaranlage"));
        getHomeX().getDeviceRegistry().register(geraet, this);

        registerCommand("sma", "sma [status|jetzt|quelle <modbus|cloud>|login|code <code>] – Solaranlage",
                this::befehl);

        quelleStarten();
    }

    @Override
    public void onDisable() {
        quelleStoppen();
    }

    // ------------------------------------------------------------------
    // Quelle & Abfrage
    // ------------------------------------------------------------------

    private synchronized void quelleStarten() {
        quelleStoppen();
        Properties c = getConfig();
        String art = c.getProperty("quelle", "modbus").trim().toLowerCase();
        long intervall;

        if (art.equals("cloud")) {
            CloudQuelle cq = cloudQuelle(c);
            quelle = cq;
            String fehlt = cq.fehlendeEinrichtung();
            if (fehlt != null) {
                // Nichts abfragen, solange der Zugang fehlt – HomeX läuft trotzdem ganz normal weiter
                geraet.wartet(fehlt);
                getLogger().info("Quelle: SMA Cloud – " + fehlt + ". Die Solaranlage wird abgefragt, sobald das erledigt ist.");
                return;
            }
            intervall = zahl(c, "intervall.cloud", 300);
            if (intervall < 60) {
                getLogger().warning("intervall.cloud ist sehr kurz – SMA hat ein Rate-Limit. Setze auf 60 s.");
                intervall = 60;
            }
        } else {
            if (!art.equals("modbus")) getLogger().warning("Unbekannte quelle '" + art + "' – nehme modbus");
            List<String> netze = Arrays.stream(c.getProperty("modbus.suchnetz", "").split(","))
                    .map(String::trim).filter(n -> n.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")).toList();
            quelle = new ModbusQuelle(
                    c.getProperty("modbus.host", "auto").trim(),
                    (int) zahl(c, "modbus.port", 502),
                    (int) zahl(c, "modbus.unit-id", 3),
                    (int) zahl(c, "modbus.timeout-ms", 3000),
                    netze,
                    zahl(c, "modbus.seriennummer", 0),
                    getLogger(),
                    ip -> configWertSetzen("modbus.zuletzt-gefunden", ip));
            intervall = Math.max(1, zahl(c, "intervall.modbus", 5));
        }

        fehlerInFolge = 0;
        getLogger().info("Quelle: " + quelle.name() + ", Abfrage alle " + intervall + " s");
        abfrage = getHomeX().getScheduler().runRepeating(this, this::abfragen, 0, intervall, TimeUnit.SECONDS);
    }

    private synchronized void quelleStoppen() {
        if (abfrage != null) abfrage.cancel();
        abfrage = null;
        if (quelle != null) quelle.close();
        quelle = null;
    }

    private CloudQuelle cloudQuelle(Properties c) {
        boolean sandbox = Boolean.parseBoolean(c.getProperty("cloud.sandbox", "true").trim());
        String env = sandbox ? "sandbox" : "prod";
        var e = new CloudQuelle.Einstellungen(
                c.getProperty("cloud.auth-url." + env, "").trim(),
                c.getProperty("cloud.token-url." + env, "").trim(),
                c.getProperty("cloud.api-url." + env, "").trim(),
                c.getProperty("cloud.client-id", "").trim(),
                c.getProperty("cloud.client-secret", "").trim(),
                c.getProperty("cloud.redirect-uri", "").trim(),
                c.getProperty("cloud.plant-id", "").trim(),
                c.getProperty("cloud.messwerte-pfad", "").trim());
        return new CloudQuelle(e, c.getProperty("cloud.refresh-token", "").trim(),
                rt -> configWertSetzen("cloud.refresh-token", rt));
    }

    private void abfragen() {
        SmaQuelle q = quelle;
        if (q == null) return;
        try {
            SolarData d = q.lesen();
            geraet.uebernehmen(d, q.name());
            if (fehlerInFolge > 0) getLogger().info("Solaranlage wieder erreichbar.");
            fehlerInFolge = 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fehlerInFolge++;
            geraet.offline(grund(e));
            // nicht jede Sekunde das Log zumüllen: erster Fehler + dann alle 60 Versuche
            if (fehlerInFolge == 1 || fehlerInFolge % 60 == 0) {
                getLogger().warning(q.name() + " nicht erreichbar (" + fehlerInFolge + "x): " + grund(e));
            }
        }
    }

    // ------------------------------------------------------------------
    // Konsolenbefehl "sma"
    // ------------------------------------------------------------------

    private boolean befehl(CommandSender s, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                s.sendMessage("Quelle: " + (quelle == null ? "-" : quelle.name())
                        + (quelle instanceof ModbusQuelle mq && mq.host() != null ? " – " + mq.host() : ""));
                geraet.getStates().forEach((k, v) -> s.sendMessage(String.format("  %-20s %s", k, v)));
            }
            case "jetzt" -> {
                if (abfrage == null) {
                    s.sendMessage("Die Solaranlage wird noch nicht abgefragt: " + geraet.getState("fehler"));
                    return true;
                }
                getHomeX().getScheduler().runAsync(this, () -> {
                    abfragen();
                    s.sendMessage("Aktualisiert: " + geraet.getStates());
                });
            }
            case "quelle" -> {
                if (a.length != 2 || !(a[1].equalsIgnoreCase("modbus") || a[1].equalsIgnoreCase("cloud"))) return false;
                configWertSetzen("quelle", a[1].toLowerCase());
                quelleStarten();
                s.sendMessage("Quelle auf " + a[1].toLowerCase() + " umgestellt und gespeichert.");
            }
            case "login" -> {
                if (!(quelle instanceof CloudQuelle cq)) {
                    s.sendMessage("Erst mit 'sma quelle cloud' auf die Cloud umstellen.");
                    return true;
                }
                try {
                    s.sendMessage("1. Diesen Link im Browser öffnen, bei SMA einloggen und HomeX zustimmen:");
                    s.sendMessage("   " + cq.loginUrl());
                    s.sendMessage("2. Du landest auf der redirect-uri (Seite darf 'nicht gefunden' sein).");
                    s.sendMessage("   Kopiere die komplette Adresse aus der Adresszeile und gib ein:");
                    s.sendMessage("   sma code <adresse>");
                } catch (IllegalStateException e) {
                    s.sendMessage(e.getMessage());
                }
            }
            case "code" -> {
                if (a.length != 2) return false;
                if (!(quelle instanceof CloudQuelle cq)) {
                    s.sendMessage("Erst mit 'sma quelle cloud' auf die Cloud umstellen.");
                    return true;
                }
                getHomeX().getScheduler().runAsync(this, () -> {
                    try {
                        cq.codeEinloesen(a[1]);
                        s.sendMessage("Angemeldet! Refresh-Token ist gespeichert, Werte kommen gleich.");
                        quelleStarten();
                    } catch (Exception e) {
                        s.sendMessage("Anmeldung fehlgeschlagen: " + grund(e));
                    }
                });
            }
            default -> { return false; }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    private static long zahl(Properties c, String key, long standard) {
        try {
            return Long.parseLong(c.getProperty(key, String.valueOf(standard)).trim());
        } catch (NumberFormatException e) {
            return standard;
        }
    }

    /** Ändert genau einen Wert in config.properties und lässt die Kommentare stehen. */
    private synchronized void configWertSetzen(String key, String wert) {
        Path datei = getDataFolder().resolve("config.properties");
        String zeile = key + "=" + wert.replace("\\", "\\\\");
        try {
            List<String> zeilen = Files.exists(datei)
                    ? new ArrayList<>(Files.readAllLines(datei, StandardCharsets.UTF_8)) : new ArrayList<>();
            boolean ersetzt = false;
            for (int i = 0; i < zeilen.size(); i++) {
                String t = zeilen.get(i).trim();
                if (t.startsWith(key + "=") || t.startsWith(key + " =") || t.startsWith(key + ":")) {
                    zeilen.set(i, zeile);
                    ersetzt = true;
                    break;
                }
            }
            if (!ersetzt) zeilen.add(zeile);
            Files.write(datei, zeilen, StandardCharsets.UTF_8);
            reloadConfig();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Konnte " + key + " nicht speichern", e);
        }
    }

    /** Verständliche Fehlermeldung (manche Java-Exceptions haben keinen Text). */
    static String grund(Throwable e) {
        if (e instanceof java.io.EOFException) return "Verbindung vom Wechselrichter getrennt";
        if (e instanceof java.net.ConnectException) return "keine Verbindung (Adresse/Port prüfen, läuft das Gerät?)";
        if (e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.SocketTimeoutException)
            return "Zeitüberschreitung";
        if (e instanceof java.net.UnknownHostException) return "Adresse unbekannt: " + e.getMessage();
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}

package de.homex.web;

import com.sun.net.httpserver.HttpServer;
import de.homex.api.event.DeviceRegisterEvent;
import de.homex.api.event.DeviceStateChangeEvent;
import de.homex.api.event.DeviceUnregisterEvent;
import de.homex.api.event.EventHandler;
import de.homex.api.event.EventPriority;
import de.homex.api.event.Listener;
import de.homex.api.event.PluginDisableEvent;
import de.homex.api.event.PluginEnableEvent;
import de.homex.api.plugin.JavaPlugin;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Startet den Webserver mit REST-API, Live-Events und Dashboard. */
public class WebApiPlugin extends JavaPlugin implements Listener {

    private HttpServer server;
    private ExecutorService threads;
    private final EventStream events = new EventStream();
    private final Aktivitaet aktivitaet = new Aktivitaet(e -> events.senden("activity", e));
    private final Verlauf verlauf = new Verlauf();
    private long gestartet;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Properties c = getConfig();

        String token = c.getProperty("token", "").trim();
        if (token.isEmpty()) {
            token = neuerToken();
            configWertSetzen("token", token);
            getLogger().info("Neuer Zugangsschlüssel erzeugt und in plugins/WebAPI/config.properties gespeichert.");
        }

        int port = zahl(c.getProperty("port"), 8080);
        String adresse = c.getProperty("adresse", "0.0.0.0").trim();
        Set<String> cors = Arrays.stream(c.getProperty("cors.erlaubt", "*").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        try {
            server = HttpServer.create(new InetSocketAddress(adresse, port), 64);
        } catch (IOException e) {
            throw new IllegalStateException("Port " + port + " ist belegt oder Adresse " + adresse + " ungültig: " + e.getMessage(), e);
        }
        // Virtuelle Threads: jede Anfrage und jeder Live-Client bekommt einen billigen eigenen Thread
        threads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(threads);
        try {
            verlauf.laden(verlaufsDatei());
        } catch (IOException e) {
            getLogger().warning("Verlauf nicht lesbar: " + e.getMessage());
        }
        server.createContext("/api", new ApiHandler(getHomeX(), token, cors, events, aktivitaet, verlauf, getLogger()));
        if (Boolean.parseBoolean(c.getProperty("dashboard", "true").trim())) {
            server.createContext("/", new DashboardHandler(getClassLoader()));
        }
        server.start();

        registerListener(this);
        gestartet = System.currentTimeMillis();
        aktivitaet.system("HomeX gestartet");
        // Verlauf alle 10 Minuten sichern, damit auch ein Absturz kaum Daten kostet
        getHomeX().getScheduler().runRepeating(this, this::verlaufSichern, 10, 10, java.util.concurrent.TimeUnit.MINUTES);

        for (String url : adressen(adresse, port)) getLogger().info("Dashboard: " + url);
        getLogger().info("Zugangsschlüssel (Token): " + token);
    }

    @Override
    public void onDisable() {
        verlaufSichern();
        events.alleSchliessen();
        if (server != null) server.stop(0);
        if (threads != null) threads.shutdownNow();
        server = null;
        threads = null;
    }

    // ------------------------------------------------------------------
    // Events → Live-Updates an die Browser
    // ------------------------------------------------------------------

    /** MONITOR = als Letzter, nach allen anderen Plugins; der Wert ist dann endgültig. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void beiAenderung(DeviceStateChangeEvent e) {
        verlauf.aufnehmen(e.getDevice(), e.getKey(), e.getNewValue());
        aktivitaet.aenderung(e.getDevice(), e.getKey(), e.getOldValue(), e.getNewValue(), e.getSource());
        if (events.anzahl() == 0) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getDevice().getId());
        m.put("key", e.getKey());
        m.put("value", e.getNewValue());
        if (e.getSource() != null) m.put("source", e.getSource());
        m.put("changed", System.currentTimeMillis());
        events.senden("state", m);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beiNeuemGeraet(DeviceRegisterEvent e) {
        events.senden("device", ApiHandler.geraet(e.getDevice()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beiEntferntemGeraet(DeviceUnregisterEvent e) {
        events.senden("removed", Map.of("id", e.getDevice().getId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beiPluginAn(PluginEnableEvent e) {
        events.senden("plugins", Map.of("name", e.getPlugin().getName(), "enabled", true));
        if (System.currentTimeMillis() - gestartet > 10_000) aktivitaet.system("Plugin " + e.getPlugin().getName() + " aktiviert");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beiPluginAus(PluginDisableEvent e) {
        events.senden("plugins", Map.of("name", e.getPlugin().getName(), "enabled", false));
        if (e.getPlugin() != this) aktivitaet.system("Plugin " + e.getPlugin().getName() + " deaktiviert");
    }

    private Path verlaufsDatei() { return getDataFolder().resolve("verlauf.dat"); }

    private void verlaufSichern() {
        try {
            Files.createDirectories(getDataFolder());
            verlauf.speichern(verlaufsDatei());
        } catch (IOException e) {
            getLogger().warning("Verlauf nicht gespeichert: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private static String neuerToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Alle Adressen, unter denen das Dashboard im Heimnetz erreichbar ist. */
    private static List<String> adressen(String bind, int port) {
        List<String> urls = new ArrayList<>();
        if (!bind.equals("0.0.0.0")) {
            urls.add("http://" + bind + ":" + port + "/");
            return urls;
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) urls.add("http://" + a.getHostAddress() + ":" + port + "/");
                }
            }
        } catch (IOException ignored) {}
        if (urls.isEmpty()) urls.add("http://localhost:" + port + "/");
        return urls;
    }

    private static int zahl(String s, int standard) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return standard; }
    }

    /** Ändert genau einen Wert in config.properties und lässt die Kommentare stehen. */
    private void configWertSetzen(String key, String wert) {
        Path datei = getDataFolder().resolve("config.properties");
        try {
            List<String> zeilen = Files.exists(datei)
                    ? new ArrayList<>(Files.readAllLines(datei, StandardCharsets.UTF_8)) : new ArrayList<>();
            boolean ersetzt = false;
            for (int i = 0; i < zeilen.size(); i++) {
                String t = zeilen.get(i).trim();
                if (t.startsWith(key + "=") || t.startsWith(key + " =")) {
                    zeilen.set(i, key + "=" + wert);
                    ersetzt = true;
                    break;
                }
            }
            if (!ersetzt) zeilen.add(key + "=" + wert);
            Files.write(datei, zeilen, StandardCharsets.UTF_8);
            reloadConfig();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Konnte " + key + " nicht speichern", e);
        }
    }
}

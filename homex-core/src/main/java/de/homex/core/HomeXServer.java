package de.homex.core;

import de.homex.api.HomeX;
import de.homex.api.Location;
import de.homex.api.device.ControlSource;
import de.homex.api.command.CommandSender;
import de.homex.api.device.Device;
import de.homex.api.plugin.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Optional;
import java.util.Properties;

public final class HomeXServer implements HomeX {

    public static final String VERSION = "1.0.0";

    private final Logger logger = Logger.getLogger("HomeX");
    private final SimpleEventBus eventBus;
    private final SimpleDeviceRegistry deviceRegistry;
    private final SimpleScheduler scheduler;
    private final SimpleCommandRegistry commandRegistry;
    private final SimplePluginManager pluginManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Properties settings = new Properties();

    private final CommandSender console = new CommandSender() {
        @Override public String getName() { return "Konsole"; }
        @Override public void sendMessage(String message) { System.out.println(message); }
    };

    HomeXServer(Path baseDir) {
        setupLogging();
        einstellungenLaden(baseDir.resolve("homex.properties"));
        this.eventBus = new SimpleEventBus(logger);
        this.deviceRegistry = new SimpleDeviceRegistry(eventBus);
        this.scheduler = new SimpleScheduler(logger);
        this.commandRegistry = new SimpleCommandRegistry(logger);
        this.pluginManager = new SimplePluginManager(this, baseDir.resolve("plugins"));
        registerBuiltinCommands();
    }

    private void setupLogging() {
        logger.setUseParentHandlers(false);
        for (Handler h : logger.getHandlers()) logger.removeHandler(h);
        ConsoleHandler h = new ConsoleHandler() {{ setOutputStream(System.out); }};
        h.setFormatter(new LogFormatter());
        h.setLevel(Level.ALL);
        logger.addHandler(h);
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        long t0 = System.currentTimeMillis();
        logger.info("Starte HomeX " + VERSION + " (Java " + Runtime.version() + ")");
        pluginManager.loadPlugins();
        pluginManager.enablePlugins();
        logger.info("Fertig! " + pluginManager.getPlugins().size() + " Plugin(s), "
                + deviceRegistry.getAll().size() + " Gerät(e) in "
                + (System.currentTimeMillis() - t0) + " ms. Tippe 'help' für Befehle.");
    }

    /**
     * Liest Befehle von der Konsole in einem Hintergrund-Thread und wartet, bis HomeX gestoppt wird.
     * (So beendet sich HomeX nach "stop" sofort, ohne auf eine weitere Eingabe zu warten.)
     */
    void runConsole() {
        Thread konsole = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while (running.get() && (line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        String befehl = line;
                        ControlSource.run("Konsole", () -> commandRegistry.dispatch(console, befehl));
                    }
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Konsole beendet", e);
            }
            // Ohne Konsole (z. B. als Dienst mit </dev/null) läuft HomeX einfach weiter
        }, "HomeX-Konsole");
        konsole.setDaemon(true);
        konsole.start();
        try { stopped.await(); } catch (InterruptedException ignored) {}
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        logger.info("Fahre HomeX herunter ...");
        pluginManager.unloadPlugins();
        scheduler.shutdown();
        logger.info("Tschüss!");
        stopped.countDown();
    }

    // ------------------------------------------------------------------
    // Eingebaute Befehle
    // ------------------------------------------------------------------

    private void registerBuiltinCommands() {
        commandRegistry.register("help", "help – zeigt alle Befehle", (s, l, a) -> {
            s.sendMessage("--- HomeX Befehle ---");
            commandRegistry.all().values().stream()
                    .sorted(Comparator.comparing(SimpleCommandRegistry.Command::name))
                    .forEach(c -> s.sendMessage("  " + c.description()
                            + (c.owner() == null ? "" : "  [" + c.owner().getName() + "]")));
            return true;
        }, null);

        commandRegistry.register("plugins", "plugins – listet alle Plugins (grün = aktiv)", (s, l, a) -> {
            var list = pluginManager.getPlugins();
            StringBuilder sb = new StringBuilder("Plugins (" + list.size() + "): ");
            boolean first = true;
            for (JavaPlugin p : list) {
                if (!first) sb.append(", ");
                sb.append(p.isEnabled() ? "\u001B[32m" : "\u001B[31m").append(p.getName()).append("\u001B[0m");
                first = false;
            }
            s.sendMessage(sb.toString());
            return true;
        }, null);

        commandRegistry.register("plugin", "plugin <name> – Infos zu einem Plugin", (s, l, a) -> {
            if (a.length != 1) return false;
            JavaPlugin p = pluginManager.getPlugin(a[0]);
            if (p == null) { s.sendMessage("Plugin nicht gefunden: " + a[0]); return true; }
            var d = p.getDescription();
            s.sendMessage(d.fullName() + (p.isEnabled() ? " [aktiv]" : " [inaktiv]"));
            if (!d.author().isEmpty()) s.sendMessage("  Autor: " + d.author());
            if (!d.description().isEmpty()) s.sendMessage("  " + d.description());
            if (!d.depend().isEmpty()) s.sendMessage("  Braucht: " + d.depend());
            if (!d.softdepend().isEmpty()) s.sendMessage("  Optional: " + d.softdepend());
            return true;
        }, null);

        commandRegistry.register("enable", "enable <plugin> – Plugin aktivieren", (s, l, a) -> {
            if (a.length != 1) return false;
            JavaPlugin p = pluginManager.getPlugin(a[0]);
            if (p == null) s.sendMessage("Plugin nicht gefunden: " + a[0]);
            else if (p.isEnabled()) s.sendMessage(p.getName() + " ist schon aktiv.");
            else pluginManager.enablePlugin(p);
            return true;
        }, null);

        commandRegistry.register("disable", "disable <plugin> – Plugin deaktivieren", (s, l, a) -> {
            if (a.length != 1) return false;
            JavaPlugin p = pluginManager.getPlugin(a[0]);
            if (p == null) s.sendMessage("Plugin nicht gefunden: " + a[0]);
            else if (!p.isEnabled()) s.sendMessage(p.getName() + " ist schon deaktiviert.");
            else pluginManager.disablePlugin(p);
            return true;
        }, null);

        commandRegistry.register("reload", "reload – alle Plugins entladen und plugins/ neu einlesen", (s, l, a) -> {
            logger.info("Lade alle Plugins neu ...");
            pluginManager.reload();
            logger.info("Reload fertig: " + pluginManager.getPlugins().size() + " Plugin(s).");
            return true;
        }, null);

        commandRegistry.register("devices", "devices – listet alle Geräte", (s, l, a) -> {
            var all = deviceRegistry.getAll();
            if (all.isEmpty()) { s.sendMessage("Keine Geräte registriert."); return true; }
            for (Device d : all) {
                s.sendMessage(String.format("  %-24s %-14s %-24s %-14s %s  [%s]", d.getId(), d.getType(), d.getName(),
                        d.getRoom() == null ? "-" : d.getRoom(), d.getStates(), deviceRegistry.ownerName(d)));
            }
            return true;
        }, null);

        commandRegistry.register("device", "device <id> <schlüssel> <wert> – Gerät steuern", (s, l, a) -> {
            if (a.length != 3) return false;
            deviceRegistry.get(a[0]).ifPresentOrElse(d -> {
                if (d.control(a[1], a[2])) s.sendMessage(d.getId() + " → " + d.getStates());
                else s.sendMessage(d.getId() + ": '" + a[1] + " " + a[2] + "' wurde nicht ausgeführt (ungültig, blockiert oder Gerät nicht erreichbar).");
            }, () -> s.sendMessage("Gerät nicht gefunden: " + a[0]));
            return true;
        }, null);

        commandRegistry.register("stop", "stop – HomeX beenden", (s, l, a) -> {
            new Thread(this::shutdown, "HomeX-Stop").start();
            return true;
        }, null);
    }

    // ------------------------------------------------------------------
    // HomeX-Schnittstelle
    // ------------------------------------------------------------------

    @Override public String getName() { return "HomeX"; }
    @Override public String getVersion() { return VERSION; }

    @Override
    public String getSetting(String key, String standard) {
        String v = settings.getProperty(key);
        return v == null || v.isBlank() ? standard : v.trim();
    }

    @Override
    public Optional<Location> getLocation() {
        try {
            String lat = getSetting("standort.breitengrad", null), lon = getSetting("standort.laengengrad", null);
            if (lat == null || lon == null) return Optional.empty();
            return Optional.of(new Location(Double.parseDouble(lat.replace(',', '.')),
                    Double.parseDouble(lon.replace(',', '.'))));
        } catch (IllegalArgumentException e) {
            logger.warning("homex.properties: Standort ungültig – " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Liest homex.properties; legt sie mit Erklärungen an, wenn sie fehlt. */
    private void einstellungenLaden(Path datei) {
        try {
            if (!Files.exists(datei)) {
                try (var in = getClass().getResourceAsStream("/homex.properties")) {
                    if (in != null) Files.copy(in, datei);
                }
                logger.info("homex.properties angelegt – dort kannst du Hausname und Standort eintragen.");
            }
            try (Reader r = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
                settings.load(r);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "homex.properties nicht lesbar", e);
        }
    }
    @Override public Logger getLogger() { return logger; }
    @Override public SimplePluginManager getPluginManager() { return pluginManager; }
    @Override public SimpleEventBus getEventBus() { return eventBus; }
    @Override public SimpleDeviceRegistry getDeviceRegistry() { return deviceRegistry; }
    @Override public SimpleScheduler getScheduler() { return scheduler; }
    @Override public SimpleCommandRegistry getCommandRegistry() { return commandRegistry; }

}

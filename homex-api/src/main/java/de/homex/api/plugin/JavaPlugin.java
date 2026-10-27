package de.homex.api.plugin;

import de.homex.api.HomeX;
import de.homex.api.command.CommandExecutor;
import de.homex.api.event.Listener;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Basisklasse für jedes HomeX-Plugin – genau wie {@code JavaPlugin} bei Spigot.
 *
 * <pre>
 * public class MeinPlugin extends JavaPlugin {
 *     &#64;Override public void onEnable()  { getLogger().info("An!"); }
 *     &#64;Override public void onDisable() { getLogger().info("Aus!"); }
 * }
 * </pre>
 *
 * Lebenszyklus: Konstruktor → {@link #onLoad()} → {@link #onEnable()} → ... → {@link #onDisable()}
 */
public abstract class JavaPlugin {

    private HomeX homeX;
    private PluginDescription description;
    private Path dataFolder;
    private Logger logger;
    private ClassLoader classLoader;
    private volatile boolean enabled = false;
    private Properties config;

    // ------------------------------------------------------------------
    // Lebenszyklus – diese Methoden überschreibt das Plugin
    // ------------------------------------------------------------------

    /** Wird aufgerufen, sobald die JAR geladen ist – noch BEVOR irgendein Plugin aktiviert wird. */
    public void onLoad() {}

    /** Wird beim Aktivieren aufgerufen: Geräte, Listener, Befehle, Tasks registrieren. */
    public void onEnable() {}

    /** Wird beim Deaktivieren/Herunterfahren aufgerufen: Verbindungen schließen, Daten speichern. */
    public void onDisable() {}

    // ------------------------------------------------------------------
    // Vom Plugin nutzbare Helfer
    // ------------------------------------------------------------------

    public final HomeX getHomeX() { return homeX; }

    public final PluginDescription getDescription() { return description; }

    public final String getName() { return description.name(); }

    public final Logger getLogger() { return logger; }

    /** Eigener Ordner unter {@code plugins/<Name>/} für Konfiguration und Daten. */
    public final Path getDataFolder() { return dataFolder; }

    public final boolean isEnabled() { return enabled; }

    protected final ClassLoader getClassLoader() { return classLoader; }

    /** Kurzform für {@code getHomeX().getEventBus().registerListener(listener, this)}. */
    public final void registerListener(Listener listener) {
        homeX.getEventBus().registerListener(listener, this);
    }

    /** Kurzform zum Registrieren eines Konsolenbefehls. */
    public final void registerCommand(String name, String description, CommandExecutor executor) {
        homeX.getCommandRegistry().register(name, description, executor, this);
    }

    /** Liest eine Datei aus der eigenen JAR (z. B. eine Standard-Config). */
    public final InputStream getResource(String name) {
        return classLoader.getResourceAsStream(name);
    }

    // ---------------------------- Config ------------------------------

    /**
     * Kopiert {@code config.properties} aus der JAR nach {@code plugins/<Name>/config.properties},
     * falls sie dort noch nicht existiert.
     */
    public final void saveDefaultConfig() {
        Path target = dataFolder.resolve("config.properties");
        if (Files.exists(target)) return;
        try (InputStream in = getResource("config.properties")) {
            Files.createDirectories(dataFolder);
            if (in != null) Files.copy(in, target);
            else Files.createFile(target);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Konnte Standard-Config nicht speichern", e);
        }
    }

    public final Properties getConfig() {
        if (config == null) reloadConfig();
        return config;
    }

    public final void reloadConfig() {
        config = new Properties();
        Path file = dataFolder.resolve("config.properties");
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(r);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Konnte config.properties nicht lesen", e);
        }
    }

    public final void saveConfig() {
        try {
            Files.createDirectories(dataFolder);
            try (Writer w = Files.newBufferedWriter(dataFolder.resolve("config.properties"), StandardCharsets.UTF_8)) {
                getConfig().store(w, description.name() + " Konfiguration");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Konnte config.properties nicht speichern", e);
        }
    }

    // ------------------------------------------------------------------
    // Intern – wird nur vom HomeX-Kern aufgerufen
    // ------------------------------------------------------------------

    /** Wird vom PluginManager direkt nach dem Erzeugen aufgerufen. Nicht selbst benutzen. */
    public final void init(HomeX homeX, PluginDescription description, Path dataFolder,
                           Logger logger, ClassLoader classLoader) {
        if (this.homeX != null) throw new IllegalStateException("Plugin ist bereits initialisiert");
        this.homeX = homeX;
        this.description = description;
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.classLoader = classLoader;
    }

    /** Wird vom PluginManager aufgerufen. Ruft onEnable()/onDisable() auf. Nicht selbst benutzen. */
    public final void setEnabled(boolean enable) {
        if (enabled == enable) return;
        if (enable) {
            enabled = true;   // vor onEnable, damit Registrierungen im onEnable erlaubt sind
            onEnable();
        } else {
            try {
                onDisable();
            } finally {
                enabled = false;
            }
        }
    }

    @Override
    public String toString() {
        return description == null ? getClass().getName() : description.fullName();
    }
}

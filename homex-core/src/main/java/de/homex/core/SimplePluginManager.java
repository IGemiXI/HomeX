package de.homex.core;

import de.homex.api.event.PluginDisableEvent;
import de.homex.api.event.PluginEnableEvent;
import de.homex.api.plugin.JavaPlugin;
import de.homex.api.plugin.PluginDescription;
import de.homex.api.plugin.PluginLoadException;
import de.homex.api.plugin.PluginManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

/**
 * Lädt alle JARs aus {@code plugins/}, sortiert sie nach Abhängigkeiten und steuert
 * den Lebenszyklus (onLoad → onEnable → onDisable) – das Herz des Plugin-Systems.
 */
final class SimplePluginManager implements PluginManager {

    private final HomeXServer server;
    private final Path pluginsDir;
    private final Logger log;

    /** Geladene Plugins in Lade-Reihenfolge, Schlüssel = Name in Kleinbuchstaben. */
    private final Map<String, JavaPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, PluginClassLoader> loaders = new LinkedHashMap<>();

    SimplePluginManager(HomeXServer server, Path pluginsDir) {
        this.server = server;
        this.pluginsDir = pluginsDir;
        this.log = server.getLogger();
    }

    // =====================================================================
    // Laden
    // =====================================================================

    /** Schritt 1: Alle JARs finden, plugin.yml lesen, sortieren, Hauptklasse erzeugen, onLoad() aufrufen. */
    synchronized void loadPlugins() {
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Plugin-Ordner kann nicht angelegt werden: " + pluginsDir, e);
            return;
        }

        // 1) plugin.yml aus jeder JAR lesen
        Map<String, PluginDescription> descriptions = new LinkedHashMap<>();
        Map<String, Path> jars = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            List<Path> files = new ArrayList<>();
            stream.forEach(files::add);
            files.sort(null);
            for (Path jar : files) {
                try {
                    PluginDescription d = readDescription(jar);
                    String key = key(d.name());
                    if (descriptions.containsKey(key) || plugins.containsKey(key)) {
                        log.severe("Doppeltes Plugin '" + d.name() + "' in " + jar.getFileName() + " – wird übersprungen");
                        continue;
                    }
                    descriptions.put(key, d);
                    jars.put(key, jar);
                } catch (PluginLoadException e) {
                    log.severe(e.getMessage());
                }
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Plugin-Ordner nicht lesbar", e);
            return;
        }

        // 2) nach Abhängigkeiten sortieren und laden
        for (PluginDescription d : sortByDependencies(descriptions)) {
            List<String> missing = d.depend().stream().filter(dep -> !plugins.containsKey(key(dep))).toList();
            if (!missing.isEmpty()) {
                log.severe("Kann " + d.fullName() + " nicht laden – fehlende Abhängigkeit(en): " + missing);
                continue;
            }
            try {
                loadPlugin(jars.get(key(d.name())), d);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Fehler beim Laden von " + d.fullName(), t);
            }
        }
    }

    private PluginDescription readDescription(Path jar) throws PluginLoadException {
        String source = jar.getFileName().toString();
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry entry = jf.getEntry("plugin.yml");
            if (entry == null) throw new PluginLoadException(source + ": keine plugin.yml in der JAR gefunden");
            try (InputStream in = jf.getInputStream(entry)) {
                return DescriptionParser.parse(in, source);
            }
        } catch (IOException e) {
            throw new PluginLoadException(source + ": ist keine gültige JAR-Datei", e);
        }
    }

    private void loadPlugin(Path jar, PluginDescription d) throws Exception {
        PluginClassLoader loader = new PluginClassLoader(jar, d, getClass().getClassLoader(), this);
        String k = key(d.name());
        loaders.put(k, loader);
        try {
            Class<?> mainClass = Class.forName(d.main(), true, loader);
            if (!JavaPlugin.class.isAssignableFrom(mainClass))
                throw new PluginLoadException(d.main() + " erbt nicht von JavaPlugin");

            JavaPlugin plugin = (JavaPlugin) mainClass.getDeclaredConstructor().newInstance();
            Logger pluginLogger = Logger.getLogger("HomeX." + d.name());
            pluginLogger.setParent(log);
            plugin.init(server, d, pluginsDir.resolve(d.name()), pluginLogger, loader);

            plugins.put(k, plugin);
            log.info("Lade " + d.fullName());
            plugin.onLoad();
        } catch (Throwable t) {
            plugins.remove(k);
            loaders.remove(k);
            loader.close();
            throw t;
        }
    }

    /** Topologische Sortierung: Abhängigkeiten werden immer VOR dem Plugin geladen, das sie braucht. */
    private List<PluginDescription> sortByDependencies(Map<String, PluginDescription> all) {
        List<PluginDescription> sorted = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String k : all.keySet()) visit(k, all, done, visiting, sorted);
        return sorted;
    }

    private void visit(String k, Map<String, PluginDescription> all, Set<String> done,
                       Set<String> visiting, List<PluginDescription> out) {
        if (done.contains(k)) return;
        PluginDescription d = all.get(k);
        if (d == null) return;
        if (!visiting.add(k)) {
            log.severe("Zirkuläre Abhängigkeit bei Plugin " + d.name() + " entdeckt!");
            return;
        }
        List<String> deps = new ArrayList<>(d.depend());
        deps.addAll(d.softdepend());
        for (String dep : deps) visit(key(dep), all, done, visiting, out);
        visiting.remove(k);
        done.add(k);
        out.add(d);
    }

    // =====================================================================
    // Aktivieren / Deaktivieren
    // =====================================================================

    /** Schritt 2: Alle geladenen Plugins aktivieren (in Lade-Reihenfolge). */
    synchronized void enablePlugins() {
        for (JavaPlugin p : List.copyOf(plugins.values())) enablePlugin(p);
    }

    @Override
    public synchronized void enablePlugin(JavaPlugin plugin) {
        if (plugin.isEnabled()) return;
        for (String dep : plugin.getDescription().depend()) {
            JavaPlugin d = getPlugin(dep);
            if (d == null || !d.isEnabled()) {
                log.severe("Kann " + plugin + " nicht aktivieren – " + dep + " ist nicht aktiv");
                return;
            }
        }
        log.info("Aktiviere " + plugin);
        try {
            plugin.setEnabled(true);
            server.getEventBus().callEvent(new PluginEnableEvent(plugin));
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Fehler in onEnable() von " + plugin + " – Plugin wird deaktiviert", t);
            disablePlugin(plugin);
        }
    }

    @Override
    public synchronized void disablePlugin(JavaPlugin plugin) {
        // Zuerst alle Plugins abschalten, die von diesem abhängen
        for (JavaPlugin other : List.copyOf(plugins.values())) {
            if (other != plugin && other.isEnabled() && containsIgnoreCase(other.getDescription().depend(), plugin.getName())) {
                disablePlugin(other);
            }
        }
        if (plugin.isEnabled()) {
            log.info("Deaktiviere " + plugin);
            server.getEventBus().callEvent(new PluginDisableEvent(plugin));
            try {
                plugin.setEnabled(false);
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Fehler in onDisable() von " + plugin, t);
            }
        }
        // Aufräumen – alles, was das Plugin registriert hat, verschwindet
        server.getScheduler().cancelAll(plugin);
        server.getEventBus().unregisterAll(plugin);
        server.getCommandRegistry().unregisterAll(plugin);
        server.getDeviceRegistry().unregisterAll(plugin);
    }

    /** Alle Plugins in umgekehrter Reihenfolge deaktivieren. */
    synchronized void disablePlugins() {
        List<JavaPlugin> list = new ArrayList<>(plugins.values());
        for (int i = list.size() - 1; i >= 0; i--) disablePlugin(list.get(i));
    }

    /** Deaktiviert alles und gibt die JAR-Dateien frei (danach können sie ausgetauscht werden). */
    synchronized void unloadPlugins() {
        disablePlugins();
        for (PluginClassLoader l : loaders.values()) {
            try { l.close(); } catch (IOException ignored) {}
        }
        loaders.clear();
        plugins.clear();
    }

    /** Wie /reload bei Spigot: alles entladen, Ordner neu einlesen, alles aktivieren. */
    synchronized void reload() {
        unloadPlugins();
        loadPlugins();
        enablePlugins();
    }

    // =====================================================================
    // Abfragen
    // =====================================================================

    @Override
    public synchronized JavaPlugin getPlugin(String name) {
        return name == null ? null : plugins.get(key(name));
    }

    @Override
    public synchronized Collection<JavaPlugin> getPlugins() {
        return List.copyOf(plugins.values());
    }

    @Override
    public boolean isPluginEnabled(String name) {
        JavaPlugin p = getPlugin(name);
        return p != null && p.isEnabled();
    }

    /** Wird vom PluginClassLoader benutzt, damit Plugins Klassen anderer Plugins sehen. */
    Class<?> findClassInOtherPlugins(String name, PluginClassLoader requester) {
        List<PluginClassLoader> snapshot;
        synchronized (this) { snapshot = List.copyOf(loaders.values()); }
        for (PluginClassLoader l : snapshot) {
            if (l == requester) continue;
            try {
                return l.findLocalClass(name);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        return list.stream().anyMatch(x -> x.equalsIgnoreCase(s));
    }
}

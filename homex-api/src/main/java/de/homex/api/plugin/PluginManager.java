package de.homex.api.plugin;

import java.util.Collection;

public interface PluginManager {

    /** Plugin anhand seines Namens aus der plugin.yml (Groß-/Kleinschreibung egal). */
    JavaPlugin getPlugin(String name);

    Collection<JavaPlugin> getPlugins();

    boolean isPluginEnabled(String name);

    void enablePlugin(JavaPlugin plugin);

    void disablePlugin(JavaPlugin plugin);
}

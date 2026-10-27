package de.homex.api.event;

import de.homex.api.plugin.JavaPlugin;

public class PluginEnableEvent extends Event {
    private final JavaPlugin plugin;
    public PluginEnableEvent(JavaPlugin plugin) { this.plugin = plugin; }
    public JavaPlugin getPlugin() { return plugin; }
}

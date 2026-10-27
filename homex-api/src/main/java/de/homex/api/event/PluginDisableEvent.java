package de.homex.api.event;

import de.homex.api.plugin.JavaPlugin;

public class PluginDisableEvent extends Event {
    private final JavaPlugin plugin;
    public PluginDisableEvent(JavaPlugin plugin) { this.plugin = plugin; }
    public JavaPlugin getPlugin() { return plugin; }
}

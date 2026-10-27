package de.homex.api;

import de.homex.api.command.CommandRegistry;
import de.homex.api.device.DeviceRegistry;
import de.homex.api.event.EventBus;
import de.homex.api.plugin.PluginManager;
import de.homex.api.scheduler.Scheduler;

import java.util.logging.Logger;

/**
 * Der zentrale Zugriffspunkt auf HomeX (vergleichbar mit {@code Bukkit.getServer()}).
 * Jedes Plugin bekommt ihn über {@code getHomeX()}.
 */
public interface HomeX {

    String getName();

    String getVersion();

    Logger getLogger();

    PluginManager getPluginManager();

    EventBus getEventBus();

    DeviceRegistry getDeviceRegistry();

    Scheduler getScheduler();

    CommandRegistry getCommandRegistry();

    /** Fährt HomeX sauber herunter (alle Plugins werden deaktiviert). */
    void shutdown();
}

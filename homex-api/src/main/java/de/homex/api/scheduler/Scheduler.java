package de.homex.api.scheduler;

import de.homex.api.plugin.JavaPlugin;

import java.util.concurrent.TimeUnit;

/**
 * Zeitgesteuerte Aufgaben, z. B. "alle 10 Sekunden den Wechselrichter abfragen".
 * Alle Tasks eines Plugins werden beim Deaktivieren automatisch gestoppt.
 */
public interface Scheduler {

    Task runAsync(JavaPlugin owner, Runnable task);

    Task runLater(JavaPlugin owner, Runnable task, long delay, TimeUnit unit);

    Task runRepeating(JavaPlugin owner, Runnable task, long initialDelay, long period, TimeUnit unit);

    void cancelAll(JavaPlugin owner);
}

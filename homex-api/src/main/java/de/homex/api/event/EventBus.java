package de.homex.api.event;

import de.homex.api.plugin.JavaPlugin;

public interface EventBus {

    void registerListener(Listener listener, JavaPlugin owner);

    /** Entfernt alle Listener eines Plugins (passiert beim Deaktivieren automatisch). */
    void unregisterAll(JavaPlugin owner);

    /** Verteilt ein Event an alle passenden Listener und gibt es zurück. */
    <T extends Event> T callEvent(T event);
}

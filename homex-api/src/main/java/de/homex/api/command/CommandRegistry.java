package de.homex.api.command;

import de.homex.api.plugin.JavaPlugin;

public interface CommandRegistry {

    /** @param owner null = eingebauter Befehl von HomeX selbst */
    void register(String name, String description, CommandExecutor executor, JavaPlugin owner);

    void unregisterAll(JavaPlugin owner);

    /** Führt eine komplette Befehlszeile aus, z. B. {@code "device licht-wohnzimmer power on"}. */
    boolean dispatch(CommandSender sender, String commandLine);
}

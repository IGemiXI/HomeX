package de.homex.core;

import de.homex.api.command.CommandExecutor;
import de.homex.api.command.CommandRegistry;
import de.homex.api.command.CommandSender;
import de.homex.api.plugin.JavaPlugin;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SimpleCommandRegistry implements CommandRegistry {

    record Command(String name, String description, CommandExecutor executor, JavaPlugin owner) {}

    private final Map<String, Command> commands = new TreeMap<>();
    private final Logger log;

    SimpleCommandRegistry(Logger log) { this.log = log; }

    @Override
    public synchronized void register(String name, String description, CommandExecutor executor, JavaPlugin owner) {
        String k = name.toLowerCase(Locale.ROOT);
        Command existing = commands.get(k);
        if (existing != null) {
            String by = existing.owner() == null ? "HomeX" : existing.owner().getName();
            throw new IllegalArgumentException("Befehl '" + name + "' ist schon von " + by + " registriert");
        }
        commands.put(k, new Command(k, description, executor, owner));
    }

    @Override
    public synchronized void unregisterAll(JavaPlugin owner) {
        commands.values().removeIf(c -> c.owner() == owner);
    }

    @Override
    public boolean dispatch(CommandSender sender, String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return false;
        Command cmd;
        synchronized (this) { cmd = commands.get(parts[0].toLowerCase(Locale.ROOT)); }
        if (cmd == null) {
            sender.sendMessage("Unbekannter Befehl. Tippe 'help' für eine Liste.");
            return false;
        }
        try {
            boolean ok = cmd.executor().onCommand(sender, parts[0], Arrays.copyOfRange(parts, 1, parts.length));
            if (!ok) sender.sendMessage("Benutzung: " + cmd.description());
            return ok;
        } catch (Throwable t) {
            log.log(Level.SEVERE, "Fehler beim Ausführen von '" + line + "'", t);
            sender.sendMessage("Beim Ausführen ist ein Fehler aufgetreten (siehe Log).");
            return false;
        }
    }

    synchronized Map<String, Command> all() { return Map.copyOf(commands); }
}

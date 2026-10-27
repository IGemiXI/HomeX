package de.homex.api.command;

@FunctionalInterface
public interface CommandExecutor {
    /**
     * @param sender wer den Befehl ausführt
     * @param label  der eingegebene Befehlsname
     * @param args   alles nach dem Befehlsnamen, an Leerzeichen getrennt
     * @return false = falsche Benutzung, die Hilfe wird angezeigt
     */
    boolean onCommand(CommandSender sender, String label, String[] args);
}

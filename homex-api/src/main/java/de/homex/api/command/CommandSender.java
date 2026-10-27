package de.homex.api.command;

/** Wer einen Befehl ausführt – heute die Konsole, später z. B. Web-Oberfläche oder App. */
public interface CommandSender {
    String getName();
    void sendMessage(String message);
}

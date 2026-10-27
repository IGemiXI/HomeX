package de.homex.core;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) {
        Path baseDir = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        HomeXServer server = new HomeXServer(baseDir);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "HomeX-Shutdown"));
        server.start();
        server.runConsole();   // blockiert, bis "stop" eingegeben wird
        System.exit(0);        // übrige Threads (z. B. von Plugins) nicht auf sich warten lassen
    }
}

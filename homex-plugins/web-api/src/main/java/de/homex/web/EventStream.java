package de.homex.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Live-Updates per Server-Sent Events (GET /api/events).
 * Der Browser hält die Verbindung offen und bekommt jede Gerätänderung sofort.
 */
final class EventStream {

    private static final String ENDE = "\u0000ende";

    private final Set<BlockingQueue<String>> clients = new CopyOnWriteArraySet<>();

    int anzahl() { return clients.size(); }

    /** Schickt ein Event an alle verbundenen Browser. */
    void senden(String event, Object daten) {
        String msg = "event: " + event + "\ndata: " + JsonOut.of(daten) + "\n\n";
        for (BlockingQueue<String> q : clients) {
            if (!q.offer(msg)) {           // Browser hängt hinterher → rauswerfen, er verbindet sich neu
                clients.remove(q);
                q.clear();
                q.offer(ENDE);
            }
        }
    }

    /** Beendet alle offenen Verbindungen (beim Deaktivieren des Plugins). */
    void alleSchliessen() {
        for (BlockingQueue<String> q : clients) { q.clear(); q.offer(ENDE); }
        clients.clear();
    }

    /** Blockiert, solange der Browser verbunden ist. Läuft in einem eigenen (virtuellen) Thread. */
    void bedienen(HttpExchange ex, Supplier<Object> startDaten) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        BlockingQueue<String> q = new ArrayBlockingQueue<>(500);
        clients.add(q);
        try (OutputStream out = ex.getResponseBody()) {
            // Erst den kompletten Stand schicken, dann nur noch Änderungen
            schreiben(out, "retry: 3000\nevent: snapshot\ndata: " + JsonOut.of(startDaten.get()) + "\n\n");
            while (true) {
                String msg = q.poll(20, TimeUnit.SECONDS);
                if (msg == ENDE) break;
                schreiben(out, msg == null ? ": ping\n\n" : msg);   // Ping hält die Verbindung offen
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Browser hat die Verbindung geschlossen
        } finally {
            clients.remove(q);
            ex.close();
        }
    }

    private static void schreiben(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}

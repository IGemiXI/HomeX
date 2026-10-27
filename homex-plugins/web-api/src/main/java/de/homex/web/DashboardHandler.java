package de.homex.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/** Liefert das Dashboard (Dateien unter /web in der Plugin-JAR) aus. */
final class DashboardHandler implements HttpHandler {

    private static final Map<String, String> TYPEN = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "webmanifest", "application/manifest+json",
            "json", "application/json",
            "ico", "image/x-icon");

    private final ClassLoader loader;

    DashboardHandler(ClassLoader loader) { this.loader = loader; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String pfad = ex.getRequestURI().getPath();
            if (pfad.equals("/") || pfad.isEmpty()) pfad = "/index.html";
            // keine Pfade außerhalb von /web zulassen
            if (pfad.contains("..") || pfad.contains("\\") || pfad.contains("//")) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            byte[] daten;
            try (InputStream in = loader.getResourceAsStream("web" + pfad)) {
                if (in == null) {
                    byte[] b = "Nicht gefunden".getBytes();
                    ex.sendResponseHeaders(404, b.length);
                    try (OutputStream out = ex.getResponseBody()) { out.write(b); }
                    return;
                }
                daten = in.readAllBytes();
            }
            String endung = pfad.substring(pfad.lastIndexOf('.') + 1);
            var h = ex.getResponseHeaders();
            h.set("Content-Type", TYPEN.getOrDefault(endung, "application/octet-stream"));
            // immer frisch prüfen – das Dashboard soll nach einem Plugin-Update sofort aktuell sein
            h.set("Cache-Control", "no-cache");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            if (ex.getRequestMethod().equals("HEAD")) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, daten.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(daten); }
        }
    }
}

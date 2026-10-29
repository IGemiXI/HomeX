package de.homex.dummy;

import de.homex.api.command.CommandSender;
import de.homex.api.plugin.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;

/**
 * Test-Geräte für HomeX. Sie verhalten sich wie echte Geräte (inkl. Antwortzeit und auf Wunsch Fehlern)
 * und melden jeden Schaltvorgang in der Konsole – so lassen sich Dashboard, Szenen und Regeln testen,
 * bevor die echte Hardware angebunden ist.
 */
public class DummyGeraetePlugin extends JavaPlugin {

    private final DummyGeraet.Verhalten verhalten = new DummyGeraet.Verhalten();
    private final List<DummyGeraet> geraete = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Properties c = getConfig();
        verhalten.verzoegerungMs = zahl(c, "verzoegerung-ms", 200, 0, 10_000);
        verhalten.fehlerquote = zahl(c, "fehlerquote", 0, 0, 100);
        verhalten.fahrzeitS = zahl(c, "rollladen-fahrzeit-s", 12, 1, 300);

        Map<String, Map<String, String>> gespeichert = zustandLaden();
        for (String[] def : geraeteAusConfig()) {
            String id = def[0], typ = def[1], name = def[2], raum = def[3];
            try {
                DummyGeraet g = switch (typ) {
                    case "licht" -> new DummyLicht(id, name, raum, false, verhalten, getLogger());
                    case "dimmer" -> new DummyLicht(id, name, raum, true, verhalten, getLogger());
                    case "steckdose", "schalter" -> new DummySteckdose(id, name, raum, verhalten, getLogger());
                    case "rollladen", "jalousie" -> new DummyRollladen(id, name, raum, verhalten, getLogger(), this);
                    default -> throw new IllegalArgumentException("unbekannter Typ '" + typ + "' (licht, dimmer, steckdose, rollladen)");
                };
                g.wiederherstellen(gespeichert.getOrDefault(id, Map.of()));
                getHomeX().getDeviceRegistry().register(g, this);
                geraete.add(g);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Gerät '" + id + "' übersprungen: " + e.getMessage());
            }
        }

        registerCommand("dummy", "dummy [liste | fehler <prozent> | verzoegerung <ms>] – Test-Geräte", this::befehl);

        getLogger().info(geraete.size() + " Test-Gerät(e) bereit. Antwortzeit " + verhalten.verzoegerungMs
                + " ms, Fehlerquote " + verhalten.fehlerquote + " %. Jeder Schaltvorgang erscheint hier mit OK/FEHLER.");
    }

    @Override
    public void onDisable() {
        zustandSpeichern();
        geraete.clear();
    }

    // ------------------------------------------------------------------ Config

    /** Liest die "geraet.<id> = typ, Name, Raum"-Zeilen in Datei-Reihenfolge. */
    private List<String[]> geraeteAusConfig() {
        List<String[]> out = new ArrayList<>();
        Path datei = getDataFolder().resolve("config.properties");
        try {
            for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                String z = zeile.trim();
                if (!z.startsWith("geraet.")) continue;
                int eq = z.indexOf('=');
                if (eq < 0) continue;
                String id = z.substring("geraet.".length(), eq).trim();
                String[] teile = z.substring(eq + 1).split(",", 3);
                if (teile.length < 2) {
                    getLogger().warning("Zeile nicht verstanden (geraet.<id> = typ, Name, Raum): " + z);
                    continue;
                }
                out.add(new String[]{id, teile[0].trim().toLowerCase(), teile[1].trim(),
                        teile.length > 2 ? teile[2].trim() : null});
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "config.properties nicht lesbar", e);
        }
        return out;
    }

    private static int zahl(Properties c, String key, int standard, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(c.getProperty(key, "" + standard).trim())));
        } catch (NumberFormatException e) {
            return standard;
        }
    }

    // ------------------------------------------------------------------ Zustand merken

    private Path zustandsDatei() { return getDataFolder().resolve("zustand.properties"); }

    private Map<String, Map<String, String>> zustandLaden() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Path datei = zustandsDatei();
        if (!Files.exists(datei)) return out;
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return out;
        }
        for (String k : p.stringPropertyNames()) {
            int punkt = k.lastIndexOf('.');
            if (punkt > 0) out.computeIfAbsent(k.substring(0, punkt), x -> new LinkedHashMap<>())
                    .put(k.substring(punkt + 1), p.getProperty(k));
        }
        return out;
    }

    /** Merkt sich, was an ist, damit nach einem Neustart alles wieder so aussieht wie vorher. */
    private void zustandSpeichern() {
        Properties p = new Properties();
        for (DummyGeraet g : geraete) {
            g.getStates().forEach((k, v) -> {
                if (!k.equals("bewegung")) p.setProperty(g.getId() + "." + k, String.valueOf(v));
            });
        }
        try (var w = Files.newBufferedWriter(zustandsDatei(), StandardCharsets.UTF_8)) {
            p.store(w, "Zustand der Test-Geräte (wird automatisch geschrieben)");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Zustand nicht gespeichert", e);
        }
    }

    // ------------------------------------------------------------------ Befehl "dummy"

    private boolean befehl(CommandSender s, String label, String[] a) {
        String sub = a.length == 0 ? "liste" : a[0].toLowerCase();
        switch (sub) {
            case "liste" -> {
                s.sendMessage("Test-Geräte (Antwortzeit " + verhalten.verzoegerungMs + " ms, Fehlerquote "
                        + verhalten.fehlerquote + " %):");
                for (DummyGeraet g : geraete) {
                    s.sendMessage(String.format("  %-26s %-10s %-18s %-14s %s", g.getId(), g.typName(), g.getName(),
                            g.getRoom() == null ? "-" : g.getRoom(), g.getStates()));
                }
                s.sendMessage("Schalten z. B.: device licht-kueche power an");
            }
            case "fehler" -> {
                if (a.length != 2) return false;
                try {
                    verhalten.fehlerquote = Math.max(0, Math.min(100, Integer.parseInt(a[1])));
                    s.sendMessage("Ab jetzt schlagen " + verhalten.fehlerquote + " % der Befehle fehl (gilt bis zum Neustart).");
                } catch (NumberFormatException e) { return false; }
            }
            case "verzoegerung" -> {
                if (a.length != 2) return false;
                try {
                    verhalten.verzoegerungMs = Math.max(0, Math.min(10_000, Integer.parseInt(a[1])));
                    s.sendMessage("Antwortzeit jetzt " + verhalten.verzoegerungMs + " ms (gilt bis zum Neustart).");
                } catch (NumberFormatException e) { return false; }
            }
            default -> { return false; }
        }
        return true;
    }
}

package de.homex.automation;

import de.homex.api.Location;
import de.homex.api.command.CommandSender;
import de.homex.api.device.Device;
import de.homex.api.event.DeviceStateChangeEvent;
import de.homex.api.event.EventHandler;
import de.homex.api.event.EventPriority;
import de.homex.api.event.Listener;
import de.homex.api.plugin.JavaPlugin;
import de.homex.automation.Modell.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Szenen und Regeln für HomeX.
 * Szenen und Regeln erscheinen als Geräte (Typ SCENE / AUTOMATION) – damit kann sie jede Oberfläche
 * anzeigen und auslösen, ohne dieses Plugin zu kennen.
 */
public class AutomationPlugin extends JavaPlugin implements Listener {

    private final Map<String, SzeneGeraet> szenen = new LinkedHashMap<>();   // slug → Gerät
    private final Map<String, RegelGeraet> regeln = new LinkedHashMap<>();   // slug → Gerät
    private Ausfuehrer ausfuehrer;
    private final ZoneId zone = ZoneId.systemDefault();
    private int nachholenMin;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String datei : List.of("szenen.txt", "regeln.txt", "regeln-status.properties")) vorlageKopieren(datei);
        alteBeispieleErsetzen();
        nachholenMin = (int) zahl("nachholen-min", 2);

        ausfuehrer = new Ausfuehrer(getHomeX(), this::szeneFinden);
        laden();
        registrieren(new VerwaltungGeraet(this::verwalten));

        registerListener(this);
        registerCommand("szene", "szene <Name> – Szene ausführen (ohne Namen: Liste)", this::befehlSzene);
        registerCommand("regel", "regel [<Name> an|aus|test] – Regeln anzeigen und schalten", this::befehlRegel);
        registerCommand("automation", "automation neu – szenen.txt und regeln.txt neu einlesen", (s, l, a) -> {
            if (a.length != 1 || !a[0].equalsIgnoreCase("neu")) return false;
            laden();
            s.sendMessage(szenen.size() + " Szene(n) und " + regeln.size() + " Regel(n) geladen.");
            return true;
        });

        long intervall = Math.max(5, zahl("pruefintervall", 15));
        getHomeX().getScheduler().runRepeating(this, this::pruefen, 2, intervall, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        szenen.clear();
        regeln.clear();
    }

    // =====================================================================
    // Laden
    // =====================================================================

    private synchronized void laden() {
        szenen.values().forEach(g -> getHomeX().getDeviceRegistry().unregister(g.getId()));
        regeln.values().forEach(g -> getHomeX().getDeviceRegistry().unregister(g.getId()));
        szenen.clear();
        regeln.clear();

        Parser p = new Parser();
        for (Szene s : p.szenen(zeilen("szenen.txt"))) {
            if (szenen.containsKey(s.id())) { p.fehler.add("Szene '" + s.name() + "' gibt es doppelt"); continue; }
            SzeneGeraet g = new SzeneGeraet(s, this::szeneAusfuehren);
            registrieren(g);
            szenen.put(s.id(), g);
        }
        Properties status = status();
        for (Parser.RegelDef r : p.regeln(zeilen("regeln.txt"))) {
            if (regeln.containsKey(r.id())) { p.fehler.add("Regel '" + r.name() + "' gibt es doppelt"); continue; }
            boolean aktiv = Boolean.parseBoolean(status.getProperty("aktiv." + r.id(), "true"));
            RegelGeraet g = new RegelGeraet(r, aktiv, this::ausloesen, this::aktivGeaendert);
            registrieren(g);
            regeln.put(r.id(), g);
        }
        p.fehler.forEach(f -> getLogger().warning(f));
        long aktiv = regeln.values().stream().filter(RegelGeraet::aktiv).count();
        getLogger().info(szenen.size() + " Szene(n), " + regeln.size() + " Regel(n) (" + aktiv + " aktiv)"
                + (p.fehler.isEmpty() ? "" : ", " + p.fehler.size() + " Fehler – siehe oben"));
        if (getHomeX().getLocation().isEmpty() && regeln.values().stream()
                .anyMatch(r -> r.def.ausloeser() instanceof SonnenEreignis)) {
            getLogger().info("Hinweis: Für Regeln mit Sonnenauf-/-untergang fehlt der Standort in homex.properties.");
        }
    }

    private void registrieren(Device g) {
        try {
            getHomeX().getDeviceRegistry().register(g, this);
        } catch (IllegalArgumentException e) {
            getLogger().warning(e.getMessage());
        }
    }

    Szene szeneFinden(String name) {
        SzeneGeraet g = szenen.get(Modell.slug(name));
        return g == null ? null : g.szene;
    }

    // =====================================================================
    // Szenen
    // =====================================================================

    private Ausfuehrer.Ergebnis szeneAusfuehren(SzeneGeraet g) {
        String wer = de.homex.api.device.ControlSource.current();
        Ausfuehrer.Ergebnis e = ausfuehrer.ausfuehren(g.szene.befehle(), "Szene " + g.szene.name());
        String zeile = "Szene '" + g.szene.name() + "'" + (wer == null ? "" : " ← " + wer) + ": " + e;
        if (e.allesOk()) getLogger().info(zeile);
        else getLogger().warning(zeile + (e.fehler().isEmpty() ? "" : " – nicht geklappt: " + String.join(", ", e.fehler())));
        return e;
    }

    // =====================================================================
    // Regeln prüfen
    // =====================================================================

    /** Regelmäßig: Zeit- und Sonnenregeln, Dauer-Bedingungen, Anzeige "nächste Ausführung". */
    private synchronized void pruefen() {
        LocalDateTime jetzt = LocalDateTime.now(zone);
        for (RegelGeraet r : regeln.values()) {
            try {
                switch (r.def.ausloeser()) {
                    case Zeit z -> zeitPruefen(r, jetzt, tag -> Optional.of(z.uhrzeit()), z.tage());
                    case SonnenEreignis s -> sonnePruefen(r, s, jetzt);
                    case Bedingung b -> bedingungPruefen(r, b, null, jetzt);
                }
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Regel '" + r.getName() + "' konnte nicht geprüft werden", e);
            }
        }
    }

    private void sonnePruefen(RegelGeraet r, SonnenEreignis s, LocalDateTime jetzt) {
        Optional<Location> ort = getHomeX().getLocation();
        if (ort.isEmpty()) {
            r.zustand("hinweis", "Standort fehlt in homex.properties");
            r.zustand("naechste", null);
            return;
        }
        Location o = ort.get();
        zeitPruefen(r, jetzt, tag -> (s.aufgang()
                ? Sonne.aufgang(tag, o.latitude(), o.longitude(), zone)
                : Sonne.untergang(tag, o.latitude(), o.longitude(), zone)).map(t -> t.plusMinutes(s.versatzMin())), s.tage());
    }

    private void zeitPruefen(RegelGeraet r, LocalDateTime jetzt,
                             java.util.function.Function<LocalDate, Optional<LocalTime>> zeitAm, java.util.Set<DayOfWeek> tage) {
        LocalDate heute = jetzt.toLocalDate();
        Optional<LocalTime> ziel = zeitAm.apply(heute);
        if (r.aktiv() && tage.contains(heute.getDayOfWeek()) && ziel.isPresent() && !heute.equals(r.zuletztAm)) {
            LocalDateTime zeitpunkt = heute.atTime(ziel.get());
            if (!jetzt.isBefore(zeitpunkt) && Duration.between(zeitpunkt, jetzt).toMinutes() < nachholenMin) {
                r.zuletztAm = heute;
                ausloesen(r, Modell.beschreibung(r.def.ausloeser()));
            }
        }
        r.zustand("hinweis", null);
        r.zustand("naechste", naechste(jetzt, zeitAm, tage, r));
    }

    /** "heute 06:30", "morgen 06:30" oder "Mo 06:30" */
    private String naechste(LocalDateTime jetzt, java.util.function.Function<LocalDate, Optional<LocalTime>> zeitAm,
                            java.util.Set<DayOfWeek> tage, RegelGeraet r) {
        for (int i = 0; i < 8; i++) {
            LocalDate tag = jetzt.toLocalDate().plusDays(i);
            if (!tage.contains(tag.getDayOfWeek())) continue;
            Optional<LocalTime> t = zeitAm.apply(tag);
            if (t.isEmpty()) continue;
            if (i == 0 && (!jetzt.toLocalTime().isBefore(t.get()) || tag.equals(r.zuletztAm))) continue;
            String wann = i == 0 ? "heute" : i == 1 ? "morgen"
                    : tag.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.GERMAN);
            return wann + " " + t.get();
        }
        return null;
    }

    /** Sofort reagieren, wenn sich ein Wert ändert, auf den eine Bedingung schaut. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void beiAenderung(DeviceStateChangeEvent e) {
        List<RegelGeraet> betroffen = new ArrayList<>();
        synchronized (this) {
            for (RegelGeraet r : regeln.values()) {
                if (r.def.ausloeser() instanceof Bedingung b && b.geraet().equals(e.getDevice().getId())
                        && b.key().equals(e.getKey())) betroffen.add(r);
            }
        }
        if (betroffen.isEmpty()) return;
        Object neu = e.getNewValue();
        // Das Event kommt, bevor der Wert gespeichert ist – also den neuen Wert direkt mitgeben
        getHomeX().getScheduler().runAsync(this, () -> {
            synchronized (this) {
                LocalDateTime jetzt = LocalDateTime.now(zone);
                for (RegelGeraet r : betroffen) bedingungPruefen(r, (Bedingung) r.def.ausloeser(), neu, jetzt);
            }
        });
    }

    private void bedingungPruefen(RegelGeraet r, Bedingung b, Object neuerWert, LocalDateTime jetzt) {
        if (!r.aktiv()) {
            r.zustand("hinweis", null);
            return;
        }
        Device d = getHomeX().getDeviceRegistry().get(b.geraet()).orElse(null);
        if (d == null) {
            r.zustand("hinweis", "Gerät '" + b.geraet() + "' ist (noch) nicht da");
            r.bedingungSeit = null;
            return;
        }
        Object wert = neuerWert != null ? neuerWert : d.getState(b.key());
        if (wert == null) {
            r.zustand("hinweis", d.getName() + " meldet noch keinen Wert '" + b.key() + "'");
            r.bedingungSeit = null;
            return;
        }
        if (!vergleichen(wert, b.op(), b.wert())) {
            r.bedingungSeit = null;
            r.bedingungAusgeloest = false;
            r.zustand("hinweis", "wartet – " + b.key() + " ist " + text(wert));
            return;
        }
        if (r.bedingungSeit == null) r.bedingungSeit = jetzt;
        long seitMin = Duration.between(r.bedingungSeit, jetzt).toMinutes();
        if (!r.bedingungAusgeloest && seitMin >= b.dauerMin()) {
            r.bedingungAusgeloest = true;
            ausloesen(r, Modell.beschreibung(b));
        }
        r.zustand("hinweis", r.bedingungAusgeloest ? "erfüllt – ausgelöst"
                : "erfüllt seit " + seitMin + " von " + b.dauerMin() + " min");
    }

    static boolean vergleichen(Object ist, String op, String soll) {
        String s = soll.trim().toLowerCase(Locale.ROOT);
        Boolean sollBool = switch (s) {
            case "an", "ein", "on", "true", "ja" -> true;
            case "aus", "off", "false", "nein" -> false;
            default -> null;
        };
        if (sollBool != null && ist instanceof Boolean b) {
            return switch (op) { case "=" -> b == sollBool; case "!=" -> b != sollBool; default -> false; };
        }
        Double a = zahl(ist), z = zahl(soll);
        if (a != null && z != null) {
            return switch (op) {
                case ">" -> a > z; case "<" -> a < z; case ">=" -> a >= z; case "<=" -> a <= z;
                case "=" -> a.equals(z); case "!=" -> !a.equals(z); default -> false;
            };
        }
        boolean gleich = String.valueOf(ist).equalsIgnoreCase(soll.trim());
        return switch (op) { case "=" -> gleich; case "!=" -> !gleich; default -> false; };
    }

    private static Double zahl(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o).trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    private static String text(Object o) {
        if (o instanceof Boolean b) return b ? "an" : "aus";
        return String.valueOf(o);
    }

    // =====================================================================
    // Auslösen
    // =====================================================================

    private void ausloesen(RegelGeraet r, String grund) {
        getHomeX().getScheduler().runAsync(this, () -> {
            Ausfuehrer.Ergebnis e = ausfuehrer.ausfuehren(r.aktionen(), "Regel " + r.getName());
            String zeile = "Regel '" + r.getName() + "' (" + grund + "): " + e;
            if (e.allesOk()) getLogger().info(zeile);
            else getLogger().warning(zeile + (e.fehler().isEmpty() ? "" : " – nicht geklappt: " + String.join(", ", e.fehler())));
            synchronized (this) {
                r.zustand("ergebnis", e.toString());
                r.zustand("zuletzt", LocalDateTime.now(zone).format(SzeneGeraet.ZEIT));
            }
        });
    }

    private synchronized void aktivGeaendert(RegelGeraet r, boolean an) {
        getLogger().info("Regel '" + r.getName() + "' ist jetzt " + (an ? "aktiv" : "pausiert")
                + (de.homex.api.device.ControlSource.current() == null ? "" : "   ← " + de.homex.api.device.ControlSource.current()));
        Properties p = status();
        p.setProperty("aktiv." + r.def.id(), String.valueOf(an));
        statusSpeichern(p);
        if (an) getHomeX().getScheduler().runAsync(this, this::pruefen);
    }

    private void statusSpeichern(Properties p) {
        try (var w = Files.newBufferedWriter(getDataFolder().resolve("regeln-status.properties"), StandardCharsets.UTF_8)) {
            p.store(w, "Welche Regeln aktiv sind (wird von HomeX gepflegt)");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Regel-Status nicht gespeichert", e);
        }
    }

    // =====================================================================
    // Bearbeiten aus dem Dashboard
    // =====================================================================

    /** Liefert null, wenn alles geklappt hat, sonst den Grund. */
    private synchronized String verwalten(String key, String wert) {
        String[] z = wert.replace("\r", "").split("\n", -1);
        String alt = z[0].trim();
        String wer = de.homex.api.device.ControlSource.current();
        String von = wer == null ? "" : "   ← " + wer;
        try {
            switch (key) {
                case "regel-loeschen" -> {
                    RegelGeraet g = regeln.get(alt);
                    if (g == null) return "Diese Regel gibt es nicht (mehr)";
                    regelnSchreiben(regeln.values().stream().filter(r -> r != g).map(r -> r.def).toList());
                    Properties p = status();
                    p.remove("aktiv." + alt);
                    statusSpeichern(p);
                    getLogger().info("Regel '" + g.getName() + "' gelöscht" + von);
                }
                case "szene-loeschen" -> {
                    SzeneGeraet g = szenen.get(alt);
                    if (g == null) return "Diese Szene gibt es nicht (mehr)";
                    szenenSchreiben(szenen.values().stream().filter(s -> s != g).map(s -> s.szene).toList());
                    getLogger().info("Szene '" + g.getName() + "' gelöscht" + von);
                }
                case "regel-speichern" -> {
                    if (z.length < 2 || z[1].isBlank()) return "Die Regel ist leer";
                    Parser p = new Parser();
                    List<Parser.RegelDef> neu = p.regeln(List.of("regel " + z[1].trim()));
                    if (!p.fehler.isEmpty()) return ohneZeile(p.fehler.getFirst());
                    Parser.RegelDef r = neu.getFirst();
                    if (!r.id().equals(alt) && regeln.containsKey(r.id())) return "Eine Regel „" + r.name() + "“ gibt es schon";
                    List<Parser.RegelDef> liste = new ArrayList<>();
                    boolean ersetzt = false;
                    for (RegelGeraet g : regeln.values()) {
                        if (g.def.id().equals(alt)) { liste.add(r); ersetzt = true; } else liste.add(g.def);
                    }
                    if (!ersetzt) liste.add(r);
                    regelnSchreiben(liste);
                    if (ersetzt && !alt.equals(r.id())) {
                        Properties st = status();
                        String a = st.getProperty("aktiv." + alt);
                        st.remove("aktiv." + alt);
                        if (a != null) st.setProperty("aktiv." + r.id(), a);
                        statusSpeichern(st);
                    }
                    getLogger().info("Regel '" + r.name() + "' " + (ersetzt ? "geändert" : "angelegt") + von);
                }
                case "szene-speichern" -> {
                    if (z.length < 2 || z[1].isBlank()) return "Die Szene ist leer";
                    List<String> zeilen = new ArrayList<>();
                    zeilen.add("szene " + z[1].trim());
                    for (int i = 2; i < z.length; i++) if (!z[i].isBlank()) zeilen.add("  " + z[i].trim());
                    Parser p = new Parser();
                    List<Szene> neu = p.szenen(zeilen);
                    if (!p.fehler.isEmpty()) return ohneZeile(p.fehler.getFirst());
                    if (neu.isEmpty()) return "Die Szene braucht einen Namen";
                    Szene s = neu.getFirst();
                    if (s.befehle().isEmpty()) return "Die Szene braucht mindestens eine Aktion";
                    if (!s.id().equals(alt) && szenen.containsKey(s.id())) return "Eine Szene „" + s.name() + "“ gibt es schon";
                    List<Szene> liste = new ArrayList<>();
                    boolean ersetzt = false;
                    for (SzeneGeraet g : szenen.values()) {
                        if (g.szene.id().equals(alt)) { liste.add(s); ersetzt = true; } else liste.add(g.szene);
                    }
                    if (!ersetzt) liste.add(s);
                    szenenSchreiben(liste);
                    getLogger().info("Szene '" + s.name() + "' " + (ersetzt ? "geändert" : "angelegt") + von);
                }
                default -> { return "Unbekannter Befehl " + key; }
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Speichern fehlgeschlagen", e);
            return "Konnte nicht gespeichert werden: " + e.getMessage();
        }
        laden();
        return null;
    }

    private static String ohneZeile(String fehler) {
        return fehler.replaceFirst("^\\S+\\.txt Zeile \\d+: ", "");
    }

    private void regelnSchreiben(List<Parser.RegelDef> liste) throws IOException {
        StringBuilder sb = new StringBuilder(kopf("regeln.txt"));
        for (Parser.RegelDef r : liste) {
            sb.append("regel ").append(r.name()).append(" | ").append(Modell.beschreibung(r.ausloeser())).append(" | ")
              .append(String.join("; ", r.aktionen().stream().map(Object::toString).toList())).append('\n');
        }
        schreiben("regeln.txt", sb.toString());
    }

    private void szenenSchreiben(List<Szene> liste) throws IOException {
        StringBuilder sb = new StringBuilder(kopf("szenen.txt"));
        for (Szene s : liste) {
            sb.append("szene ").append(s.name()).append(" | ").append(s.symbol()).append('\n');
            for (Befehl b : s.befehle()) sb.append("  ").append(b).append('\n');
            sb.append('\n');
        }
        schreiben("szenen.txt", sb.toString());
    }

    /** Die Erklärung oben in der Datei bleibt erhalten. */
    private String kopf(String datei) {
        StringBuilder sb = new StringBuilder();
        for (String z : zeilen(datei)) {
            if (!z.startsWith("#")) break;
            sb.append(z).append('\n');
        }
        return sb.append('\n').toString();
    }

    private void schreiben(String datei, String inhalt) throws IOException {
        Path ziel = getDataFolder().resolve(datei);
        Path tmp = getDataFolder().resolve(datei + ".neu");
        Files.writeString(tmp, inhalt, StandardCharsets.UTF_8);
        Files.move(tmp, ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Die Beispiele aus älteren Versionen (für Test-Geräte) entfernen – aber nur, wenn niemand die Datei geändert hat. */
    private void alteBeispieleErsetzen() {
        Map<String, String> alt = Map.of(
                "szenen.txt", "335f2de3ebe5b7dfef926b3a7720b11c86c1ddf87a023d75544209231842077e",
                "regeln.txt", "47471964f3c4e2c22108c9d2ceb2aeb3ac8d97456399c7af24e6f8a893761325");
        for (var e : alt.entrySet()) {
            Path ziel = getDataFolder().resolve(e.getKey());
            try {
                if (!Files.exists(ziel)) continue;
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ziel));
                if (!java.util.HexFormat.of().formatHex(hash).equals(e.getValue())) continue;
                try (InputStream in = getResource(e.getKey())) {
                    if (in != null) Files.copy(in, ziel, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                getLogger().info(e.getKey() + ": die alten Beispiele wurden entfernt.");
            } catch (IOException | java.security.NoSuchAlgorithmException ex) {
                getLogger().log(Level.WARNING, e.getKey() + " nicht geprüft", ex);
            }
        }
    }

    // =====================================================================
    // Konsole
    // =====================================================================

    private boolean befehlSzene(CommandSender s, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("Szenen:");
            for (SzeneGeraet g : szenen.values()) {
                s.sendMessage(String.format("  %-20s %d Befehl(e)%s", g.getName(), g.szene.befehle().size(),
                        g.getState("zuletzt") == null ? "" : "   zuletzt " + g.getState("zuletzt") + " – " + g.getState("ergebnis")));
            }
            s.sendMessage("Ausführen: szene <Name>");
            return true;
        }
        SzeneGeraet g = szenen.get(Modell.slug(String.join(" ", a)));
        if (g == null) { s.sendMessage("Szene '" + String.join(" ", a) + "' gibt es nicht. 'szene' zeigt alle."); return true; }
        g.control("ausfuehren", "1");
        return true;
    }

    private boolean befehlRegel(CommandSender s, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("Regeln:");
            for (RegelGeraet r : regeln.values()) {
                var st = r.getStates();
                s.sendMessage(String.format("  [%s] %-26s %s → %s", r.aktiv() ? "an " : "aus", r.getName(), st.get("wann"), st.get("was")));
                String info = (st.get("naechste") != null ? "nächste: " + st.get("naechste") + "  " : "")
                        + (st.get("zuletzt") != null ? "zuletzt: " + st.get("zuletzt") + " (" + st.get("ergebnis") + ")  " : "")
                        + (st.get("hinweis") != null ? st.get("hinweis") : "");
                if (!info.isBlank()) s.sendMessage("        " + info.trim());
            }
            s.sendMessage("Schalten: regel <Name> an|aus   –   sofort testen: regel <Name> test");
            return true;
        }
        if (a.length < 2) return false;
        String aktion = a[a.length - 1].toLowerCase(Locale.ROOT);
        String name = String.join(" ", java.util.Arrays.copyOf(a, a.length - 1));
        RegelGeraet r = regeln.get(Modell.slug(name));
        if (r == null) { s.sendMessage("Regel '" + name + "' gibt es nicht. 'regel' zeigt alle."); return true; }
        switch (aktion) {
            case "an", "aus" -> r.control("aktiv", aktion);
            case "test" -> { s.sendMessage("Führe '" + r.getName() + "' jetzt aus …"); r.control("ausfuehren", "1"); }
            default -> { return false; }
        }
        return true;
    }

    // =====================================================================
    // Dateien
    // =====================================================================

    private List<String> zeilen(String datei) {
        try {
            return Files.readAllLines(getDataFolder().resolve(datei), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning(datei + " nicht lesbar: " + e.getMessage());
            return List.of();
        }
    }

    private Properties status() {
        Properties p = new Properties();
        Path datei = getDataFolder().resolve("regeln-status.properties");
        if (Files.exists(datei)) {
            try (var r = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) { p.load(r); } catch (IOException ignored) {}
        }
        return p;
    }

    private void vorlageKopieren(String datei) {
        Path ziel = getDataFolder().resolve(datei);
        if (Files.exists(ziel)) return;
        try (InputStream in = getResource(datei)) {
            Files.createDirectories(getDataFolder());
            if (in != null) Files.copy(in, ziel);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, datei + " konnte nicht angelegt werden", e);
        }
    }

    private long zahl(String key, long standard) {
        try { return Long.parseLong(getConfig().getProperty(key, "" + standard).trim()); } catch (NumberFormatException e) { return standard; }
    }
}

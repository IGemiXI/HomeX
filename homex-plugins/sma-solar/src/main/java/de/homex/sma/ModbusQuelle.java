package de.homex.sma;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Liest den Sunny Tripower Smart Energy lokal über SMA Modbus (Unit-ID standardmäßig 3, Port 502).
 *
 * Registerliste (SMA-Modbus-Profil). SMA markiert "kein Wert" mit NaN-Mustern
 * (S32 = 0x80000000, U32 = 0xFFFFFFFF, U64 = 0xFFFFFFFFFFFFFFFF) – die werden zu {@code null}.
 */
final class ModbusQuelle implements SmaQuelle {

    // Register-Adressen (Typ und Einheit im Kommentar)
    private static final int STATUS        = 30201; // U32  Gerätestatus
    private static final int ERTRAG_GESAMT = 30513; // U64  Wh
    private static final int ERTRAG_HEUTE  = 30517; // U64  Wh
    private static final int DC_LEISTUNG_A = 30773; // S32  W  (Eingang A)
    private static final int AC_LEISTUNG   = 30775; // S32  W
    private static final int BATTERIE_SOC  = 30845; // U32  %
    private static final int NETZBEZUG     = 30865; // S32  W  (nur mit Energy Meter / Home Manager)
    private static final int EINSPEISUNG   = 30867; // S32  W  (nur mit Energy Meter / Home Manager)
    private static final int TEMPERATUR    = 30953; // S32  0,1 °C
    private static final int DC_LEISTUNG_B = 30961; // S32  W  (Eingang B)
    private static final int BAT_LADEN     = 31393; // U32  W
    private static final int BAT_ENTLADEN  = 31395; // U32  W

    /** Wie oft höchstens neu gesucht wird, wenn der Wechselrichter nicht gefunden wird. */
    private static final long SUCH_PAUSE_MS = 60_000;

    private final boolean automatisch;
    private final List<String> suchNetze;
    private final long seriennummer;
    private final int port, unitId, timeoutMs;
    private final Logger log;
    private final Consumer<String> gefunden;

    private volatile String host;
    private ModbusTcpClient client;
    private long letzteSuche = 0;

    /**
     * @param host       feste Adresse/Name, oder "auto" = im Netz suchen
     * @param gefunden   wird mit der IP aufgerufen, wenn die Suche den Wechselrichter gefunden hat
     */
    ModbusQuelle(String host, int port, int unitId, int timeoutMs,
                 List<String> suchNetze, long seriennummer, Logger log, Consumer<String> gefunden) {
        this.automatisch = host == null || host.isBlank() || host.equalsIgnoreCase("auto");
        this.host = automatisch ? null : host;
        this.port = port;
        this.unitId = unitId;
        this.timeoutMs = timeoutMs;
        this.suchNetze = suchNetze;
        this.seriennummer = seriennummer;
        this.log = log;
        this.gefunden = gefunden;
    }

    @Override
    public String name() { return automatisch ? "Modbus (automatisch)" : "Modbus"; }

    String host() { return host; }

    @Override
    public SolarData lesen() throws IOException {
        if (client == null) verbinden();
        try {
            return werteLesen();
        } catch (IOException e) {
            if (automatisch) {          // IP hat sich vielleicht geändert → beim nächsten Mal neu suchen
                client.close();
                client = null;
                host = null;
            }
            throw e;
        }
    }

    private void verbinden() throws IOException {
        if (host == null) {
            long jetzt = System.currentTimeMillis();
            if (jetzt - letzteSuche < SUCH_PAUSE_MS)
                throw new IOException("Wechselrichter nicht gefunden – nächste Suche in "
                        + (SUCH_PAUSE_MS - (jetzt - letzteSuche)) / 1000 + " s");
            letzteSuche = jetzt;
            var fund = WechselrichterSuche.suchen(suchNetze, port, unitId, seriennummer, log);
            if (fund.isEmpty())
                throw new IOException("Wechselrichter im Netz nicht gefunden – ist der Modbus-TCP-Server eingeschaltet?");
            host = fund.get().host();
            log.info("Wechselrichter gefunden: " + host + " (Seriennummer " + fund.get().seriennummer() + ")");
            gefunden.accept(host);
        }
        client = new ModbusTcpClient(host, port, timeoutMs);
    }

    private SolarData werteLesen() throws IOException {
        // Zuerst ein Pflicht-Register: schlägt das fehl, ist der Wechselrichter nicht erreichbar.
        Double ac = s32(AC_LEISTUNG);
        if (ac == null) ac = 0d;   // nachts liefert SMA NaN → 0 W

        Double dcA = s32(DC_LEISTUNG_A), dcB = s32(DC_LEISTUNG_B);
        Double pv = (dcA == null && dcB == null) ? null : nz(dcA) + nz(dcB);

        Double bezug = s32(NETZBEZUG);
        Double einsp = s32(EINSPEISUNG);
        Double verbrauch = (bezug != null && einsp != null) ? Math.max(0, ac + bezug - einsp) : null;

        Double temp = s32(TEMPERATUR);
        Double gesamt = u64(ERTRAG_GESAMT);
        Double heute = u64(ERTRAG_HEUTE);

        return new SolarData(
                pv, ac, einsp, bezug, verbrauch,
                u32(BATTERIE_SOC), u32(BAT_LADEN), u32(BAT_ENTLADEN),
                heute == null ? null : runden(heute / 1000.0, 2),
                gesamt == null ? null : runden(gesamt / 1000.0, 1),
                temp == null ? null : runden(temp / 10.0, 1),
                status(u32(STATUS)),
                null);
    }

    private static String status(Double code) {
        if (code == null) return "UNBEKANNT";
        return switch (code.intValue()) {
            case 307 -> "OK";
            case 455 -> "WARNUNG";
            case 35 -> "FEHLER";
            case 303 -> "AUS";
            case 381 -> "STOPP";
            default -> "CODE_" + code.intValue();
        };
    }

    // ---------------------------- Register lesen ----------------------------

    private int[] lesen(int addr, int count) throws IOException {
        try {
            return client.readHoldingRegisters(unitId, addr, count);
        } catch (ModbusTcpClient.ModbusException e) {
            return null;   // Register gibt es auf diesem Gerät nicht (z. B. keine Batterie)
        }
    }

    private Double s32(int addr) throws IOException {
        int[] r = lesen(addr, 2);
        if (r == null) return null;
        int v = (r[0] << 16) | r[1];
        return v == Integer.MIN_VALUE ? null : (double) v;
    }

    private Double u32(int addr) throws IOException {
        int[] r = lesen(addr, 2);
        if (r == null) return null;
        long v = ((long) r[0] << 16) | r[1];
        return v == 0xFFFFFFFFL ? null : (double) v;
    }

    private Double u64(int addr) throws IOException {
        int[] r = lesen(addr, 4);
        if (r == null) return null;
        long v = ((long) r[0] << 48) | ((long) r[1] << 32) | ((long) r[2] << 16) | r[3];
        return v == -1L ? null : (double) v;
    }

    private static double nz(Double d) { return d == null ? 0 : d; }

    static double runden(double v, int stellen) {
        double f = Math.pow(10, stellen);
        return Math.round(v * f) / f;
    }

    @Override
    public void close() { if (client != null) client.close(); }
}

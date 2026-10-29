package de.homex.sma;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Sucht den SMA-Wechselrichter im Heimnetz – für Router, die keine festen IPs vergeben können
 * (z. B. der Starlink-Router).
 *
 * Vorgehen: Alle Adressen im eigenen Netz (x.x.x.1 – x.x.x.254) werden gleichzeitig auf dem Modbus-Port
 * angefragt. Ein Gerät zählt als SMA-Wechselrichter, wenn es mit der SMA-Unit-ID auf das
 * Seriennummer-Register (30057) antwortet. Ist eine Seriennummer konfiguriert, muss sie passen.
 */
final class WechselrichterSuche {

    static final int SERIENNUMMER = 30057;   // U32

    record Fund(String host, long seriennummer) {}

    private WechselrichterSuche() {}

    /**
     * @param netze      z. B. ["192.168.1"]; leer = alle eigenen IPv4-Netze automatisch
     * @param seriennr   0 = egal, sonst muss die Seriennummer passen
     */
    static Optional<Fund> suchen(List<String> netze, int port, int unitId, long seriennr, Logger log) {
        List<String> ziele = new ArrayList<>();
        for (String netz : netze.isEmpty() ? eigeneNetze() : netze) {
            for (int i = 1; i <= 254; i++) ziele.add(netz + "." + i);
        }
        if (ziele.isEmpty()) return Optional.empty();
        long start = System.currentTimeMillis();
        log.info("Suche Wechselrichter in " + (netze.isEmpty() ? eigeneNetze() : netze) + ".x (Port " + port + ") …");

        // 64 normale Threads: schnell genug (254 Adressen in wenigen Sekunden) und auf jeder Java-Version
        // zuverlässig – virtuelle Threads blockieren unter Java 21 bei synchronized-Netzwerkzugriffen.
        try (ExecutorService pool = Executors.newFixedThreadPool(64, r -> {
            Thread t = new Thread(r, "HomeX-Wechselrichtersuche");
            t.setDaemon(true);
            return t;
        })) {
            List<Future<Fund>> laeufe = new ArrayList<>();
            for (String host : ziele) laeufe.add(pool.submit(() -> pruefen(host, port, unitId)));
            List<Fund> funde = new ArrayList<>();
            for (Future<Fund> f : laeufe) {
                try {
                    Fund fund = f.get();
                    if (fund != null) funde.add(fund);
                } catch (Exception ignored) {}
            }
            long dauer = System.currentTimeMillis() - start;
            log.info("Suche fertig nach " + dauer + " ms, " + funde.size() + " SMA-Gerät(e) gefunden.");
            for (Fund f : funde) log.info("  " + f.host() + " (Seriennummer " + f.seriennummer() + ")");
            Optional<Fund> treffer = funde.stream()
                    .filter(f -> seriennr == 0 || f.seriennummer() == seriennr)
                    .findFirst();
            if (treffer.isEmpty() && !funde.isEmpty())
                log.warning("SMA-Gerät(e) gefunden, aber keins mit der Seriennummer " + seriennr
                        + " aus modbus.seriennummer – Nummer auf dem Typenschild prüfen oder Feld leer lassen.");
            return treffer;
        }
    }

    /** Antwortet unter dieser Adresse ein SMA-Gerät? */
    private static Fund pruefen(String host, int port, int unitId) {
        try (ModbusTcpClient c = new ModbusTcpClient(host, port, 600)) {
            int[] r = c.readHoldingRegisters(unitId, SERIENNUMMER, 2);
            long sn = ((long) r[0] << 16) | r[1];
            return sn == 0 || sn == 0xFFFFFFFFL ? null : new Fund(host, sn);
        } catch (IOException e) {
            return null;   // kein Gerät, Port zu oder kein SMA
        }
    }

    /** Die ersten drei Zahlen jeder eigenen privaten IPv4-Adresse, z. B. "192.168.1". */
    static List<String> eigeneNetze() {
        Set<String> netze = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        netze.add(ip.substring(0, ip.lastIndexOf('.')));
                    }
                }
            }
        } catch (IOException ignored) {}
        return new ArrayList<>(netze);
    }
}

package de.homex.automation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Sonnenauf- und -untergang für einen Ort – ohne Internet berechnet
 * (Verfahren aus dem "Almanac for Computers" des US Naval Observatory, Genauigkeit ±1–2 Minuten).
 */
final class Sonne {

    private static final double ZENIT = 90.833;   // offizieller Auf-/Untergang (Refraktion + Sonnenradius)

    private Sonne() {}

    static Optional<LocalTime> aufgang(LocalDate tag, double breite, double laenge, ZoneId zone) {
        return berechnen(tag, breite, laenge, zone, true);
    }

    static Optional<LocalTime> untergang(LocalDate tag, double breite, double laenge, ZoneId zone) {
        return berechnen(tag, breite, laenge, zone, false);
    }

    /** @return leer, wenn die Sonne an diesem Tag nicht auf- bzw. untergeht (Polartag/-nacht) */
    private static Optional<LocalTime> berechnen(LocalDate tag, double breite, double laenge, ZoneId zone, boolean aufgang) {
        int n = tag.getDayOfYear();
        double lngStunde = laenge / 15.0;
        double t = n + ((aufgang ? 6 : 18) - lngStunde) / 24.0;

        double m = 0.9856 * t - 3.289;                                       // mittlere Anomalie
        double l = norm360(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634); // wahre Länge

        double ra = norm360(Math.toDegrees(Math.atan(0.91764 * tan(l))));     // Rektaszension
        ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90;
        ra /= 15.0;

        double sinDek = 0.39782 * sin(l);
        double cosDek = Math.cos(Math.asin(sinDek));
        double cosH = (cos(ZENIT) - sinDek * sin(breite)) / (cosDek * cos(breite));
        if (cosH > 1 || cosH < -1) return Optional.empty();

        double h = (aufgang ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH))) / 15.0;
        double lokal = h + ra - 0.06571 * t - 6.622;
        double utc = ((lokal - lngStunde) % 24 + 24) % 24;

        int sekunden = (int) Math.round(utc * 3600) % 86400;
        ZonedDateTime z = tag.atStartOfDay(ZoneOffset.UTC).plusSeconds(sekunden).withZoneSameInstant(zone);
        return Optional.of(z.toLocalTime().withSecond(0).withNano(0));
    }

    private static double sin(double grad) { return Math.sin(Math.toRadians(grad)); }
    private static double cos(double grad) { return Math.cos(Math.toRadians(grad)); }
    private static double tan(double grad) { return Math.tan(Math.toRadians(grad)); }
    private static double norm360(double g) { return ((g % 360) + 360) % 360; }
}

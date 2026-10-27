package de.homex.sma;

/**
 * Ein Messwert-Paket der Solaranlage. Jeder Wert darf {@code null} sein,
 * wenn der Wechselrichter/die Cloud ihn nicht liefert (z. B. keine Batterie).
 */
public record SolarData(
        Double pvLeistungW,        // was die Module gerade liefern (DC)
        Double acLeistungW,        // was der Wechselrichter ins Haus abgibt (AC)
        Double einspeisungW,       // ins Netz
        Double netzbezugW,         // aus dem Netz
        Double verbrauchW,         // Hausverbrauch
        Double batterieSoc,        // Ladestand in %
        Double batterieLadenW,
        Double batterieEntladenW,
        Double ertragHeuteKwh,
        Double ertragGesamtKwh,
        Double temperaturC,
        String status,             // OK, WARNUNG, FEHLER, AUS, OFFLINE ...
        String zeitpunkt           // Zeitstempel der Messung (bei Cloud von SMA)
) {}

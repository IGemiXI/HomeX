package de.homex.wetter;

/** WMO-Wettercodes (wie Open-Meteo sie liefert) → deutscher Text und Symbol für das Dashboard. */
final class WetterCodes {

    private WetterCodes() {}

    static String text(int code) {
        return switch (code) {
            case 0 -> "Klar";
            case 1 -> "Überwiegend klar";
            case 2 -> "Teilweise bewölkt";
            case 3 -> "Bedeckt";
            case 45, 48 -> "Nebel";
            case 51, 53, 55 -> "Nieselregen";
            case 56, 57 -> "Gefrierender Niesel";
            case 61 -> "Leichter Regen";
            case 63 -> "Regen";
            case 65 -> "Starker Regen";
            case 66, 67 -> "Gefrierender Regen";
            case 71 -> "Leichter Schneefall";
            case 73 -> "Schneefall";
            case 75 -> "Starker Schneefall";
            case 77 -> "Schneegriesel";
            case 80, 81 -> "Regenschauer";
            case 82 -> "Heftige Regenschauer";
            case 85, 86 -> "Schneeschauer";
            case 95 -> "Gewitter";
            case 96, 99 -> "Gewitter mit Hagel";
            default -> "Unbekannt";
        };
    }

    /** Symbolname für das Dashboard: klar, klar-nacht, wolkig, wolkig-nacht, bedeckt, nebel, regen, schnee, gewitter */
    static String symbol(int code, boolean tag) {
        if (code == 0) return tag ? "klar" : "klar-nacht";
        if (code == 1 || code == 2) return tag ? "wolkig" : "wolkig-nacht";
        if (code == 3) return "bedeckt";
        if (code == 45 || code == 48) return "nebel";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "schnee";
        if (code >= 95) return "gewitter";
        if (code >= 51) return "regen";
        return "bedeckt";
    }
}

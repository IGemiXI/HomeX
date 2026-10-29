package de.homex.api;

/** Standort des Hauses in Grad (z. B. für Wetter und Sonnenauf-/-untergang). */
public record Location(double latitude, double longitude) {
    public Location {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Ungültiger Standort: " + latitude + ", " + longitude);
    }
}

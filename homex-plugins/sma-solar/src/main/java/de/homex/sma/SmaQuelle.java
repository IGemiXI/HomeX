package de.homex.sma;

/** Eine Datenquelle für die Solaranlage (Modbus lokal oder SMA Cloud). */
public interface SmaQuelle extends AutoCloseable {

    String name();

    /** Liest die aktuellen Werte. Wirft eine Exception, wenn die Anlage nicht erreichbar ist. */
    SolarData lesen() throws Exception;

    @Override
    default void close() {}
}

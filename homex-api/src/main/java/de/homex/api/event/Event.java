package de.homex.api.event;

/** Basisklasse aller Events. */
public abstract class Event {
    public String getEventName() {
        return getClass().getSimpleName();
    }
}

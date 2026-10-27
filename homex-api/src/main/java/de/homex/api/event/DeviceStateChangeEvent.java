package de.homex.api.event;

import de.homex.api.device.Device;

/**
 * Wird ausgelöst, bevor sich ein Zustand eines Geräts ändert
 * (z. B. Klima "power" false → true). Kann abgebrochen werden.
 */
public class DeviceStateChangeEvent extends Event implements Cancellable {
    private final Device device;
    private final String key;
    private final Object oldValue;
    private Object newValue;
    private boolean cancelled;

    public DeviceStateChangeEvent(Device device, String key, Object oldValue, Object newValue) {
        this.device = device;
        this.key = key;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Device getDevice() { return device; }
    public String getKey() { return key; }
    public Object getOldValue() { return oldValue; }
    public Object getNewValue() { return newValue; }
    /** Listener dürfen den neuen Wert verändern (z. B. Temperatur begrenzen). */
    public void setNewValue(Object newValue) { this.newValue = newValue; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}

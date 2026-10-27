package de.homex.api.device;

import de.homex.api.event.DeviceStateChangeEvent;
import de.homex.api.event.EventBus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein Gerät im Smarthome (Licht, Klima, Wechselrichter, Sensor ...).
 * Plugins erben hiervon und implementieren {@link #control(String, String)}.
 *
 * Zustände sind einfache Schlüssel/Wert-Paare, z. B. {@code power=true}, {@code mode=COOL}, {@code watt=3450}.
 */
public abstract class Device {

    private final String id;
    private final String name;
    private final DeviceType type;
    private final Map<String, Object> state = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile EventBus eventBus;

    protected Device(String id, String name, DeviceType type) {
        if (id == null || !id.matches("[a-z0-9_-]+"))
            throw new IllegalArgumentException("Geräte-ID nur aus a-z, 0-9, _ und - : " + id);
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public final String getId() { return id; }
    public final String getName() { return name; }
    public final DeviceType getType() { return type; }

    public final Object getState(String key) { return state.get(key); }

    public final Map<String, Object> getStates() {
        synchronized (state) { return Map.copyOf(state); }
    }

    /**
     * Jemand (Konsole, Automation, Web-UI) möchte etwas am Gerät ändern.
     * Das Plugin schickt den Befehl an die echte Hardware und ruft danach {@link #updateState}.
     *
     * @return false, wenn der Schlüssel/Wert nicht unterstützt wird
     */
    public abstract boolean control(String key, String value);

    /**
     * Speichert einen neuen Zustand und löst {@link DeviceStateChangeEvent} aus.
     * @return false, wenn ein Listener die Änderung abgebrochen hat
     */
    protected final boolean updateState(String key, Object value) {
        Object old = state.get(key);
        if (value != null && value.equals(old)) return true;
        EventBus bus = eventBus;
        if (bus != null) {
            DeviceStateChangeEvent ev = bus.callEvent(new DeviceStateChangeEvent(this, key, old, value));
            if (ev.isCancelled()) return false;
            value = ev.getNewValue();
        }
        state.put(key, value);
        return true;
    }

    /** Intern: wird von der DeviceRegistry beim Registrieren gesetzt. */
    public final void attach(EventBus bus) { this.eventBus = bus; }

    @Override
    public String toString() {
        return id + " (" + name + ", " + type + ") " + getStates();
    }
}

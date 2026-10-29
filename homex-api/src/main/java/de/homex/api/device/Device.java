package de.homex.api.device;

import de.homex.api.event.DeviceStateChangeEvent;
import de.homex.api.event.EventBus;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ein Gerät im Smarthome (Licht, Klima, Wechselrichter, Sensor ...).
 * Plugins erben hiervon und implementieren {@link #control(String, String)}.
 *
 * Zustände sind einfache Schlüssel/Wert-Paare, z. B. {@code power=true}, {@code modus=kuehlen}, {@code watt=3450}.
 * Ein Gerät kann einem Raum zugeordnet sein ({@link #getRoom()}), damit Oberflächen es gruppieren können.
 */
public abstract class Device {

    private final String id;
    private final String name;
    private final DeviceType type;
    private volatile String room;
    private final Map<String, Object> state = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile EventBus eventBus;
    private volatile Instant lastChanged;
    private volatile String lastSource;

    protected Device(String id, String name, DeviceType type) {
        this(id, name, type, null);
    }

    /** @param room Raum, z. B. "Wohnzimmer" – oder {@code null}, wenn das Gerät keinem Raum gehört */
    protected Device(String id, String name, DeviceType type, String room) {
        if (id == null || !id.matches("[a-z0-9_-]+"))
            throw new IllegalArgumentException("Geräte-ID nur aus a-z, 0-9, _ und - : " + id);
        this.id = id;
        this.name = name;
        this.type = type;
        setRoom(room);
    }

    public final String getId() { return id; }
    public final String getName() { return name; }
    public final DeviceType getType() { return type; }

    /** Raum des Geräts oder {@code null}. */
    public final String getRoom() { return room; }

    public final void setRoom(String room) {
        this.room = room == null || room.isBlank() ? null : room.trim();
    }

    public final Object getState(String key) { return state.get(key); }

    public final Map<String, Object> getStates() {
        synchronized (state) { return Map.copyOf(state); }
    }

    /** Wann sich zuletzt ein Zustand geändert hat (oder {@code null}). */
    public final Instant getLastChanged() { return lastChanged; }

    /** Wer die letzte Änderung ausgelöst hat (siehe {@link ControlSource}), oder {@code null}. */
    public final String getLastSource() { return lastSource; }

    /**
     * Jemand (Konsole, Automation, Web-UI) möchte etwas am Gerät ändern.
     * Das Plugin schickt den Befehl an die echte Hardware und ruft danach {@link #updateState}.
     * Wer den Befehl gegeben hat, steht in {@link ControlSource#current()}.
     *
     * @return false, wenn der Schlüssel/Wert nicht unterstützt wird oder das Gerät nicht erreichbar ist
     */
    public abstract boolean control(String key, String value);

    /**
     * Speichert einen neuen Zustand und löst {@link DeviceStateChangeEvent} aus.
     * @return false, wenn ein Listener die Änderung abgebrochen hat
     */
    protected final boolean updateState(String key, Object value) {
        Object old = state.get(key);
        if (value != null && value.equals(old)) return true;
        String source = ControlSource.current();
        EventBus bus = eventBus;
        if (bus != null) {
            DeviceStateChangeEvent ev = bus.callEvent(new DeviceStateChangeEvent(this, key, old, value, source));
            if (ev.isCancelled()) return false;
            value = ev.getNewValue();
        }
        state.put(key, value);
        lastChanged = Instant.now();
        lastSource = source;
        return true;
    }

    /** Entfernt einen Zustand (z. B. eine Fehlermeldung, die nicht mehr gilt). */
    protected final void removeState(String key) {
        state.remove(key);
    }

    /** Intern: wird von der DeviceRegistry beim Registrieren gesetzt. */
    public final void attach(EventBus bus) { this.eventBus = bus; }

    @Override
    public String toString() {
        return id + " (" + name + ", " + type + (room == null ? "" : ", " + room) + ") " + getStates();
    }
}

package de.homex.core;

import de.homex.api.device.Device;
import de.homex.api.device.DeviceRegistry;
import de.homex.api.device.DeviceType;
import de.homex.api.event.EventBus;
import de.homex.api.plugin.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class SimpleDeviceRegistry implements DeviceRegistry {

    private record Entry(Device device, JavaPlugin owner) {}

    private final Map<String, Entry> devices = new ConcurrentHashMap<>();
    private final EventBus bus;

    SimpleDeviceRegistry(EventBus bus) { this.bus = bus; }

    @Override
    public void register(Device device, JavaPlugin owner) {
        Entry existing = devices.putIfAbsent(device.getId(), new Entry(device, owner));
        if (existing != null)
            throw new IllegalArgumentException("Geräte-ID '" + device.getId() + "' ist schon vergeben (von "
                    + (existing.owner() == null ? "HomeX" : existing.owner().getName()) + ")");
        device.attach(bus);
    }

    @Override
    public void unregister(String deviceId) {
        Entry e = devices.remove(deviceId);
        if (e != null) e.device().attach(null);
    }

    @Override
    public void unregisterAll(JavaPlugin owner) {
        devices.values().removeIf(e -> {
            if (e.owner() != owner) return false;
            e.device().attach(null);
            return true;
        });
    }

    @Override
    public Optional<Device> get(String deviceId) {
        Entry e = devices.get(deviceId);
        return e == null ? Optional.empty() : Optional.of(e.device());
    }

    @Override
    public Collection<Device> getAll() {
        return devices.values().stream().map(Entry::device)
                .sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
    }

    @Override
    public Collection<Device> getByType(DeviceType type) {
        return getAll().stream().filter(d -> d.getType() == type).toList();
    }

    String ownerName(Device d) {
        Entry e = devices.get(d.getId());
        return e == null || e.owner() == null ? "HomeX" : e.owner().getName();
    }

    List<Device> snapshot() { return List.copyOf(getAll()); }
}

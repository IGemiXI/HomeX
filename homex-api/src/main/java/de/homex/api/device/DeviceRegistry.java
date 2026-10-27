package de.homex.api.device;

import de.homex.api.plugin.JavaPlugin;

import java.util.Collection;
import java.util.Optional;

public interface DeviceRegistry {

    void register(Device device, JavaPlugin owner);

    void unregister(String deviceId);

    /** Entfernt alle Geräte eines Plugins (passiert beim Deaktivieren automatisch). */
    void unregisterAll(JavaPlugin owner);

    Optional<Device> get(String deviceId);

    Collection<Device> getAll();

    Collection<Device> getByType(DeviceType type);
}

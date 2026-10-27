package de.homex.api.event;

import de.homex.api.device.Device;
import de.homex.api.plugin.JavaPlugin;

/** Ein Gerät wurde in der DeviceRegistry hinzugefügt. */
public class DeviceRegisterEvent extends Event {
    private final Device device;
    private final JavaPlugin owner;

    public DeviceRegisterEvent(Device device, JavaPlugin owner) {
        this.device = device;
        this.owner = owner;
    }

    public Device getDevice() { return device; }

    /** Das Plugin, dem das Gerät gehört (null = HomeX selbst). */
    public JavaPlugin getOwner() { return owner; }
}

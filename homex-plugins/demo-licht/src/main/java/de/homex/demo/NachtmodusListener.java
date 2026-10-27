package de.homex.demo;

import de.homex.api.device.DeviceType;
import de.homex.api.event.DeviceStateChangeEvent;
import de.homex.api.event.EventHandler;
import de.homex.api.event.EventPriority;
import de.homex.api.event.Listener;

/** Zeigt, wie ein Plugin auf Events reagiert und sie sogar verändern kann. */
public class NachtmodusListener implements Listener {

    private final DemoLichtPlugin plugin;
    private final int maxHelligkeit;

    public NachtmodusListener(DemoLichtPlugin plugin, int maxHelligkeit) {
        this.plugin = plugin;
        this.maxHelligkeit = maxHelligkeit;
    }

    /** Im Nachtmodus wird die Helligkeit begrenzt. */
    @EventHandler(priority = EventPriority.HIGH)
    public void beiHelligkeit(DeviceStateChangeEvent e) {
        if (!plugin.isNachtmodus() || e.getDevice().getType() != DeviceType.LIGHT) return;
        if (e.getKey().equals("helligkeit") && e.getNewValue() instanceof Integer v && v > maxHelligkeit) {
            e.setNewValue(maxHelligkeit);
            plugin.getLogger().info("Nachtmodus: " + e.getDevice().getName() + " auf " + maxHelligkeit + " % begrenzt");
        }
    }

    /** MONITOR = nur zuschauen. Hier: Schaltvorgänge von Lampen loggen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void loggen(DeviceStateChangeEvent e) {
        if (e.getDevice().getType() != DeviceType.LIGHT || e.getOldValue() == null) return;
        plugin.getLogger().info(e.getDevice().getName() + ": " + e.getKey() + " " + e.getOldValue() + " → " + e.getNewValue());
    }
}

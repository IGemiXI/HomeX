package de.homex.api.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert eine Methode als Event-Empfänger. Die Methode braucht genau einen Parameter,
 * der von {@link Event} erbt:
 * <pre>
 * &#64;EventHandler
 * public void onChange(DeviceStateChangeEvent e) { ... }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventHandler {
    EventPriority priority() default EventPriority.NORMAL;

    /** true = wird nicht aufgerufen, wenn das Event schon abgebrochen wurde. */
    boolean ignoreCancelled() default false;
}

package de.homex.core;

import de.homex.api.event.Cancellable;
import de.homex.api.event.Event;
import de.homex.api.event.EventBus;
import de.homex.api.event.EventHandler;
import de.homex.api.event.EventPriority;
import de.homex.api.event.Listener;
import de.homex.api.plugin.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SimpleEventBus implements EventBus {

    private record Handler(JavaPlugin owner, Listener listener, Method method,
                           Class<? extends Event> eventType, EventPriority priority, boolean ignoreCancelled) {}

    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    private final Logger log;

    SimpleEventBus(Logger log) { this.log = log; }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void registerListener(Listener listener, JavaPlugin owner) {
        if (owner != null && !owner.isEnabled())
            throw new IllegalStateException(owner.getName() + " ist nicht aktiv");
        List<Handler> found = new ArrayList<>();
        for (Method m : listener.getClass().getMethods()) {
            EventHandler ann = m.getAnnotation(EventHandler.class);
            if (ann == null) continue;
            if (m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                log.warning("@EventHandler " + m + " braucht genau einen Event-Parameter – ignoriert");
                continue;
            }
            m.setAccessible(true);
            found.add(new Handler(owner, listener, m, (Class<? extends Event>) m.getParameterTypes()[0],
                    ann.priority(), ann.ignoreCancelled()));
        }
        List<Handler> all = new ArrayList<>(handlers);
        all.addAll(found);
        all.sort(Comparator.comparing(Handler::priority));   // stabile Sortierung
        handlers.clear();
        handlers.addAll(all);
    }

    @Override
    public synchronized void unregisterAll(JavaPlugin owner) {
        handlers.removeIf(h -> h.owner() == owner);
    }

    @Override
    public <T extends Event> T callEvent(T event) {
        for (Handler h : handlers) {
            if (!h.eventType().isInstance(event)) continue;
            if (h.ignoreCancelled() && event instanceof Cancellable c && c.isCancelled()) continue;
            try {
                h.method().invoke(h.listener(), event);
            } catch (InvocationTargetException e) {
                String who = h.owner() == null ? "HomeX" : h.owner().getName();
                log.log(Level.SEVERE, "Fehler in Listener von " + who + " bei " + event.getEventName(), e.getCause());
            } catch (IllegalAccessException e) {
                log.log(Level.SEVERE, "Listener nicht aufrufbar: " + h.method(), e);
            }
        }
        return event;
    }
}

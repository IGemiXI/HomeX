package de.homex.api.event;

/** Events, die ein Listener abbrechen kann (z. B. "Klima darf nicht eingeschaltet werden"). */
public interface Cancellable {
    boolean isCancelled();
    void setCancelled(boolean cancelled);
}

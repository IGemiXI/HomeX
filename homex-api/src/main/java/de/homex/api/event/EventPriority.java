package de.homex.api.event;

/** Reihenfolge der Listener: LOWEST zuerst, MONITOR zuletzt (MONITOR nur beobachten, nichts ändern). */
public enum EventPriority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }

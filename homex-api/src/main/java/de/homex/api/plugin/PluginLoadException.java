package de.homex.api.plugin;

public class PluginLoadException extends Exception {
    private static final long serialVersionUID = 1L;

    public PluginLoadException(String message) { super(message); }
    public PluginLoadException(String message, Throwable cause) { super(message, cause); }
}

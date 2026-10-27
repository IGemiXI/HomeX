package de.homex.api.plugin;

import java.util.List;

/**
 * Inhalt der {@code plugin.yml}, die in jeder Plugin-JAR liegen muss.
 *
 * <pre>
 * name: DemoLicht
 * version: 1.0.0
 * main: de.homex.demo.DemoLichtPlugin
 * author: Dennis
 * description: Ein Beispiel-Plugin
 * depend: [AnderesPlugin]
 * softdepend: [OptionalesPlugin]
 * </pre>
 */
public record PluginDescription(
        String name,
        String version,
        String main,
        String author,
        String description,
        List<String> depend,
        List<String> softdepend
) {
    public PluginDescription {
        depend = depend == null ? List.of() : List.copyOf(depend);
        softdepend = softdepend == null ? List.of() : List.copyOf(softdepend);
    }

    public String fullName() {
        return name + " v" + version;
    }
}

package de.homex.core;

import de.homex.api.plugin.PluginDescription;
import de.homex.api.plugin.PluginLoadException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest eine einfache plugin.yml ohne externe Bibliothek.
 * Unterstützt:  {@code key: wert},  {@code key: [a, b]}  und Listen mit {@code - eintrag}.
 */
final class DescriptionParser {

    private DescriptionParser() {}

    static PluginDescription parse(InputStream in, String source) throws PluginLoadException {
        Map<String, Object> values = new HashMap<>();
        String listKey = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = stripComment(line).trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.startsWith("- ") && listKey != null) {      // Listeneintrag
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) values.get(listKey);
                    list.add(unquote(trimmed.substring(2).trim()));
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) throw new PluginLoadException(source + ": ungültige Zeile in plugin.yml: " + line);

                String key = trimmed.substring(0, colon).trim().toLowerCase();
                String value = trimmed.substring(colon + 1).trim();
                listKey = null;

                if (value.isEmpty()) {                                 // Liste folgt in den nächsten Zeilen
                    values.put(key, new ArrayList<String>());
                    listKey = key;
                } else if (value.startsWith("[") && value.endsWith("]")) {
                    List<String> list = new ArrayList<>();
                    for (String part : value.substring(1, value.length() - 1).split(",")) {
                        if (!part.isBlank()) list.add(unquote(part.trim()));
                    }
                    values.put(key, list);
                } else {
                    values.put(key, unquote(value));
                }
            }
        } catch (IOException e) {
            throw new PluginLoadException(source + ": plugin.yml nicht lesbar", e);
        }

        String name = required(values, "name", source);
        String version = required(values, "version", source);
        String main = required(values, "main", source);
        if (!name.matches("[A-Za-z0-9_-]+"))
            throw new PluginLoadException(source + ": Plugin-Name darf nur A-Z, 0-9, _ und - enthalten: " + name);

        return new PluginDescription(name, version, main,
                str(values.get("author")), str(values.get("description")),
                list(values.get("depend")), list(values.get("softdepend")));
    }

    private static String required(Map<String, Object> v, String key, String source) throws PluginLoadException {
        Object o = v.get(key);
        if (!(o instanceof String s) || s.isBlank())
            throw new PluginLoadException(source + ": plugin.yml fehlt Pflichtfeld '" + key + "'");
        return s;
    }

    private static String str(Object o) { return o instanceof String s ? s : ""; }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        if (o instanceof List<?> l) return (List<String>) l;
        if (o instanceof String s && !s.isBlank()) return List.of(s);
        return List.of();
    }

    private static String stripComment(String line) {
        int hash = line.indexOf(" #");
        if (line.trim().startsWith("#")) return "";
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))))
            return s.substring(1, s.length() - 1);
        return s;
    }
}

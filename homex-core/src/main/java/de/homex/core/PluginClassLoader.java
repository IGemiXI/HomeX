package de.homex.core;

import de.homex.api.plugin.PluginDescription;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Jedes Plugin bekommt einen eigenen ClassLoader. Findet er eine Klasse nicht selbst,
 * fragt er die anderen Plugins (so kann ein Plugin die API eines anderen benutzen, auf das es "depend"et).
 */
final class PluginClassLoader extends URLClassLoader {

    static { ClassLoader.registerAsParallelCapable(); }

    private final SimplePluginManager manager;
    final Path jarFile;
    final PluginDescription description;

    PluginClassLoader(Path jarFile, PluginDescription description, ClassLoader parent,
                      SimplePluginManager manager) throws Exception {
        super("HomeX-" + description.name(), new URL[]{jarFile.toUri().toURL()}, parent);
        this.jarFile = jarFile;
        this.description = description;
        this.manager = manager;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return findLocalClass(name);
        } catch (ClassNotFoundException e) {
            Class<?> fromOther = manager.findClassInOtherPlugins(name, this);
            if (fromOther != null) return fromOther;
            throw e;
        }
    }

    /** Sucht nur in der eigenen JAR (keine Weiterleitung → keine Endlosschleifen). */
    Class<?> findLocalClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            return c != null ? c : super.findClass(name);
        }
    }
}

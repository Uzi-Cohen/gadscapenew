package com.gadscape.cache.verify;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the same cache with RuneLite's own cache library, built from RuneLite master
 * (server/cache/scripts/build-runelite-reference.sh), so the verifier can compare raw bytes and decoded values
 * with an independent implementation.
 * <p>
 * GadScape already depends on RuneLite's cache library 1.9.8 (same package names, rev-211 era), so the reference
 * jar is loaded child-first in its own class loader.
 */
final class RuneLiteReference implements AutoCloseable {

    private final URLClassLoader loader;
    private final Object store;
    private final Object storage;
    private final Map<String, Object> loaders = new HashMap<>();

    /**
     * @param classpath the reference jar, optionally followed (path-separator separated) by jars it needs that the
     *                  server does not have, such as JNA (RuneLite's bzip2 path) and commons-io
     */
    RuneLiteReference(final String classpath, final File cacheDir) throws Exception {
        final List<URL> urls = new java.util.ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank()) urls.add(new File(entry).toURI().toURL());
        }
        loader = new ChildFirstLoader(urls.toArray(new URL[0]), RuneLiteReference.class.getClassLoader());
        final Class<?> storeClass = loader.loadClass("net.runelite.cache.fs.Store");
        store = Reflect.construct(storeClass, cacheDir);
        Reflect.call(store, "load");
        storage = Reflect.call(store, "getStorage");
    }

    private Object index(final int indexId) throws ClassNotFoundException {
        final Class<?> indexType = loader.loadClass("net.runelite.cache.IndexType");
        for (Object constant : indexType.getEnumConstants()) {
            if ((int) Reflect.call(constant, "getNumber") == indexId) {
                return Reflect.call(store, "getIndex", constant);
            }
        }
        throw new IllegalArgumentException("RuneLite has no IndexType " + indexId);
    }

    /**
     * All files of one group, by file id, or null when RuneLite does not have the group.
     */
    Map<Integer, byte[]> files(final int indexId, final int groupId) throws Exception {
        final Object archive = Reflect.call(index(indexId), "getArchive", groupId);
        if (archive == null) {
            return null;
        }
        final byte[] data = (byte[]) Reflect.call(storage, "loadArchive", archive);
        final Object archiveFiles = Reflect.call(archive, "getFiles", data);
        final Map<Integer, byte[]> out = new TreeMap<>();
        for (Object file : (java.util.Collection<?>) Reflect.call(archiveFiles, "getFiles")) {
            out.put((Integer) Reflect.call(file, "getFileId"), (byte[]) Reflect.call(file, "getContents"));
        }
        return out;
    }

    int groupCount(final int indexId) throws Exception {
        return ((List<?>) Reflect.call(index(indexId), "getArchives")).size();
    }

    /**
     * Calls {@code new net.runelite.cache.definitions.loaders.<name>().load(args...)}.
     */
    Object load(final String loaderName, final Object... args) throws Exception {
        Object instance = loaders.get(loaderName);
        if (instance == null) {
            instance = Reflect.construct(loader.loadClass("net.runelite.cache.definitions.loaders." + loaderName));
            loaders.put(loaderName, instance);
        }
        return Reflect.call(instance, "load", args);
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    /**
     * Loads net.runelite.cache.* from the reference jar first; everything else (guava, slf4j, commons-compress...)
     * comes from the parent.
     */
    private static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("net.runelite.cache.") && !name.startsWith("com.sun.jna.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}

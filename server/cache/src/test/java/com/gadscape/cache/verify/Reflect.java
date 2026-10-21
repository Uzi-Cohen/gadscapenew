package com.gadscape.cache.verify;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small reflection helpers for the cache verifier: it reads private definition fields on our side and drives
 * RuneLite's classes (loaded from a separate jar) without compiling against them.
 */
final class Reflect {

    private Reflect() {
    }

    static Field findField(Class<?> type, final String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * Value of a field (any visibility) or, failing that, of a getter getX()/isX().
     */
    static Object get(final Object target, final String name) {
        if (target == null) {
            return null;
        }
        final Field field = findField(target.getClass(), name);
        try {
            if (field != null) {
                return field.get(target);
            }
            final String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (String getter : new String[]{"get" + cap, "is" + cap}) {
                try {
                    final Method m = target.getClass().getMethod(getter);
                    return m.invoke(target);
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read " + name + " of " + target.getClass(), e);
        }
        throw new IllegalArgumentException("No field or getter '" + name + "' on " + target.getClass().getName());
    }

    /**
     * Invokes an instance method on {@code target}.
     */
    static Object call(final Object target, final String method, final Object... args) {
        return invoke(target.getClass(), target, method, args);
    }

    /**
     * Invokes a static method of {@code type}.
     */
    static Object callStatic(final Class<?> type, final String method, final Object... args) {
        return invoke(type, null, method, args);
    }

    private static Object invoke(final Class<?> type, final Object target, final String method, final Object... args) {
        for (Method m : allMethods(type)) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) {
                continue;
            }
            if ((target == null) != Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (!compatible(m.getParameterTypes(), args)) {
                continue;
            }
            try {
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("No method " + method + "/" + args.length + " on " + type.getName());
    }

    static Object construct(final Class<?> type, final Object... args) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.getParameterCount() != args.length || !compatible(c.getParameterTypes(), args)) {
                continue;
            }
            try {
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw rethrow(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("No constructor/" + args.length + " on " + type.getName());
    }

    static RuntimeException rethrow(final Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    private static List<Method> allMethods(Class<?> type) {
        final List<Method> list = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                list.add(m);
            }
        }
        for (Method m : type.getMethods()) {
            list.add(m);
        }
        return list;
    }

    private static boolean compatible(final Class<?>[] params, final Object[] args) {
        for (int i = 0; i < params.length; i++) {
            final Class<?> p = wrap(params[i]);
            if (args[i] != null && !p.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(final Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        return c;
    }

    /**
     * Turns arrays into lists, maps into sorted maps and integral numbers into longs so values decoded by two
     * different code bases can be compared with equals().
     */
    static Object normalize(final Object value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            final int length = Array.getLength(value);
            final List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(normalize(Array.get(value, i)));
            }
            return list;
        }
        if (value instanceof Map<?, ?> map) {
            final TreeMap<Long, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(((Number) e.getKey()).longValue(), normalize(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Character c) {
            return (long) c;
        }
        return value;
    }
}

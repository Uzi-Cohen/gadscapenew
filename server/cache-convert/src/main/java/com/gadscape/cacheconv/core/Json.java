package com.gadscape.cacheconv.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON writer/reader. Writing keeps map iteration order (use TreeMap/LinkedHashMap for
 * deterministic output). Reading yields LinkedHashMap, ArrayList, String, Long (integers), Double, Boolean or null.
 */
public final class Json {
    private Json() {}

    // ---------------------------------------------------------------- writing

    public static String write(Object v) { StringBuilder s = new StringBuilder(); write(s, v, 0, true); s.append('\n'); return s.toString(); }
    public static String writeCompact(Object v) { StringBuilder s = new StringBuilder(); write(s, v, 0, false); return s.toString(); }

    public static void write(Path file, Object v) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, write(v), StandardCharsets.UTF_8);
    }

    /** Values that are small (numbers, strings, short flat lists) are written on one line. */
    private static void write(StringBuilder s, Object v, int indent, boolean pretty) {
        if (v == null) s.append("null");
        else if (v instanceof String str) quote(s, str);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) s.append(v);
        else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) s.append("null"); else s.append(v);
        } else if (v instanceof Number n) s.append(n);
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { s.append("{}"); return; }
            boolean flat = pretty && m.size() <= 8 && m.values().stream().allMatch(Json::scalar);
            s.append('{');
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ > 0) s.append(',');
                if (pretty && !flat) nl(s, indent + 1); else if (pretty) s.append(i == 1 ? "" : " ");
                quote(s, String.valueOf(e.getKey()));
                s.append(pretty ? ": " : ":");
                write(s, e.getValue(), indent + 1, pretty);
            }
            if (pretty && !flat) nl(s, indent);
            s.append('}');
        } else if (v instanceof Collection<?> c) {
            writeList(s, new ArrayList<>(c), indent, pretty);
        } else if (v instanceof int[] a) {
            List<Object> l = new ArrayList<>(); for (int x : a) l.add(x); writeList(s, l, indent, pretty);
        } else if (v instanceof long[] a) {
            List<Object> l = new ArrayList<>(); for (long x : a) l.add(x); writeList(s, l, indent, pretty);
        } else if (v instanceof Object[] a) {
            writeList(s, List.of(a), indent, pretty);
        } else quote(s, v.toString());
    }

    private static void writeList(StringBuilder s, List<?> l, int indent, boolean pretty) {
        if (l.isEmpty()) { s.append("[]"); return; }
        boolean flat = !pretty || l.stream().allMatch(Json::scalar);
        s.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) s.append(pretty && flat ? ", " : ",");
            if (pretty && !flat) nl(s, indent + 1);
            write(s, l.get(i), indent + 1, pretty);
        }
        if (pretty && !flat) nl(s, indent);
        s.append(']');
    }

    private static boolean scalar(Object v) { return v == null || v instanceof Number || v instanceof Boolean || (v instanceof String str && str.length() < 80); }
    private static void nl(StringBuilder s, int indent) { s.append('\n'); for (int i = 0; i < indent; i++) s.append("  "); }

    public static void quote(StringBuilder s, String str) {
        s.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> { if (c < 0x20 || c > 0x7e) s.append(String.format("\\u%04x", (int) c)); else s.append(c); }
            }
        }
        s.append('"');
    }

    // ---------------------------------------------------------------- reading

    public static Object read(Path file) throws IOException { return parse(Files.readString(file, StandardCharsets.UTF_8)); }

    public static Object parse(String text) {
        P p = new P(text);
        if (p.i < text.length() && text.charAt(p.i) == '﻿') p.i++; // byte order mark
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw p.err("trailing data");
        return v;
    }

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        RuntimeException err(String m) { return new IllegalArgumentException("JSON: " + m + " at offset " + i); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = string(); ws();
                    if (s.charAt(i++) != ':') throw err("expected ':'");
                    m.put(k, value()); ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw err("expected ',' or '}'");
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value()); ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw err("expected ',' or ']'");
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(st, i);
            if (num.isEmpty()) throw err("unexpected '" + c + "'");
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }
        String string() {
            if (s.charAt(i) != '"') throw err("expected string");
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            }
        }
    }

    // ---------------------------------------------------------------- access helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) { return (Map<String, Object>) v; }
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) { return (List<Object>) v; }
    public static int i(Object v) { return ((Number) v).intValue(); }
}

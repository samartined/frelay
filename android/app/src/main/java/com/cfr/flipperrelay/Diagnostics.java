package com.cfr.flipperrelay;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small in-memory log of connection events for the "Copiar diagnóstico" button. Never holds tokens. */
final class Diagnostics {
    static final int CAPACITY = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    static void add(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + "  " + message;
        synchronized (LINES) {
            if (LINES.size() >= CAPACITY) LINES.removeFirst();
            LINES.addLast(line);
        }
    }

    static List<String> tail(int n) {
        synchronized (LINES) {
            List<String> all = new ArrayList<>(LINES);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    static String dump() {
        return String.join("\n", tail(CAPACITY));
    }

    static void clear() {
        synchronized (LINES) { LINES.clear(); }
    }
}

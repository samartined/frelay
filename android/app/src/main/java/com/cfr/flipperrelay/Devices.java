package com.cfr.flipperrelay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Device list entries shown for selection. Pure Java so it can be unit-tested. */
final class Devices {
    /** Flipper Zero BLE addresses derive from STMicroelectronics' company ID. A hint only: some firmwares spoof them. */
    private static final String[] FLIPPER_PREFIXES = {"80:E1:26", "80:E1:27"};

    static final class Entry {
        final String address;
        String name;
        boolean bonded;
        boolean advertisesFlipperService;
        boolean classicOnly;
        Integer rssi;

        Entry(String address, String name) {
            this.address = address;
            this.name = name;
        }

        boolean likelyFlipper() {
            return advertisesFlipperService || Devices.likelyFlipper(name, address);
        }

        String title() {
            return name == null || name.trim().isEmpty() ? "(sin nombre)" : name.trim();
        }

        String label() {
            StringBuilder s = new StringBuilder(title()).append("  ·  ").append(address);
            if (bonded) s.append("  ·  emparejado");
            if (classicOnly) s.append("  ·  sólo Bluetooth clásico");
            if (rssi != null) s.append("  ·  visto ").append(rssi).append(" dBm");
            if (likelyFlipper()) s.append("\nposible Flipper");
            return s.toString();
        }
    }

    static boolean likelyFlipper(String name, String address) {
        if (name != null && name.toLowerCase(Locale.ROOT).contains("flipper")) return true;
        if (address == null) return false;
        String a = address.toUpperCase(Locale.ROOT);
        for (String prefix : FLIPPER_PREFIXES) if (a.startsWith(prefix)) return true;
        return false;
    }

    /** Likely Flipper first, then bonded, then by signal and name. */
    static List<Entry> sorted(Collection<Entry> entries) {
        List<Entry> list = new ArrayList<>(entries);
        list.sort(Comparator
                .comparing((Entry e) -> !e.likelyFlipper())
                .thenComparing(e -> !e.bonded)
                .thenComparing(e -> e.rssi == null ? Integer.MAX_VALUE : -e.rssi)
                .thenComparing(e -> e.title().toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.address));
        return list;
    }
}

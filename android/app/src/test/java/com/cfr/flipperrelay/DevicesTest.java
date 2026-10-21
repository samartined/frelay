package com.cfr.flipperrelay;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class DevicesTest {
    @Test public void customNamedFlipperIsRecognisedByAddressAndService() {
        assertTrue(Devices.likelyFlipper("Kuyo", "80:e1:26:12:34:56"));
        assertTrue(Devices.likelyFlipper("Mi Flipper", "00:11:22:33:44:55"));
        assertFalse(Devices.likelyFlipper("Auriculares", "00:11:22:33:44:55"));
        Devices.Entry e = new Devices.Entry("00:11:22:33:44:55", "Raro");
        e.advertisesFlipperService = true;
        assertTrue(e.likelyFlipper());
    }

    @Test public void likelyFlipperThenBondedThenSignal() {
        Devices.Entry tv = entry("AA:00:00:00:00:01", "TV", true, -40);
        Devices.Entry flipper = entry("80:E1:27:00:00:02", "Zorro", false, -80);
        Devices.Entry far = entry("AA:00:00:00:00:03", "Lejos", false, -90);
        Devices.Entry near = entry("AA:00:00:00:00:04", "Cerca", false, -50);
        List<Devices.Entry> sorted = Devices.sorted(Arrays.asList(far, near, tv, flipper));
        assertEquals(Arrays.asList(flipper, tv, near, far), sorted);
    }

    @Test public void labelShowsWhatMatters() {
        Devices.Entry e = entry("80:E1:26:00:00:01", null, true, -61);
        String label = e.label();
        assertTrue(label.startsWith("(sin nombre)"));
        assertTrue(label.contains("80:E1:26:00:00:01") && label.contains("emparejado") && label.contains("-61 dBm") && label.contains("posible Flipper"));
    }

    private static Devices.Entry entry(String address, String name, boolean bonded, Integer rssi) {
        Devices.Entry e = new Devices.Entry(address, name);
        e.bonded = bonded;
        e.rssi = rssi;
        return e;
    }
}

package com.cfr.flipperrelay;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticsTest {
    @Test public void keepsTheNewestLines() {
        Diagnostics.clear();
        for (int i = 0; i < Diagnostics.CAPACITY + 5; i++) Diagnostics.add("evento " + i);
        assertEquals(Diagnostics.CAPACITY, Diagnostics.tail(10_000).size());
        assertTrue(Diagnostics.tail(1).get(0).endsWith("evento " + (Diagnostics.CAPACITY + 4)));
        assertFalse(Diagnostics.dump().contains("evento 4\n"));
    }
}

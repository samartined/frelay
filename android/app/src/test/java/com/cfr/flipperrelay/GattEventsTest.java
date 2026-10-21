package com.cfr.flipperrelay;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class GattEventsTest {
    @Test public void skipsUnrelatedEventsSuchAsAnExtraMtu() throws Exception {
        GattEvents events = new GattEvents();
        events.offer("mtu", 0);  // automatic MTU exchange already consumed elsewhere
        events.offer("mtu", 0);
        events.offer("services", 0);
        events.await("services", 100);
    }

    @Test public void failsFastOnDisconnectWithHint() {
        GattEvents events = new GattEvents();
        events.offer("disconnect", 133);
        IOException e = assertThrows(IOException.class, () -> events.await("services", 1000));
        assertTrue(e.getMessage().contains("133") && e.getMessage().contains("app oficial"));
    }

    @Test public void failedStepReportsStatus() {
        GattEvents events = new GattEvents();
        events.offer("subscribe", 5);
        IOException e = assertThrows(IOException.class, () -> events.await("subscribe", 100));
        assertTrue(e.getMessage().contains("suscripción") && e.getMessage().contains("emparejar"));
    }

    @Test public void timesOut() {
        IOException e = assertThrows(IOException.class, () -> new GattEvents().await("connect", 50));
        assertTrue(e.getMessage().contains("tiempo agotado"));
    }

    @Test public void closedStops() {
        GattEvents events = new GattEvents();
        events.offer("closed", -1);
        assertThrows(IOException.class, () -> events.await("read", 1000));
    }
}

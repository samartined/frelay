package com.cfr.flipperrelay;

import org.junit.Test;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import static org.junit.Assert.*;

public class ServerErrorsTest {
    private static final String T = "100.118.130.39:9434";

    @Test public void httpStatusesFromTheBridge() {
        assertTrue(ServerErrors.describe(401, null, T).startsWith("Token rechazado"));
        assertTrue(ServerErrors.describe(409, null, T).contains("otro móvil"));
        assertTrue(ServerErrors.describe(423, null, T).contains("parado"));
        assertTrue(ServerErrors.describe(404, null, T).contains("/phone"));
        assertTrue(ServerErrors.describe(502, null, T).contains("HTTP 502"));
    }

    @Test public void networkFailuresNameTheCause() {
        assertTrue(ServerErrors.describe(-1, new ConnectException("refused"), T).startsWith("Conexión rechazada en " + T));
        assertTrue(ServerErrors.describe(-1, new SocketTimeoutException(), T).contains("Tailscale"));
        assertTrue(ServerErrors.describe(-1, new NoRouteToHostException(), T).contains("Sin ruta"));
        assertTrue(ServerErrors.describe(-1, new IllegalStateException("x"), T).contains("IllegalStateException: x"));
        assertTrue(ServerErrors.describe(-1, null, T).contains("no respondió"));
    }
}

package com.cfr.flipperrelay;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Turns a failed WebSocket upgrade into an actionable message. Pure Java for unit tests. */
final class ServerErrors {
    /**
     * @param httpCode HTTP status of the refused upgrade, or -1 when no response arrived
     * @param error    the transport exception, if any
     * @param target   "host:port" for the message
     */
    static String describe(int httpCode, Throwable error, String target) {
        if (httpCode > 0) {
            switch (httpCode) {
                case 401: return "Token rechazado por el servidor: vuelve a copiar el Phone token (flipper-bridge token phone)";
                case 403: return "El servidor rechazó la conexión (HTTP 403): token incorrecto o servidor antiguo";
                case 404: return "Ruta no encontrada (HTTP 404): la URL debe terminar en /phone";
                case 409: return "Ya hay otro móvil conectado al servidor";
                case 423: return "El relé está parado desde la vista en directo: pulsa Reanudar allí";
                default:  return "El servidor respondió HTTP " + httpCode + " en " + target;
            }
        }
        if (error instanceof ConnectException)
            return "Conexión rechazada en " + target + ": ¿servidor parado, puerto incorrecto o cortafuegos?";
        if (error instanceof NoRouteToHostException)
            return "Sin ruta hasta " + target + ": ¿Tailscale activo en el móvil y en el servidor?";
        if (error instanceof UnknownHostException)
            return "No se resuelve el nombre del servidor en " + target;
        if (error instanceof SocketTimeoutException || error instanceof InterruptedIOException)
            return "Tiempo agotado conectando a " + target + ": ¿Tailscale activo en el móvil? ¿IP correcta?";
        if (error instanceof SSLException)
            return "Error TLS con " + target + ": " + error.getMessage();
        if (error != null)
            return "No se pudo conectar a " + target + " (" + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()) + ")";
        return "El servidor " + target + " no respondió";
    }
}

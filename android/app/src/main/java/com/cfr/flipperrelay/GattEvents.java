package com.cfr.flipperrelay;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ordered GATT callback events. await() waits for the expected kind and skips unrelated benign
 * events (for example an MTU exchange started by the phone or the Flipper), but fails at once on
 * a disconnect or close. Pure Java for unit tests.
 */
final class GattEvents {
    static final String DISCONNECT = "disconnect";
    static final String CLOSED = "closed";

    record Event(String kind, int status) {}

    private final LinkedBlockingQueue<Event> queue = new LinkedBlockingQueue<>();

    void offer(String kind, int status) {
        queue.offer(new Event(kind, status));
    }

    void clear() {
        queue.clear();
    }

    /** Returns when an event of {@code kind} arrives with status 0. */
    void await(String kind, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            long remaining = deadline - System.nanoTime();
            Event event = remaining > 0 ? queue.poll(remaining, TimeUnit.NANOSECONDS) : null;
            if (event == null) throw new IOException("BLE: tiempo agotado esperando " + describeStep(kind));
            if (CLOSED.equals(event.kind)) throw new IOException("BLE detenido");
            if (DISCONNECT.equals(event.kind)) throw new IOException("Flipper desconectado: " + hint(event.status));
            if (!kind.equals(event.kind)) continue; // unrelated event, e.g. an extra MTU exchange
            if (event.status != 0) throw new IOException("BLE: falló " + describeStep(kind) + ": " + hint(event.status));
            return;
        }
    }

    static String describeStep(String kind) {
        switch (kind) {
            case "connect": return "la conexión";
            case "mtu": return "la negociación de MTU";
            case "services": return "el descubrimiento de servicios";
            case "subscribe": return "la suscripción a notificaciones";
            case "read": return "la lectura del buffer";
            case "write": return "la escritura";
            default: return kind;
        }
    }

    static String hint(int status) {
        switch (status) {
            case 0: return "sin error (estado 0)";
            case 8: return "el Flipper dejó de responder (estado 8, fuera de alcance o apagado)";
            case 19: return "el Flipper cerró la conexión (estado 19)";
            case 22: return "conexión terminada por el móvil (estado 22)";
            case 62: return "no se pudo establecer la conexión (estado 62): acerca el Flipper y reintenta";
            case 133: return "error GATT 133: cierra la app oficial de Flipper o desactiva y activa el Bluetooth";
            case 5: case 15: return "falta de autenticación o cifrado (estado " + status + "): vuelve a emparejar el Flipper";
            default: return "estado GATT " + status;
        }
    }
}

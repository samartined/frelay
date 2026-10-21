package com.cfr.flipperrelay;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.usb.*;
import android.os.*;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import okio.ByteString;

public final class RelayService extends Service {
    private static final String CHANNEL = "flipper-relay";
    public static volatile String status = "Parado";
    public static volatile boolean active;
    /** Per-stage state shown in the app: server WebSocket, Flipper transport, relay. */
    public static volatile String serverStage = "—", flipperStage = "—", relayStage = "Parado";
    public static volatile String lastError;
    public static volatile long lastErrorAt, nextRetryAt;
    public static volatile int attempt;
    private volatile boolean running;
    private volatile Transport transport;
    private volatile WebSocket socket;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build();
    @Override public IBinder onBind(Intent intent) { return null; }
    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RelayService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Flipper Relay").setContentText(message).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
    private void update(String message) {
        status = message;
        if (running) getSystemService(NotificationManager.class).notify(42, notification(message));
    }
    static String appVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }
    private void fail(String stage, String message) {
        Diagnostics.add("Error en " + ("server".equals(stage) ? "servidor" : "Flipper") + ": " + message);
        lastError = message; lastErrorAt = System.currentTimeMillis();
        if ("server".equals(stage)) serverStage = "Error"; else flipperStage = "Error";
        update(message);
    }
    @android.annotation.SuppressLint("WakelockTimeout")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        serverStage = "—"; flipperStage = "—"; relayStage = "Arrancando"; lastError = null; attempt = 0; nextRetryAt = 0;
        BleTransport.skipMtu = false;
        Diagnostics.add("Relé iniciado: app " + appVersion(this) + ", Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Flipper connection", NotificationManager.IMPORTANCE_LOW));
        startForeground(42, notification("Conectando"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        running = true; active = true;
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlipperRelay:active");
        wakeLock.acquire(); // User-started foreground relay; released in onDestroy.
        worker = new Thread(this::runRelay, "flipper-relay"); worker.start();
        return START_NOT_STICKY;
    }
    private void runRelay() {
        SharedPreferences prefs = getSharedPreferences("relay", MODE_PRIVATE);
        int failures = 0;
        while (running) {
            AtomicBoolean healthy = new AtomicBoolean(true);
            AtomicBoolean ready = new AtomicBoolean(false);
            LinkedBlockingQueue<byte[]> writes = new LinkedBlockingQueue<>(64);
            CountDownLatch connected = new CountDownLatch(1);
            final WebSocket[] sessionSocket = new WebSocket[1];
            final String[] serverFailure = new String[1];
            String stage = "server";
            attempt++; nextRetryAt = 0;
            try {
                String url = RelayAddress.validate(prefs.getString("url", ""));
                java.net.URI parsed = java.net.URI.create(url);
                String target = parsed.getHost() + ":" + (parsed.getPort() > 0 ? parsed.getPort() : ("wss".equals(parsed.getScheme()) ? 443 : 80));
                String token = prefs.getString("token", "");
                if (token.length() < 32) throw new IOException("Pega el Phone token que muestra el servidor");
                serverStage = "Conectando a " + target; flipperStage = "—"; relayStage = "Esperando";
                update("Conectando al servidor " + target);
                WebSocket ws = client.newWebSocket(new Request.Builder().url(url).header("Authorization", "Bearer " + token).build(), new WebSocketListener() {
                    @Override public void onOpen(WebSocket ws, Response response) { connected.countDown(); }
                    @Override public void onMessage(WebSocket ws, ByteString bytes) {
                        if (!ready.get() || bytes.size() > 65536 || !writes.offer(bytes.toByteArray())) {
                            healthy.set(false); ws.cancel();
                        }
                    }
                    @Override public void onMessage(WebSocket ws, String text) {
                        // No remote configuration or shell execution; this channel carries RPC bytes only.
                        healthy.set(false); ws.close(1003, "Binary messages required");
                    }
                    @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                        if (connected.getCount() > 0) serverFailure[0] = ServerErrors.describe(r == null ? -1 : r.code(), t, target);
                        if (r != null) r.close();
                        healthy.set(false); connected.countDown();
                    }
                    @Override public void onClosing(WebSocket ws, int code, String reason) {
                        if (reason != null && !reason.isEmpty()) serverFailure[0] = "El servidor cerró la sesión: " + reason;
                        healthy.set(false); ws.close(code, null);
                    }
                    @Override public void onClosed(WebSocket ws, int code, String reason) { healthy.set(false); }
                });
                socket = ws; sessionSocket[0] = ws;
                if (!connected.await(12, TimeUnit.SECONDS))
                    throw new IOException("El servidor " + target + " no respondió en 12 s: ¿Tailscale activo en el móvil? ¿IP y puerto correctos?");
                if (!healthy.get())
                    throw new IOException(serverFailure[0] != null ? serverFailure[0] : ServerErrors.describe(-1, null, target));
                serverStage = "Conectado a " + target;
                Diagnostics.add("Servidor conectado: " + target + " (intento " + attempt + ")");
                stage = "flipper";
                Transport.Listener listener = new Transport.Listener() {
                    public void bytes(byte[] data) {
                        WebSocket s = sessionSocket[0];
                        if (!healthy.get()) return;
                        if (s.queueSize() > 1024 * 1024 || !s.send(ByteString.of(data))) {
                            healthy.set(false); s.cancel();
                        }
                    }
                    public void failed(String message) { if (healthy.getAndSet(false)) fail("flipper", message); }
                    public void stage(String message) { flipperStage = message; update(message); }
                };
                String mode = prefs.getString("mode", "BLE");
                if ("USB".equals(mode)) {
                    UsbManager manager = getSystemService(UsbManager.class);
                    UsbDevice device = manager.getDeviceList().get(prefs.getString("usb", ""));
                    if (device == null) throw new IOException("Conecta por USB-C el Flipper seleccionado y pulsa Iniciar");
                    transport = new UsbTransport(manager, device, listener);
                } else {
                    transport = new BleTransport(this, prefs.getString("ble", ""), listener);
                }
                flipperStage = "Conectando por " + mode;
                update("Conectando al Flipper por " + mode);
                transport.open();
                if (!running || !healthy.get())
                    throw new IOException(serverFailure[0] != null ? serverFailure[0] : "Conexión interrumpida");
                ready.set(true);
                if (!ws.send(new JSONObject().put("type", "ready").put("protocol", 1).put("transport", mode).toString()))
                    throw new IOException("El servidor se desconectó");
                flipperStage = "Conectado por " + mode; relayStage = "Activo";
                update("Activo: Flipper conectado por " + mode); failures = 0;
                Diagnostics.add("Relé activo por " + mode);
                while (running && healthy.get()) {
                    byte[] next = writes.poll(500, TimeUnit.MILLISECONDS);
                    if (next != null) {
                        transport.write(next);
                        if (!ws.send("{\"type\":\"written\"}")) throw new IOException("El servidor se desconectó");
                    }
                }
            } catch (Exception e) {
                if (running) {
                    String message = e instanceof IllegalArgumentException ? "URL del servidor no válida: " + e.getMessage()
                            : e.getMessage() == null ? "La conexión falló (" + e.getClass().getSimpleName() + ")" : e.getMessage();
                    if ("flipper".equals(stage) && serverFailure[0] != null && !healthy.get()) { stage = "server"; message = serverFailure[0]; }
                    fail(stage, message);
                }
            } finally {
                healthy.set(false); ready.set(false); writes.clear();
                Transport t = transport; transport = null; if (t != null) t.close();
                WebSocket s = socket; socket = null; if (s != null) s.cancel();
            }
            if (running) {
                failures++;
                long delay = Math.min(15000, 2000L * failures);
                relayStage = "Reintentando"; nextRetryAt = System.currentTimeMillis() + delay;
                try { Thread.sleep(delay); } catch (InterruptedException e) { break; }
            }
        }
    }
    @Override public void onDestroy() {
        running = false; active = false; status = "Parado"; relayStage = "Parado"; nextRetryAt = 0;
        if (worker != null) worker.interrupt();
        WebSocket s = socket; if (s != null) s.cancel();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        super.onDestroy();
    }
}

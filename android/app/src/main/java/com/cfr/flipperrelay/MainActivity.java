package com.cfr.flipperrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.*;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.core.content.ContextCompat;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

@SuppressLint("MissingPermission")
public final class MainActivity extends Activity {
    private static final String USB_PERMISSION = "com.cfr.flipperrelay.USB_PERMISSION";
    private static final int REQ_BLE = 7, REQ_NOTIFY = 8;
    private static final long SCAN_MS = 12000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Devices.Entry> devices = new LinkedHashMap<>();
    private EditText url, token;
    private Spinner mode;
    private TextView selected, searchStatus, serverLine, flipperLine, relayLine, errorLine, diagnostics;
    private RadioGroup deviceList;
    private BluetoothLeScanner scanner;
    private boolean receiverRegistered, renderPending;
    private SharedPreferences prefs;

    private final Runnable statusTick = new Runnable() {
        public void run() { showStatus(); handler.postDelayed(this, 1000); }
    };
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            ScanRecord record = result.getScanRecord();
            String name = record != null && record.getDeviceName() != null ? record.getDeviceName() : d.getName();
            boolean service = record != null && record.getServiceUuids() != null
                    && record.getServiceUuids().contains(new ParcelUuid(BleTransport.SERVICE));
            if (name == null && !service && !Devices.likelyFlipper(null, d.getAddress())) return; // anonymous beacons
            int rssi = result.getRssi();
            runOnUiThread(() -> {
                Devices.Entry e = devices.computeIfAbsent(d.getAddress(), a -> new Devices.Entry(a, name));
                if (name != null) e.name = name;
                e.rssi = rssi;
                e.advertisesFlipperService |= service;
                scheduleRender();
            });
        }
        @Override public void onScanFailed(int error) {
            runOnUiThread(() -> searchStatus.setText("La búsqueda BLE falló (código " + error + "). Desactiva y activa el Bluetooth."));
        }
    };
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (USB_PERMISSION.equals(i.getAction())) {
                // Recheck UsbManager instead of trusting broadcast extras.
                UsbDevice d = getSystemService(UsbManager.class).getDeviceList().get(prefs.getString("usb", ""));
                if (d != null && getSystemService(UsbManager.class).hasPermission(d)) launchRelay();
                else show("No se concedió el permiso USB");
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("relay", 0);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20); layout.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(layout); setContentView(scroll);
        // Account for Android 15 edge-to-edge system bars and the keyboard.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        TextView title = label(layout, "Flipper Relay"); title.setTextSize(28); title.setTextColor(Color.rgb(210, 80, 0));
        label(layout, "Conecta tu agente con el Flipper a través de este móvil. Mantén Tailscale activo en el móvil y en el servidor.");

        section(layout, "1. Servidor");
        label(layout, "URL del servidor");
        url = new EditText(this); url.setSingleLine(); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("ws://100.x.x.x:9434/phone"); url.setText(prefs.getString("url", "")); layout.addView(url);
        label(layout, "Phone token");
        token = new EditText(this); token.setSingleLine(); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.getString("token", "")); layout.addView(token);
        button(layout, "Probar servidor", this::testServer);

        section(layout, "2. Flipper");
        label(layout, "Conexión");
        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"BLE", "USB"})); layout.addView(mode);
        mode.setSelection("USB".equals(prefs.getString("mode", "BLE")) ? 1 : 0);
        selected = label(layout, ""); selected.setTextSize(17); selected.setTypeface(Typeface.DEFAULT_BOLD);
        selected.setPadding(dp(12), dp(10), dp(12), dp(10));
        button(layout, "Buscar dispositivos", this::findDevices);
        searchStatus = label(layout, "Pulsa Buscar y elige tu Flipper en la lista. Se muestran todos los dispositivos emparejados y los que se vean cerca.");
        deviceList = new RadioGroup(this); layout.addView(deviceList);

        section(layout, "3. Relé");
        button(layout, "Iniciar relé", this::startRelay);
        button(layout, "Parar relé", () -> stopService(new Intent(this, RelayService.class)));
        serverLine = label(layout, ""); flipperLine = label(layout, ""); relayLine = label(layout, "");
        errorLine = label(layout, ""); errorLine.setTextColor(Color.rgb(190, 30, 30));
        section(layout, "Diagnóstico");
        diagnostics = label(layout, ""); diagnostics.setTextSize(12); diagnostics.setTypeface(Typeface.MONOSPACE);
        button(layout, "Copiar diagnóstico", this::copyDiagnostics);
        label(layout, "BLE: activa el Bluetooth del Flipper, cierra la app oficial de Flipper y confirma el emparejamiento si se pide.\n\n"
                + "USB: usa un cable USB-C de datos y acepta el permiso USB. Tras cambiar de cable o de modo, pulsa Iniciar otra vez.");

        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                stopScan(); devices.clear(); render(); showSelected();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        ContextCompat.registerReceiver(this, usbReceiver, new IntentFilter(USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        showSelected(); showStatus();
    }

    // ---------------------------------------------------------------- helpers
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout l, String text) { TextView t = new TextView(this); t.setText(text); t.setTextSize(15); t.setPadding(0, dp(6), 0, dp(4)); l.addView(t); return t; }
    private void section(LinearLayout l, String text) { TextView t = label(l, text); t.setTextSize(19); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0, dp(20), 0, dp(4)); }
    private void button(LinearLayout l, String text, Runnable action) { Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> action.run()); l.addView(b); }
    private void show(String text) { new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show(); }
    private String currentMode() { return mode.getSelectedItem().toString(); }
    private String key() { return "USB".equals(currentMode()) ? "usb" : "ble"; }

    private boolean blePermissions() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLE); return false;
        }
        return true;
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_BLE) return;
        boolean granted = results.length > 0;
        for (int r : results) granted &= r == PackageManager.PERMISSION_GRANTED;
        if (granted) findDevices();
        else show("Sin el permiso \"Dispositivos cercanos\" la app no puede ver el Bluetooth. Actívalo en Ajustes > Aplicaciones > Flipper Relay > Permisos.");
    }

    // ---------------------------------------------------------------- device list
    private void findDevices() {
        devices.clear(); stopScan();
        if ("USB".equals(currentMode())) {
            for (UsbDevice d : getSystemService(UsbManager.class).getDeviceList().values()) {
                boolean flipper = UsbTransport.isFlipper(d);
                Devices.Entry e = new Devices.Entry(d.getDeviceName(), flipper ? "Flipper USB" : String.format(Locale.ROOT, "USB %04x:%04x", d.getVendorId(), d.getProductId()));
                e.advertisesFlipperService = flipper;
                devices.put(e.address, e);
            }
            render();
            searchStatus.setText(devices.isEmpty() ? "No hay dispositivos USB. Revisa el cable de datos y que el Flipper esté en modo normal." : "Dispositivos USB conectados: " + devices.size());
            return;
        }
        if (!blePermissions()) { searchStatus.setText("Concede el permiso \"Dispositivos cercanos\" para buscar."); return; }
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) { show("Activa primero el Bluetooth del móvil"); return; }
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            Devices.Entry e = new Devices.Entry(d.getAddress(), d.getName());
            e.bonded = true;
            e.classicOnly = d.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC;
            devices.put(e.address, e);
        }
        render();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { searchStatus.setText("Este móvil no permite buscar por BLE ahora mismo."); return; }
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, settings, scanCallback);
        searchStatus.setText("Buscando dispositivos cercanos durante 12 s…");
        handler.postDelayed(() -> { if (scanner != null) { stopScan(); searchStatus.setText("Búsqueda terminada: " + devices.size() + " dispositivos. Toca el tuyo para seleccionarlo."); } }, SCAN_MS);
    }

    private void scheduleRender() {
        if (renderPending) return;
        renderPending = true;
        handler.postDelayed(() -> { renderPending = false; render(); }, 300);
    }

    private void render() {
        deviceList.setOnCheckedChangeListener(null);
        deviceList.removeAllViews();
        String chosen = prefs.getString(key(), "");
        for (Devices.Entry e : Devices.sorted(devices.values())) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(e.label());
            rb.setTextSize(15);
            rb.setPadding(dp(6), dp(10), dp(6), dp(10));
            if (e.likelyFlipper()) rb.setTypeface(Typeface.DEFAULT_BOLD);
            rb.setOnClickListener(v -> select(e));
            deviceList.addView(rb);
            if (e.address.equals(chosen)) rb.setChecked(true);
        }
    }

    private void select(Devices.Entry e) {
        prefs.edit().putString(key(), e.address).putString(key() + "_name", e.title()).apply();
        showSelected();
    }

    private void showSelected() {
        String address = prefs.getString(key(), "");
        if (address.isEmpty()) {
            selected.setText("Ningún dispositivo seleccionado");
            selected.setTextColor(Color.WHITE); selected.setBackgroundColor(Color.rgb(190, 30, 30));
        } else {
            selected.setText("Seleccionado: " + prefs.getString(key() + "_name", "?") + "\n" + address);
            selected.setTextColor(Color.WHITE); selected.setBackgroundColor(Color.rgb(30, 130, 60));
        }
    }

    private void stopScan() { if (scanner != null) { try { scanner.stopScan(scanCallback); } catch (SecurityException | IllegalStateException ignored) {} scanner = null; } }

    // ---------------------------------------------------------------- status
    private void showStatus() {
        serverLine.setText("Servidor: " + RelayService.serverStage);
        flipperLine.setText("Flipper: " + RelayService.flipperStage);
        String relay = "Relé: " + RelayService.relayStage;
        if (RelayService.active && RelayService.attempt > 0) relay += "  ·  intento " + RelayService.attempt;
        long wait = RelayService.nextRetryAt - System.currentTimeMillis();
        if (RelayService.active && wait > 0) relay += "  ·  reintento en " + (wait / 1000 + 1) + " s";
        relayLine.setText(relay);
        java.util.List<String> lines = Diagnostics.tail(10);
        diagnostics.setText(lines.isEmpty() ? "Sin eventos todavía." : String.join("\n", lines));
        String error = RelayService.lastError;
        errorLine.setText(error == null ? "" : "Último error (" + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(RelayService.lastErrorAt)) + "): " + error);
    }

    private void copyDiagnostics() {
        String text = "Flipper Relay " + RelayService.appVersion(this) + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Modo: " + currentMode() + ", seleccionado: " + prefs.getString(key() + "_name", "?") + " " + prefs.getString(key(), "") + "\n"
                + "Servidor: " + RelayService.serverStage + "\nFlipper: " + RelayService.flipperStage + "\nRelé: " + RelayService.relayStage + "\n"
                + "Último error: " + RelayService.lastError + "\n\n" + Diagnostics.dump();
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Diagnóstico Flipper Relay", text));
        Toast.makeText(this, "Diagnóstico copiado (sin tokens). Pégalo donde lo necesites.", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- server test
    private void saveServerFields() {
        prefs.edit().putString("url", url.getText().toString().trim()).putString("token", token.getText().toString().trim()).apply();
    }

    private void testServer() {
        String wsUrl;
        try { wsUrl = RelayAddress.validate(url.getText().toString()); } catch (Exception e) { show(e.getMessage()); return; }
        String tok = token.getText().toString().trim();
        saveServerFields();
        URI u = URI.create(wsUrl);
        boolean tls = "wss".equals(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String host = u.getHost().contains(":") ? "[" + u.getHost() + "]" : u.getHost();
        String target = host + ":" + port;
        String probe = (tls ? "https://" : "http://") + target + "/";
        ProgressDialog progress = ProgressDialog.show(this, null, "Probando " + target + "…", true);
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
            boolean reachable = false;
            try (Response r = client.newCall(new Request.Builder().url(probe).build()).execute()) {
                String body = r.body() != null ? r.body().string() : "";
                reachable = true;
                if (r.isSuccessful() && body.contains("flipper-bridge")) out.append("✓ Servidor flipper-bridge alcanzable en ").append(target);
                else out.append("⚠ ").append(target).append(" responde (HTTP ").append(r.code()).append(") pero no parece flipper-bridge");
            } catch (Exception e) {
                out.append("✗ ").append(ServerErrors.describe(-1, e, target));
            }
            if (reachable) {
                out.append("\n\n");
                if (RelayService.active) out.append("El relé está activo: no se prueba el token para no interrumpirlo.");
                else if (tok.length() < 32) out.append("✗ Falta el Phone token (debe tener al menos 32 caracteres).");
                else out.append(testToken(client, wsUrl, tok, target));
            }
            client.dispatcher().executorService().shutdown();
            runOnUiThread(() -> { progress.dismiss(); show(out.toString()); });
        }).start();
    }

    private String testToken(OkHttpClient client, String wsUrl, String tok, String target) {
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        client.newWebSocket(new Request.Builder().url(wsUrl).header("Authorization", "Bearer " + tok).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                result[0] = "✓ Token aceptado"; ws.close(1000, "connection test"); done.countDown();
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                result[0] = "✗ " + ServerErrors.describe(r == null ? -1 : r.code(), t, target);
                if (r != null) r.close();
                done.countDown();
            }
        });
        try { if (!done.await(10, TimeUnit.SECONDS)) return "✗ El servidor no respondió al probar el token"; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    // ---------------------------------------------------------------- relay
    private void startRelay() {
        if (RelayService.active) { show("Para el relé actual antes de cambiar la conexión"); return; }
        try { RelayAddress.validate(url.getText().toString()); } catch (Exception e) { show(e.getMessage()); return; }
        if (token.getText().toString().trim().length() < 32) { show("Pega el Phone token que muestra el servidor (flipper-bridge show)"); return; }
        String chosen = prefs.getString(key(), "");
        if (chosen.isEmpty()) { show("Pulsa Buscar dispositivos y toca tu Flipper en la lista"); return; }
        String transport = currentMode();
        if ("BLE".equals(transport) && !blePermissions()) return;
        if ("BLE".equals(transport) && !BluetoothAdapter.checkBluetoothAddress(chosen)) { show("La selección Bluetooth no es válida: busca y elige otra vez"); return; }
        saveServerFields();
        prefs.edit().putString("mode", transport).apply();
        if ("USB".equals(transport)) {
            UsbManager manager = getSystemService(UsbManager.class); UsbDevice d = manager.getDeviceList().get(chosen);
            if (d == null) { show("El dispositivo USB seleccionado no está conectado"); return; }
            if (!manager.hasPermission(d)) {
                manager.requestPermission(d, PendingIntent.getBroadcast(this, 3, new Intent(USB_PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                return;
            }
        }
        launchRelay();
    }

    private void launchRelay() {
        stopScan();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        startForegroundService(new Intent(this, RelayService.class));
    }

    @Override protected void onResume() { super.onResume(); handler.post(statusTick); }
    @Override protected void onPause() { handler.removeCallbacks(statusTick); stopScan(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (receiverRegistered) unregisterReceiver(usbReceiver); super.onDestroy(); }
}

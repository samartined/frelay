package com.cfr.flipperrelay;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** UUIDs and big-endian buffer credits follow the upstream Flipper Android bridge. */
@SuppressLint("MissingPermission")
final class BleTransport implements Transport {
    static final UUID SERVICE = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000");
    private static final UUID RX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000");
    private static final UUID TX = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000");
    private static final UUID FLOW = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final String address;
    private final Listener listener;
    private final GattEvents events = new GattEvents();
    private final Object creditLock = new Object();
    private volatile BluetoothGatt gatt;
    private volatile boolean closed;
    private volatile int mtu = 23;
    /**
     * Set when the Flipper dropped the link during MTU negotiation; later attempts in this relay run
     * keep the default MTU (slower, but works). Reset by RelayService when the user presses Start.
     */
    static volatile boolean skipMtu;
    private int credits;
    private BluetoothGattCharacteristic tx;
    BleTransport(Context context, String address, Listener listener) {
        this.context = context; this.address = address; this.listener = listener;
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            Diagnostics.add("GATT conexión: estado=" + stateName(state) + " status=" + status
                    + (status != 0 ? " (" + GattEvents.hint(status) + ")" : ""));
            if (closed) return;
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED)
                events.offer("connect", status);
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != 0) {
                events.offer(GattEvents.DISCONNECT, status);
                listener.failed("Flipper desconectado: " + GattEvents.hint(status));
                synchronized (creditLock) { creditLock.notifyAll(); }
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            Diagnostics.add("GATT servicios descubiertos: status=" + status);
            events.offer("services", status);
        }
        @Override public void onMtuChanged(BluetoothGatt g, int value, int status) {
            Diagnostics.add("GATT MTU=" + value + " status=" + status);
            if (status == 0) mtu = value;
            events.offer("mtu", 0); // The default MTU remains usable if negotiation fails.
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Diagnostics.add("GATT suscripción " + d.getCharacteristic().getUuid() + ": status=" + status);
            events.offer("subscribe", status);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { events.offer("write", status); }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Diagnostics.add("GATT lectura buffer: status=" + status);
            if (status == 0 && c.getUuid().equals(FLOW)) updateCredits(c.getValue());
            events.offer("read", status);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] data = c.getValue();
            if (closed || data == null) return;
            if (c.getUuid().equals(FLOW)) updateCredits(data);
            else if (c.getUuid().equals(RX)) listener.bytes(Arrays.copyOf(data, data.length));
        }
    };
    private void updateCredits(byte[] value) {
        if (value == null || value.length != 4) { listener.failed("Valor de control de flujo BLE no válido"); return; }
        int n = ByteBuffer.wrap(value).getInt();
        if (n < 0 || n > 65536) { listener.failed("Tamaño de buffer BLE no válido"); return; }
        synchronized (creditLock) { credits = n; creditLock.notifyAll(); }
    }
    private static String stateName(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED: return "conectado";
            case BluetoothProfile.STATE_CONNECTING: return "conectando";
            case BluetoothProfile.STATE_DISCONNECTING: return "desconectando";
            case BluetoothProfile.STATE_DISCONNECTED: return "desconectado";
            default: return String.valueOf(state);
        }
    }
    private static String bondName(int bond) {
        switch (bond) {
            case BluetoothDevice.BOND_BONDED: return "emparejado";
            case BluetoothDevice.BOND_BONDING: return "emparejando";
            case BluetoothDevice.BOND_NONE: return "no emparejado";
            default: return String.valueOf(bond);
        }
    }
    private void await(String kind, int seconds) throws Exception {
        if (closed) throw new IOException("BLE detenido");
        events.await(kind, TimeUnit.SECONDS.toMillis(seconds));
    }
    @Override public void open() throws Exception {
        BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("Activa el Bluetooth del móvil");
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IOException("Selecciona un dispositivo Bluetooth en la lista");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        Diagnostics.add("BLE inicio: " + address + " nombre=" + device.getName() + " tipo=" + device.getType()
                + " vínculo=" + bondName(device.getBondState()) + " omitirMTU=" + skipMtu);
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            listener.stage("Emparejando con " + address + ": confirma el código en el móvil y en el Flipper");
            if (device.getBondState() != BluetoothDevice.BOND_BONDING && !device.createBond())
                throw new IOException("No se pudo iniciar el emparejamiento");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!closed && device.getBondState() != BluetoothDevice.BOND_BONDED && System.nanoTime() < deadline)
                Thread.sleep(200);
            if (closed || device.getBondState() != BluetoothDevice.BOND_BONDED)
                throw new IOException("Emparejamiento no completado: confirma el código en el móvil y en el Flipper");
        }
        if (closed) throw new IOException("BLE detenido");
        listener.stage("Conectando por BLE a " + address);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("No se pudo abrir la conexión BLE");
        await("connect", 20);
        // Like the official app (BleDeviceConnectionApiImpl: connect, waitForBonding, requestMtu): let the
        // Flipper finish its security request (pairing/encryption) before any other GATT operation.
        listener.stage("Esperando cifrado y vínculo con el Flipper");
        Thread.sleep(600);
        long bondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!closed && device.getBondState() == BluetoothDevice.BOND_BONDING && System.nanoTime() < bondDeadline)
            Thread.sleep(200);
        Diagnostics.add("BLE tras conectar: vínculo=" + bondName(device.getBondState()));
        if (closed) throw new IOException("BLE detenido");
        if (device.getBondState() != BluetoothDevice.BOND_BONDED)
            throw new IOException("El Flipper no completó el emparejamiento: bórralo de los dispositivos Bluetooth del móvil y del Flipper, y vuelve a emparejar");
        if (skipMtu) {
            Diagnostics.add("BLE: se omite la negociación de MTU (MTU 23)");
        } else {
            listener.stage("Negociando MTU");
            if (gatt.requestMtu(247)) {
                try {
                    await("mtu", 10);
                } catch (IOException e) {
                    skipMtu = true;
                    Diagnostics.add("BLE: falló el MTU, el próximo intento no lo negociará");
                    throw new IOException(e.getMessage() + ". El próximo intento no negociará el MTU.");
                }
            } else {
                Diagnostics.add("BLE: requestMtu devolvió false");
            }
        }
        listener.stage("Descubriendo servicios del Flipper");
        if (!gatt.discoverServices()) throw new IOException("No se pudo iniciar el descubrimiento de servicios BLE");
        await("services", 15);
        BluetoothGattService service = gatt.getService(SERVICE);
        if (service == null) throw new IOException("El dispositivo no ofrece el servicio RPC del Flipper: ¿es el Flipper correcto?");
        BluetoothGattCharacteristic rx = service.getCharacteristic(RX);
        tx = service.getCharacteristic(TX);
        BluetoothGattCharacteristic flow = service.getCharacteristic(FLOW);
        if (rx == null || tx == null || flow == null) throw new IOException("Faltan las características serie del Flipper");
        listener.stage("Suscribiendo a notificaciones");
        subscribe(rx); subscribe(flow);
        if (!gatt.readCharacteristic(flow)) throw new IOException("No se pudo leer el buffer BLE del Flipper");
        await("read", 10);
    }
    private void subscribe(BluetoothGattCharacteristic c) throws Exception {
        if (!gatt.setCharacteristicNotification(c, true)) throw new IOException("No se pudieron activar las notificaciones BLE");
        BluetoothGattDescriptor descriptor = c.getDescriptor(CCC);
        if (descriptor == null) throw new IOException("Falta el descriptor CCC BLE");
        descriptor.setValue((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        if (!gatt.writeDescriptor(descriptor)) throw new IOException("No se pudo suscribir a las notificaciones BLE");
        await("subscribe", 10);
    }
    @Override public void write(byte[] data) throws Exception {
        int offset = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (offset < data.length) {
            if (closed) throw new IOException("BLE detenido");
            int count;
            synchronized (creditLock) {
                while (credits == 0 && !closed) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new IOException("Tiempo agotado en el control de flujo BLE");
                    TimeUnit.NANOSECONDS.timedWait(creditLock, remaining);
                }
                if (closed) throw new IOException("BLE detenido");
                count = Math.min(Math.min(mtu - 3, 244), Math.min(credits, data.length - offset));
                credits -= count;
            }
            tx.setWriteType((tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            tx.setValue(Arrays.copyOfRange(data, offset, offset + count));
            if (!gatt.writeCharacteristic(tx)) throw new IOException("Escritura BLE rechazada");
            await("write", 10);
            offset += count;
        }
    }
    @Override public void close() {
        closed = true;
        synchronized (creditLock) { creditLock.notifyAll(); }
        events.offer(GattEvents.CLOSED, -1);
        BluetoothGatt g = gatt;
        if (g != null) { try { g.disconnect(); g.close(); } catch (SecurityException ignored) {} }
    }
}

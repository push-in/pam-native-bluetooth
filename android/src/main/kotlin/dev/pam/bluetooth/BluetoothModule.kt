package dev.pam.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.pam.nativeapp.modules.*
import dev.pam.nativeapp.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BluetoothModule(private val context: Context) : NativeModule {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = manager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()
    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val events = ArrayDeque<JSONObject>()
    private var scanning = false
    private val stopScan = Runnable { stopScanInternal() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            devices[device.address] = device
            event(1, device.address, message = runCatching { device.name }.getOrNull().orEmpty(), rssi = result.rssi)
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; event(7, message = "BLE scan failed ($errorCode).") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val id = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) { event(7, id, message = "GATT connection failed ($status)."); gatt.close(); gatts.remove(id); return }
            if (newState == BluetoothProfile.STATE_CONNECTED) { gatts[id] = gatt; event(2, id); gatt.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { event(3, id); gatt.close(); gatts.remove(id) }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) event(4, gatt.device.address, message = JSONArray(gatt.services.map { it.uuid.toString() }).toString())
            else event(7, gatt.device.address, message = "Service discovery failed ($status).")
        }
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { characteristicResult(gatt, characteristic, characteristic.value, status) }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) { characteristicResult(gatt, characteristic, value, status) }
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { characteristicResult(gatt, characteristic, characteristic.value, BluetoothGatt.GATT_SUCCESS) }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { characteristicResult(gatt, characteristic, value, BluetoothGatt.GATT_SUCCESS) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            event(if (status == BluetoothGatt.GATT_SUCCESS) 6 else 7, gatt.device.address, characteristic.service.uuid.toString(), characteristic.uuid.toString(), message = if (status == 0) "" else "Write failed ($status).")
        }
    }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        val values = runCatching { WireMap.decode(payload) }.getOrElse { completion.fail(it); return }
        runCatching {
            when (method) {
                "state" -> mapOf("state" to WireValue.Integer(state().toLong()))
                "scan" -> { scan(values); emptyMap() }
                "stopScan" -> { stopScanInternal(); emptyMap() }
                "connect" -> { connect(values.text("deviceId")); emptyMap() }
                "disconnect" -> { disconnect(values.text("deviceId")); emptyMap() }
                "read" -> { characteristic(values).first.readCharacteristic(characteristic(values).second); emptyMap() }
                "write" -> { write(values); emptyMap() }
                "subscribe" -> { subscribe(values); emptyMap() }
                "poll" -> mapOf("json" to WireValue.Text(poll(values.integer("limit").toInt()).toString()))
                else -> error("Unknown method: $method")
            }
        }.onSuccess { completion.ok(it) }.onFailure { completion.fail(it) }
    }

    private fun state(): Int = when {
        !context.packageManager.hasSystemFeature("android.hardware.bluetooth_le") || adapter == null -> 5
        adapter?.isEnabled == true -> 3
        else -> 2
    }
    private fun scan(v: Map<String, WireValue>) {
        val scanner = adapter?.bluetoothLeScanner ?: error("Bluetooth is unavailable or disabled.")
        val ids = JSONArray(v.text("services")); val filters = (0 until ids.length()).map { ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(uuid(ids.getString(it)))).build() }
        stopScanInternal(); scanning = true
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCallback)
        handler.postDelayed(stopScan, v.integer("timeoutMillis").coerceIn(1000, 60000))
    }
    private fun stopScanInternal() { handler.removeCallbacks(stopScan); if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }; scanning = false }
    private fun connect(id: String) { val device = devices[id] ?: adapter?.getRemoteDevice(id) ?: error("Unknown Bluetooth device."); gatts.remove(id)?.close(); device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
    private fun disconnect(id: String) { gatts.remove(id)?.let { it.disconnect(); it.close() } }
    private fun characteristic(v: Map<String, WireValue>): Pair<BluetoothGatt, BluetoothGattCharacteristic> { val g = gatts[v.text("deviceId")] ?: error("Device is not connected."); val s = g.getService(uuid(v.text("service"))) ?: error("Service was not found."); return g to (s.getCharacteristic(uuid(v.text("characteristic"))) ?: error("Characteristic was not found.")) }
    private fun write(v: Map<String, WireValue>) { val (g, c) = characteristic(v); val bytes = Base64.decode(v.text("valueBase64"), Base64.DEFAULT); val type = if (v.flag("response")) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS else { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }; check(ok) { "BLE write could not be queued." } }
    private fun subscribe(v: Map<String, WireValue>) { val (g, c) = characteristic(v); val enabled = v.flag("enabled"); check(g.setCharacteristicNotification(c, enabled)) { "Notifications are unsupported." }; val descriptor = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: error("CCCD descriptor was not found."); val value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE; val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS else { descriptor.value = value; g.writeDescriptor(descriptor) }; check(ok) { "Notification subscription could not be queued." } }
    private fun characteristicResult(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) { if (status == 0) event(5, g.device.address, c.service.uuid.toString(), c.uuid.toString(), Base64.encodeToString(value, Base64.NO_WRAP)) else event(7, g.device.address, message = "Characteristic operation failed ($status).") }
    @Synchronized private fun event(kind: Int, deviceId: String = "", service: String = "", characteristic: String = "", value: String = "", message: String = "", rssi: Int? = null) { while (events.size >= 256) events.removeFirst(); events.addLast(JSONObject().put("kind", kind).put("deviceId", deviceId).put("service", service).put("characteristic", characteristic).put("valueBase64", value).put("message", message).also { if (rssi != null) it.put("rssi", rssi) }) }
    @Synchronized private fun poll(limit: Int): JSONArray = JSONArray().also { out -> repeat(minOf(limit, events.size)) { out.put(events.removeFirst()) } }
    private fun uuid(v: String): UUID = if (v.length == 4 || v.length == 8) UUID.fromString(v.padStart(8, '0') + "-0000-1000-8000-00805f9b34fb") else UUID.fromString(v)
    private fun Map<String, WireValue>.text(k: String) = (get(k) as? WireValue.Text)?.value.orEmpty()
    private fun Map<String, WireValue>.integer(k: String) = (get(k) as? WireValue.Integer)?.value ?: 0
    private fun Map<String, WireValue>.flag(k: String) = (get(k) as? WireValue.Flag)?.value ?: false
    private fun ModuleCompletion.ok(v: Map<String, WireValue> = emptyMap()) = complete(ModuleResultStatus.SUCCESS, WireMap.encode(v))
    private fun ModuleCompletion.fail(e: Throwable) = complete(ModuleResultStatus.FAILURE, e.message.orEmpty().take(1024).toByteArray())
}

package io.github.bataokun.augustble.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.bataokun.augustble.AugustBle
import io.github.bataokun.augustble.AugustException
import io.github.bataokun.augustble.AugustLock
import io.github.bataokun.augustble.LockStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** BLE runtime permissions by API level. */
object AugustPermissions {
    /** Permissions the calling app must hold before scanning/connecting. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** The subset of [required] not yet granted to [context]'s app. */
    fun missing(context: Context): List<String> =
        required().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    fun checkConnectPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("BLUETOOTH_CONNECT permission not granted")
        }
    }

    fun checkScanPermission(context: Context) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("$perm permission not granted")
        }
    }
}

/** Scanning for August Gen1 locks (they advertise the command service UUID). */
object AugustScanner {
    /**
     * Cold flow of scan results for locks advertising the August command
     * service. Collecting starts the scan; cancelling stops it.
     * Requires the scan permission (see [AugustPermissions]).
     */
    @SuppressLint("MissingPermission")
    fun scan(context: Context): Flow<ScanResult> = callbackFlow {
        AugustPermissions.checkScanPermission(context)
        val adapter = bluetoothAdapter(context)
        val scanner = adapter.bluetoothLeScanner
            ?: throw AugustException("BluetoothLeScanner unavailable (Bluetooth off?)")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }

            override fun onScanFailed(errorCode: Int) {
                close(AugustException("BLE scan failed, errorCode=$errorCode"))
            }
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AugustBle.COMMAND_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    /** First lock found within [timeoutMs]. */
    suspend fun scanForFirst(context: Context, timeoutMs: Long = 15_000): BluetoothDevice =
        withTimeout(timeoutMs) { scan(context).first().device }

    internal fun bluetoothAdapter(context: Context): BluetoothAdapter {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: throw AugustException("BluetoothManager unavailable")
        val adapter = manager.adapter ?: throw AugustException("no Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw AugustException("Bluetooth is turned off")
        return adapter
    }
}

/**
 * A connected, handshaken August Gen1 lock, ready for [lock]/[unlock]/[status].
 * Call [close] when done.
 *
 * Concurrency: the protocol layer serialises commands internally (a
 * [Mutex] covers both command routes), so concurrent `suspend` calls are
 * safe from stealing each other's responses — they simply queue up and
 * run one at a time on the wire. They are not made to trade in parallel;
 * await one call at a time when ordering matters (see README behaviour
 * notes).
 */
class ConnectedAugustLock internal constructor(
    private val protocol: AugustLock,
    private val link: GattLink,
    private val connectionScope: CoroutineScope,
    /** The remote device's MAC address. */
    val address: String,
) {
    suspend fun lock() = protocol.lock()
    suspend fun unlock() = protocol.unlock()
    suspend fun unlatch() = protocol.unlatch()
    suspend fun secureLock() = protocol.secureLock()
    suspend fun status(): LockStatus = protocol.status()
    suspend fun doorStatus() = protocol.doorStatus()
    suspend fun lockAndDoorStatus() = protocol.lockAndDoorStatus()
    suspend fun battery() = protocol.battery()

    /** Asynchronous lock-event frames (decrypted), see [AugustLock.events]. */
    val events: kotlinx.coroutines.flow.Flow<ByteArray> = protocol.events

    /** Protocol disconnect (best effort), then GATT teardown. */
    suspend fun close() {
        try {
            protocol.disconnect()
        } catch (_: Exception) {
            // Lock may already be out of range; GATT teardown happens regardless.
        } finally {
            connectionScope.cancel()
            link.close()
        }
    }
}

/** Entry point: connects to an August Gen1 lock over BLE and runs the secure handshake. */
object AugustAndroid {
    /**
     * Connects to [device], discovers the August command service, enables
     * indications and performs the offline-key handshake.
     *
     * GATT status 133 (generic error) is a well-known transient failure when
     * connecting to older BLE peripherals, so connection attempts are retried
     * (up to [maxAttempts] in total) with growing pauses between attempts.
     * [onStage] receives human-readable progress lines so a host app can show
     * exactly how far a failed attempt got (connect / discovery / indications
     * / handshake step).
     *
     * @param offlineKey the lock's 16-byte offline key.
     * @param offlineKeyOffset its non-zero slot/offset.
     */
    suspend fun connect(
        context: Context,
        device: BluetoothDevice,
        offlineKey: ByteArray,
        offlineKeyOffset: Int,
        connectTimeoutMs: Long = 15_000,
        commandTimeoutMs: Long = 10_000,
        maxAttempts: Int = 4,
        onStage: (String) -> Unit = {},
    ): ConnectedAugustLock {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                if (attempt > 1) onStage("connect attempt $attempt/$maxAttempts")
                return connectOnce(
                    context, device, offlineKey, offlineKeyOffset,
                    connectTimeoutMs, commandTimeoutMs, onStage,
                    // The first connectGatt in a process on Samsung's stack
                    // regularly takes >15s to deliver the connected callback;
                    // give it more room.
                    firstAttempt = attempt == 1,
                )
            } catch (e: Exception) {
                lastError = e
                onStage("attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                if (attempt < maxAttempts) {
                    // Let the stack and the lock fully release the previous
                    // connection before retrying; the lock takes seconds to
                    // notice an abruptly dropped central, and hammering it
                    // makes status-133/discovery timeouts more likely.
                    kotlinx.coroutines.delay(2_500)
                }
            }
        }
        throw lastError ?: AugustException("connect failed")
    }

    private suspend fun connectOnce(
        context: Context,
        device: BluetoothDevice,
        offlineKey: ByteArray,
        offlineKeyOffset: Int,
        connectTimeoutMs: Long,
        commandTimeoutMs: Long,
        onStage: (String) -> Unit,
        firstAttempt: Boolean = false,
    ): ConnectedAugustLock {
        AugustPermissions.checkConnectPermission(context)
        val appContext = context.applicationContext
        val link = GattLink()
        link.debugLogger = { msg -> onStage(msg.replaceFirst("gatt: ", "gatt#${link.id}: ")) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            onStage("gatt: connecting to ${device.address}")
            link.expectConnect()
            // Connection recipe for this lock: connect with preferred
            // PHY = LE 1M passed to connectGatt, autoConnect = false.
            // No bonding (the lock goes link-silent when SMP pairing
            // starts), no MTU request, no connection-priority request.
            link.openGatt(appContext, device, BluetoothDevice.PHY_LE_1M_MASK)
            link.awaitConnected(if (firstAttempt) connectTimeoutMs * 2 else connectTimeoutMs)
            onStage("gatt: connected, settling")
            kotlinx.coroutines.delay(500)
            onStage("gatt: discovering services")
            link.discoverServices(connectTimeoutMs)

            val gatt = link.gatt
            val service = gatt.getService(AugustBle.COMMAND_SERVICE_UUID)
                ?: throw AugustException("August command service not found on this device")
            fun characteristic(uuid: java.util.UUID) =
                service.getCharacteristic(uuid)
                    ?: throw AugustException("characteristic $uuid not found")

            for (uuid in listOf(
                AugustBle.SESSION_WRITE_UUID, AugustBle.SESSION_READ_UUID,
                AugustBle.SECURE_WRITE_UUID, AugustBle.SECURE_READ_UUID,
            )) {
                val c = characteristic(uuid)
                onStage(
                    "gatt: char ${uuid.toString().substring(0, 8)} " +
                        "props=0x%02x perms=0x%02x".format(c.properties, c.permissions)
                )
            }

            val sessionChannel = GattCharacteristicChannel(
                link, characteristic(AugustBle.SESSION_WRITE_UUID)
            )
            val secureChannel = GattCharacteristicChannel(
                link, characteristic(AugustBle.SECURE_WRITE_UUID)
            )
            link.registerChannel(AugustBle.SESSION_READ_UUID, sessionChannel)
            link.registerChannel(AugustBle.SECURE_READ_UUID, secureChannel)

            // Enable indications on BOTH read characteristics, in
            // service-definition order (session read bd4ac612 first, then
            // secure read bd4ac614), each awaited, before the app-level
            // steps.
            onStage("gatt: enabling indications (bondState=${device.bondState})")
            link.enableIndications(characteristic(AugustBle.SESSION_READ_UUID), connectTimeoutMs)
            link.enableIndications(characteristic(AugustBle.SECURE_READ_UUID), connectTimeoutMs)

            // Read the Device Information service (firmware revision,
            // then serial number) after initializing and before starting
            // the security handshake.
            readDeviceInfo(link, onStage)
            onStage("gatt: settling 500ms before handshake")
            kotlinx.coroutines.delay(500)

            val protocol = AugustLock(
                sessionChannel = sessionChannel,
                secureChannel = secureChannel,
                offlineKey = offlineKey,
                offlineKeyOffset = offlineKeyOffset,
                scope = scope,
                commandTimeoutMs = commandTimeoutMs,
                logger = onStage,
            )
            protocol.connect()

            onStage("ready")
            return ConnectedAugustLock(protocol, link, scope, device.address)
        } catch (e: Exception) {
            scope.cancel()
            link.close()
            throw e
        }
    }


    /**
     * Reads firmware revision (0x2A26) and serial number (0x2A25) from the
     * Device Information service (0x180A). Best effort: any failure is
     * only logged.
     */
    private suspend fun readDeviceInfo(link: GattLink, onStage: (String) -> Unit) {
        val serviceUuid = java.util.UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val service = link.gatt.getService(serviceUuid)
        if (service == null) {
            onStage("gatt: no Device Information service, skipping reads")
            return
        }
        val reads = listOf(
            "firmware" to "00002a26-0000-1000-8000-00805f9b34fb",
            "serial" to "00002a25-0000-1000-8000-00805f9b34fb",
        )
        for ((label, uuidText) in reads) {
            val characteristic = service.getCharacteristic(java.util.UUID.fromString(uuidText))
            if (characteristic == null) {
                onStage("gatt: device info $label characteristic missing")
                continue
            }
            try {
                val value = link.readCharacteristic(characteristic, timeoutMs = 5_000)
                onStage("gatt: device info $label = ${value?.toString(Charsets.UTF_8) ?: "?"}")
            } catch (e: Exception) {
                onStage("gatt: device info $label read failed: ${e.message}")
            }
        }
    }

    /** Scans for the first lock, then [connect]s to it. */
    suspend fun scanAndConnect(
        context: Context,
        offlineKey: ByteArray,
        offlineKeyOffset: Int,
        scanTimeoutMs: Long = 15_000,
        connectTimeoutMs: Long = 15_000,
        commandTimeoutMs: Long = 10_000,
        maxAttempts: Int = 4,
        onStage: (String) -> Unit = {},
    ): ConnectedAugustLock {
        onStage("scan: looking for August service")
        val result = withTimeout(scanTimeoutMs) { AugustScanner.scan(context).first() }
        @SuppressLint("MissingPermission")
        onStage("scan: found ${result.device.address} (rssi ${result.rssi})")
        return connect(
            context, result.device, offlineKey, offlineKeyOffset,
            connectTimeoutMs, commandTimeoutMs, maxAttempts, onStage,
        )
    }
}

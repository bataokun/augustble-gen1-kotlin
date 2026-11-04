package io.github.bataokun.augustble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Build
import io.github.bataokun.augustble.AugustBle
import io.github.bataokun.augustble.AugustException
import io.github.bataokun.augustble.GattChannel
import java.util.UUID
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [GattChannel] backed by one GATT characteristic pair.
 *
 * Android allows only **one outstanding GATT operation at a time**, so all
 * writes (both channels share one [BluetoothGatt]) are serialised through
 * [GattLink.opMutex]; the protocol core issues commands sequentially anyway.
 */
internal class GattCharacteristicChannel(
    private val link: GattLink,
    private val writeCharacteristic: BluetoothGattCharacteristic,
) : GattChannel {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    override suspend fun write(value: ByteArray) {
        link.writeCharacteristic(writeCharacteristic, value)
    }

    fun deliver(value: ByteArray) {
        inbox.trySend(value)
    }
}

/** Owns the [BluetoothGatt] and the callback-driven operation plumbing. */
@SuppressLint("MissingPermission") // permission checks happen in AugustAndroid before connect
internal class GattLink {
    /** Monotonic id so log lines can be attributed to one connection attempt
     *  (stale BluetoothGatt objects from earlier attempts can still deliver
     *  callbacks into the same log). */
    val id: Int = nextId()

    lateinit var gatt: BluetoothGatt
        private set

    /** Optional human-readable progress sink (on-device debugging). */
    var debugLogger: ((String) -> Unit)? = null

    val opMutex = Mutex()

    private fun shortUuid(uuid: UUID): String = uuid.toString().substring(0, 8)

    @Volatile
    private var lastConnectionState: Int = BluetoothProfile.STATE_DISCONNECTED

    private var connectDeferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    // The five binder-callback signalling fields. The protocol side polls
    // or awaits them on coroutine threads while assignments/resumes happen
    // on binder threads; @Volatile keeps those threads in sync with each
    // other (task 2.4).
    @Volatile
    private var connectContinuation: CancellableContinuation<Unit>? = null
    @Volatile
    private var discoverContinuation: CancellableContinuation<Unit>? = null
    @Volatile
    private var writeContinuation: CancellableContinuation<Unit>? = null
    @Volatile
    private var descriptorContinuation: CancellableContinuation<Unit>? = null
    @Volatile
    private var readContinuation: CancellableContinuation<ByteArray?>? = null

    private val channelsByReadUuid = mutableMapOf<UUID, GattCharacteristicChannel>()

    /** Set when the connection drops; pending operations fail fast. */
    @Volatile
    var disconnected: Boolean = false
        private set

    /**
     * Resumes [continuation] exactly once from a binder callback (task 2.4).
     *
     * The waiting coroutine races the callback: its timeout may have
     * cancelled the continuation, or a newer operation may have replaced
     * the field. A double resume throws IllegalStateException; converting
     * it to a debug log keeps a stale callback from ever throwing out of
     * the binder thread — the same lesson as the deliver()-before-log
     * ordering: an exception in a callback must not swallow the dispatch.
     *
     * Callback order, the CCCD retry path and the connection recipe are
     * not touched by this helper.
     */
    private fun <T> resumeOnce(
        continuation: CancellableContinuation<T>?,
        result: Result<T>,
    ) {
        if (continuation == null) return
        try {
            continuation.resumeWith(result)
        } catch (e: IllegalStateException) {
            try {
                debugLogger?.invoke("gatt: stale callback resume ignored: ${e.message}")
            } catch (_: Exception) {
                // logging must never break the callback
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // State bookkeeping FIRST, logging last: a slow/blocked log sink
            // must never delay or lose a connection-state transition.
            lastConnectionState = newState
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connectDeferred?.complete(Unit)
                        resumeOnce(connectContinuation, Result.success(Unit))
                    } else {
                        val error = AugustException("GATT connect failed, status=$status")
                        connectDeferred?.completeExceptionally(error)
                        resumeOnce(connectContinuation, Result.failure(error))
                    }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    disconnected = true
                    val error = AugustException("GATT disconnected, status=$status")
                    connectDeferred?.completeExceptionally(error)
                    resumeOnce(connectContinuation, Result.failure(error))
                    resumeOnce(discoverContinuation, Result.failure(error))
                    resumeOnce(writeContinuation, Result.failure(error))
                    resumeOnce(descriptorContinuation, Result.failure(error))
                    resumeOnce(readContinuation, Result.failure(error))
                }
            }
            try {
                debugLogger?.invoke("gatt: connectionState status=$status newState=$newState")
            } catch (_: Exception) {
                // logging must never break the callback
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                discoverContinuation?.resume(Unit)
            } else {
                discoverContinuation?.resumeWithException(
                    AugustException("service discovery failed, status=$status")
                )
            }
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            try {
                debugLogger?.invoke("gatt: phyUpdate tx=$txPhy rx=$rxPhy status=$status")
            } catch (_: Exception) {
                // logging must never break the callback
            }
        }

        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            try {
                debugLogger?.invoke("gatt: phyRead tx=$txPhy rx=$rxPhy status=$status")
            } catch (_: Exception) {
                // logging must never break the callback
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(value, status)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            completeRead(characteristic.value, status)
        }

        private fun completeRead(value: ByteArray?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resumeOnce(readContinuation, Result.success(value?.copyOf()))
            } else {
                resumeOnce(
                    readContinuation,
                    Result.failure(AugustException("characteristic read failed, status=$status")),
                )
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resumeOnce(writeContinuation, Result.success(Unit))
            } else {
                resumeOnce(
                    writeContinuation,
                    Result.failure(AugustException("characteristic write failed, status=$status")),
                )
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                resumeOnce(descriptorContinuation, Result.success(Unit))
            } else {
                resumeOnce(
                    descriptorContinuation,
                    Result.failure(AugustException("descriptor write failed, status=$status")),
                )
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            deliver(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(characteristic.uuid, value)
        }

        private fun deliver(uuid: UUID, value: ByteArray) {
            // Deliver FIRST, log second — and never let the log sink throw
            // out of this callback: a sink that touches a View from this
            // binder thread can throw, and if it did so before delivery the
            // frame would be lost while appearing in the log (observed as a
            // handshake timeout although the response had arrived).
            // Delivery is the one thing that must not be lost.
            channelsByReadUuid[uuid]?.deliver(value.copyOf())
            try {
                debugLogger?.invoke(
                    "gatt: indication ${shortUuid(uuid)} len=${value.size} " +
                        value.joinToString("") { "%02x".format(it) }
                )
            } catch (_: Exception) {
                // logging must never break the callback
            }
        }
    }

    fun openGatt(
        context: android.content.Context,
        device: android.bluetooth.BluetoothDevice,
        preferredPhy: Int = 0,
    ) {
        // Pass the preferred PHY (LE 1M) into connectGatt itself rather
        // than adjusting the PHY after connecting.
        gatt = if (preferredPhy != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                context, false, callback, BluetoothDeviceTransportLe, preferredPhy
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDeviceTransportLe)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        } ?: throw AugustException("connectGatt returned null")
    }

    /**
     * Must be called BEFORE [openGatt]: the connected callback can fire on a
     * binder thread in between initiating the connection and registering a
     * waiter, which used to lose the event and hang until timeout (field
     * finding, attempt 1 of the v6 test). The deferred + latched state make
     * [awaitConnected] race-free.
     */
    fun expectConnect() {
        connectDeferred = kotlinx.coroutines.CompletableDeferred()
    }

    suspend fun awaitConnected(timeoutMs: Long) {
        if (lastConnectionState == BluetoothProfile.STATE_CONNECTED) return
        val deferred = connectDeferred
            ?: throw IllegalStateException("expectConnect() must be called before openGatt()")
        try {
            withTimeout(timeoutMs) {
                // Poll the latched state as well as the deferred: in the
                // field, a run logged STATE_CONNECTED yet still timed out
                // waiting, so don't rely on a single wake-up path.
                while (lastConnectionState != BluetoothProfile.STATE_CONNECTED) {
                    if (deferred.isCompleted) {
                        deferred.await() // rethrows a failure, returns on success
                        break
                    }
                    kotlinx.coroutines.delay(50)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw io.github.bataokun.augustble.AugustTimeoutException("GATT connect timed out after ${timeoutMs}ms", e)
        }
    }

    /** Reads a characteristic's value (serialised with all other GATT ops). */
    suspend fun readCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        timeoutMs: Long = 15_000,
    ): ByteArray? {
        opMutex.withLock {
            check(!disconnected) { "GATT is disconnected" }
            val tag = shortUuid(characteristic.uuid)
            try {
                val value = withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<ByteArray?> { cont ->
                        readContinuation = cont
                        cont.invokeOnCancellation { readContinuation = null }
                        if (!gatt.readCharacteristic(characteristic)) {
                            readContinuation = null
                            cont.resumeWithException(
                                AugustException("readCharacteristic() returned false for $tag")
                            )
                        }
                    }
                }
                debugLogger?.invoke("gatt: read $tag len=${value?.size ?: 0}")
                return value
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw io.github.bataokun.augustble.AugustTimeoutException(
                    "characteristic read timed out after ${timeoutMs}ms ($tag)", e
                )
            }
        }
    }

    suspend fun discoverServices(timeoutMs: Long) {
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    discoverContinuation = cont
                    cont.invokeOnCancellation { discoverContinuation = null }
                    if (!gatt.discoverServices()) {
                        discoverContinuation = null
                        cont.resumeWithException(AugustException("discoverServices() returned false"))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw io.github.bataokun.augustble.AugustTimeoutException("service discovery timed out after ${timeoutMs}ms", e)
        }
    }

    fun registerChannel(readUuid: UUID, channel: GattCharacteristicChannel) {
        channelsByReadUuid[readUuid] = channel
    }

    suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        timeoutMs: Long = 15_000,
    ) {
        opMutex.withLock {
            check(!disconnected) { "GATT is disconnected" }
            debugLogger?.invoke(
                "gatt: write ${shortUuid(characteristic.uuid)} len=${value.size} " +
                    value.joinToString("") { "%02x".format(it) }
            )
            // Some characteristics accept only write-without-response; a
            // Write Request to such a characteristic is never answered.
            // Pick the mode from the characteristic's properties.
            val noResponseOnly =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            if (noResponseOnly) {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        value,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = value
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                if (!started) throw AugustException("writeCharacteristic(noResponse) returned false")
                debugLogger?.invoke("gatt: write sent (no response) ${shortUuid(characteristic.uuid)}")
                return@withLock
            }
            try {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        writeContinuation = cont
                        cont.invokeOnCancellation { writeContinuation = null }
                        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(
                                characteristic,
                                value,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                            ) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            characteristic.value = value
                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(characteristic)
                        }
                        if (!started) {
                            writeContinuation = null
                            cont.resumeWithException(AugustException("writeCharacteristic() returned false"))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw io.github.bataokun.augustble.AugustTimeoutException(
                    "characteristic write timed out after ${timeoutMs}ms (${shortUuid(characteristic.uuid)})", e
                )
            }
            debugLogger?.invoke("gatt: write ok ${shortUuid(characteristic.uuid)}")
        }
    }

    /**
     * Enables indications (falling back to notifications) on [characteristic].
     *
     * With `required = false`, a CCCD write timeout is logged and swallowed:
     * on the Gen1 lock + modern Samsung/Android combination, the *second*
     * CCCD write in a connection consistently never calls back, although the
     * write may still have reached the peripheral. Proceeding lets the
     * protocol layer find out (its own writes/reads have timeouts too).
     */
    suspend fun enableIndications(
        characteristic: BluetoothGattCharacteristic,
        timeoutMs: Long = 15_000,
        required: Boolean = true,
    ) {
        opMutex.withLock {
            check(!disconnected) { "GATT is disconnected" }
            val tag = shortUuid(characteristic.uuid)
            debugLogger?.invoke(
                "gatt: enable indications on $tag (props=0x%02x perms=0x%02x)".format(
                    characteristic.properties, characteristic.permissions
                )
            )
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                throw AugustException("setCharacteristicNotification() returned false for $tag")
            }
            val cccd = characteristic.getDescriptor(AugustBle.CCCD_UUID)
                ?: throw AugustException("characteristic $tag has no CCCD")
            val value = when {
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else -> throw AugustException(
                    "characteristic $tag supports neither indicate nor notify"
                )
            }
            // Retry transient CCCD failures in-band instead of failing the
            // whole connection attempt: on Android 16 the second CCCD write
            // has been seen to fail fast with status 133 and then succeed on
            // a fresh connection — retrying the write itself is cheaper
            // than a full reconnect, and the lock dislikes immediate
            // reconnects after aborted sessions.
            // Status failures retry up to 3 attempts; a timeout (15s each)
            // retries only once.
            var attempt = 0
            var timeoutRetriesLeft = 1
            while (true) {
                attempt++
                debugLogger?.invoke("gatt: writing CCCD for $tag (attempt $attempt)")
                try {
                    withTimeout(timeoutMs) {
                        suspendCancellableCoroutine<Unit> { cont ->
                            descriptorContinuation = cont
                            cont.invokeOnCancellation { descriptorContinuation = null }
                            // Legacy write path on all API levels: the API 33+
                            // writeDescriptor(descriptor, value) overload is
                            // implicated in the "second CCCD write never calls
                            // back" behaviour seen with this lock, so use the
                            // long-standing path (deprecated but functional).
                            @Suppress("DEPRECATION")
                            cccd.value = value
                            @Suppress("DEPRECATION")
                            val started = gatt.writeDescriptor(cccd)
                            if (!started) {
                                descriptorContinuation = null
                                cont.resumeWithException(AugustException("writeDescriptor() returned false for $tag"))
                            }
                        }
                    }
                    debugLogger?.invoke("gatt: indications enabled on $tag")
                    return@withLock
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    descriptorContinuation = null
                    if (timeoutRetriesLeft > 0) {
                        timeoutRetriesLeft--
                        debugLogger?.invoke("gatt: CCCD write timed out on $tag — retrying")
                        kotlinx.coroutines.delay(400)
                        continue
                    }
                    if (required) {
                        throw io.github.bataokun.augustble.AugustTimeoutException("CCCD write timed out after ${timeoutMs}ms ($tag)", e)
                    }
                    debugLogger?.invoke(
                        "gatt: CCCD write timed out on $tag — continuing optimistically"
                    )
                    return@withLock
                } catch (e: AugustException) {
                    descriptorContinuation = null
                    if (attempt < 3) {
                        debugLogger?.invoke(
                            "gatt: CCCD write failed on $tag (${e.message}) — retrying"
                        )
                        kotlinx.coroutines.delay(400)
                        continue
                    }
                    if (required) throw e
                    debugLogger?.invoke(
                        "gatt: CCCD write failed on $tag (${e.message}) — continuing optimistically"
                    )
                    return@withLock
                }
            }
        }
    }

    fun close() {
        try {
            gatt.disconnect()
        } catch (_: Exception) {
            // already gone
        }
        try {
            gatt.close()
        } catch (_: Exception) {
            // already gone
        }
    }

    private companion object {
        /** Value of BluetoothDevice.TRANSPORT_LE (API 23+), inlined to keep minSdk simple. */
        const val BluetoothDeviceTransportLe = 2

        private val idCounter = java.util.concurrent.atomic.AtomicInteger(1)
        fun nextId(): Int = idCounter.getAndIncrement()
    }
}

package io.github.bataokun.augustble.sample

import android.app.Activity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.bataokun.augustble.android.AugustAndroid
import io.github.bataokun.augustble.android.AugustPermissions
import io.github.bataokun.augustble.android.ConnectedAugustLock
import io.github.bataokun.augustble.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal operation-check app for the augustble-gen1-kotlin library.
 * Enter the lock's offlineKey (hex) and offlineKeyOffset, then
 * Scan & Connect / Lock / Unlock / Status / Disconnect.
 * Key and offset are remembered in app-private preferences for repeat tests.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connected: ConnectedAugustLock? = null
    private var eventJob: kotlinx.coroutines.Job? = null

    private lateinit var keyInput: EditText
    private lateinit var offsetInput: EditText
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("sample", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun label(text: String) = TextView(this).apply { this.text = text }
        keyInput = EditText(this).apply {
            hint = "offlineKey (hex, 32 chars)"
            setText(prefs.getString("offlineKey", ""))
            isSingleLine = true
        }
        offsetInput = EditText(this).apply {
            hint = "offlineKeyOffset"
            setText(prefs.getString("offset", ""))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        logView = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            gravity = Gravity.START
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

        root.addView(label("August Gen1 — library operation check"))
        root.addView(keyInput)
        root.addView(offsetInput)
        root.addView(button("Request BLE permissions") {
            val missing = AugustPermissions.missing(this)
            if (missing.isEmpty()) {
                log("permissions already granted")
            } else {
                requestPermissions(missing.toTypedArray(), 1)
            }
        })
        root.addView(button("Scan & Connect") { connect() })
        root.addView(button("Unlock") { runOp("unlock") { it.unlock() } })
        root.addView(button("Lock") { runOp("lock") { it.lock() } })
        root.addView(button("Unlatch") { runOp("unlatch") { it.unlatch() } })
        root.addView(button("Secure Lock") { runOp("secureLock") { it.secureLock() } })
        root.addView(button("Status") {
            runOp("status") { conn ->
                log("status: ${conn.status()}")
                try {
                    log("door: ${conn.doorStatus()}")
                } catch (e: Exception) {
                    log("door: failed (${e.message})")
                }
                try {
                    val battery = conn.battery()
                    log("battery: ${battery.voltage}V (${battery.percentage}%)")
                } catch (e: Exception) {
                    log("battery: failed (${e.message})")
                }
            }
        })
        root.addView(button("Copy log") {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("augustble log", logView.text.toString())
            )
            android.widget.Toast.makeText(this, "log copied", android.widget.Toast.LENGTH_SHORT)
                .show()
        })
        root.addView(button("Disconnect") {
            val current = connected
            connected = null
            eventJob?.cancel()
            eventJob = null
            if (current != null) {
                scope.launch {
                    current.close()
                    log("disconnected")
                }
            }
        })
        root.addView(scroll)
        setContentView(root)
    }

    private var connecting = false

    private fun connect() {
        if (connecting) {
            log("connect already in progress — please wait for it to finish or time out")
            return
        }
        val keyHex = keyInput.text.toString().trim()
        val offset = offsetInput.text.toString().trim().toIntOrNull()
        val key = try {
            keyHex.hexToBytes()
        } catch (e: Exception) {
            log("invalid key hex: ${e.message}")
            return
        }
        if (key.size != 16 || offset == null || offset == 0) {
            log("key must be 16 bytes and offset a non-zero integer")
            return
        }
        getSharedPreferences("sample", MODE_PRIVATE).edit()
            .putString("offlineKey", keyHex)
            .putString("offset", offset.toString())
            .apply()
        connecting = true
        scope.launch {
            try {
                log("scanning…")
                val conn = AugustAndroid.scanAndConnect(
                    this@MainActivity, key, offset,
                    onStage = { stage -> log("  $stage") },
                )
                connected = conn
                log("connected & handshake OK (${conn.address})")
                // Surface the lock's asynchronous event frames in the log,
                // labelled as events (they are not command responses).
                eventJob?.cancel()
                eventJob = scope.launch {
                    conn.events.collect { frame ->
                        log("  event: ${frame.joinToString("") { "%02x".format(it) }}")
                    }
                }
            } catch (e: Exception) {
                log("connect failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                connecting = false
            }
        }
    }

    private fun runOp(name: String, op: suspend (ConnectedAugustLock) -> Any?) {
        val current = connected
        if (current == null) {
            log("$name: not connected")
            return
        }
        scope.launch {
            try {
                op(current)
                log("$name: OK")
            } catch (e: Exception) {
                log("$name failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun log(line: String) {
        // The library's stage logger is also invoked from GATT binder
        // threads; touching the view from there can throw (and, in v12,
        // that exception is what swallowed a handshake response on
        // Android 16). Always append on the UI thread.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            logView.append(line + "\n")
        } else {
            logView.post { logView.append(line + "\n") }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

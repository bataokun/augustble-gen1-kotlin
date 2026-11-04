# augustble-gen1-kotlin

A Kotlin library for controlling the **August Smart Lock (1st generation)**
over BLE from modern Android devices. The lock's asynchronous event
frames are routed separately from command responses, and `status()`
speaks the GetStatus protocol — see [PROTOCOL.md](PROTOCOL.md) for the
full protocol model.

> **Unofficial.** Not affiliated with August Home / Yale. Use at your own
> risk. The offline key is effectively a physical key to the lock — store it
> like one (Android Keystore / encrypted storage; never in source control,
> logs, or plain `SharedPreferences` in a production app — the bundled sample
> does the latter only to keep re-testing convenient).

## Modules

| Module | What it is |
|---|---|
| `core` (`io.github.bataokun.augustble`) | Pure Kotlin/JVM protocol implementation: framing, checksums, AES channels (incl. the stateful CBC behaviour), handshake, lock/unlock/status/disconnect, response routing with a separate lock-event flow. No Android dependencies; unit-tested against a fixed transcript and a fake lock implementing the documented frame model. |
| `android` (`io.github.bataokun.augustble.android`) | Android BLE transport: scanning, GATT plumbing (serialised operations, indications), runtime permissions, and the high-level `AugustAndroid` entry point. minSdk 26. |
| `sample` (`io.github.bataokun.augustble.sample`) | Minimal operation-check app: enter key/offset, scan & connect, lock/unlock/unlatch/secure-lock/status (lock + door + battery)/disconnect. Intended for on-device verification, not as a product UI. |

## Scope: first generation, verified; later generations, unverified

This library is developed and verified **only against the 1st-generation
August Smart Lock** — the only hardware available for testing. Later
generations (Gen3 / Gen4, and Yale models) are **not verified**.

That said, the wire protocol is a shared family across generations (the
public yalexs-ble library drives the later locks with it): the offline
key format is the same (16 bytes + a slot number), the handshake and
frame formats are identical, and the same characteristics (…611–…614)
carry the traffic. The known Gen1-specific assumption in this codebase
is the `android` module resolving the command service by its Gen1 UUID
(`bd4ac610…`); later locks expose the same characteristics under a
different service layout (FE24). `core` itself is service-agnostic, so
later-generation support would mainly be a transport/discovery change —
but until it is tested on real hardware it remains unverified.

## Requirements

- Android device with BLE, API 26+ (Android 8.0+). Tested build target:
  compileSdk 34, Kotlin 2.0.21, JDK 17.
- An `offlineKey` (16 bytes, usually written as 32 hex chars) and its
  `offlineKeyOffset` (non-zero integer) that the lock recognises. This
  library takes them as parameters; for how to obtain the currently
  valid pair, see "Retrieving the current key pair" below.

## Quick start

Either use the modules as project modules, or resolve the published
Maven artifacts:

```kotlin
// as project modules
dependencies {
    implementation(project(":android")) // brings in :core transitively
}

// or from Maven (GitHub Packages; or mavenLocal after ./gradlew publishToMavenLocal)
dependencies {
    implementation("io.github.bataokun:augustble-android:0.16.0")
}
```

Both library modules publish to Maven: group `io.github.bataokun`,
artifacts `augustble-core` / `augustble-android`, sources jar included.
See the "Publishing" section under "Building & testing".

Request the BLE permissions first (Android 12+ needs `BLUETOOTH_SCAN` and
`BLUETOOTH_CONNECT` at runtime; the library manifest merges the declarations,
and `neverForLocation` is set in the sample). Helpers:

```kotlin
val missing = AugustPermissions.missing(context)   // io.github.bataokun.augustble.android
if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
```

Then, from a coroutine:

```kotlin
import io.github.bataokun.augustble.android.AugustAndroid
import io.github.bataokun.augustble.hexToBytes

val lock = AugustAndroid.scanAndConnect(
    context = this,
    offlineKey = "0123456789abcdef0123456789abcdef".hexToBytes(),
    offlineKeyOffset = 1,
)
// …or AugustAndroid.connect(context, device, key, offset) with a BluetoothDevice
//    obtained from AugustScanner.scan(context) / your own scanning code.

lock.unlock()
lock.lock()
val status = lock.status()   // LockStatus.LOCKED / UNLOCKED / UNKNOWN
lock.close()                 // protocol disconnect + GATT teardown
```

Errors are reported as `io.github.bataokun.augustble.AugustException` subclasses
(`AugustProtocolException` for checksum/opcode mismatches,
`AugustTimeoutException` when the lock does not answer within
`commandTimeoutMs`, default 10 s) plus standard `SecurityException` when a
runtime permission is missing.

## API reference

### `core` — `io.github.bataokun.augustble`

- **`AugustLock(sessionChannel, secureChannel, offlineKey, offlineKeyOffset, scope, commandTimeoutMs = 10_000, handshakeRandom)`**
  The protocol state machine, transport-agnostic: give it two `GattChannel`s
  (write + indication flow) and it speaks August. `connect()` runs the secure
  handshake; `disconnect()` sends the secure terminate command.
  Operations: `lock()`, `unlock()` (aliases `forceLock()`/`forceUnlock()`),
  `unlatch()` (momentary latch retraction) and `secureLock()` (secure-mode
  lock) run as staged waits — acknowledgment first, then the operation
  response emitted when the motor stops — and return that response frame;
  a failure reported by the lock (e.g. a jam) throws
  **`AugustOperationException`** carrying the `OperationError` result code.
  Status: `status()` returns `LockStatus`; `doorStatus()` and
  `lockAndDoorStatus()` report the door state; `battery()` returns the
  pack voltage and an estimated percentage; `getStatusValue(index)` reads
  any raw GetStatus index. Properties: `isSecure`, `sessionKey`
  (negotiated key, for diagnostics), and `events: Flow<ByteArray>` — the
  lock's asynchronous event frames (decrypted), routed away from the
  response path.
- **`AugustBle`** — the service/characteristic UUID constants and the CCCD UUID.
- **`GattChannel`** — the transport interface the core needs:
  `suspend fun write(value: ByteArray)` and `val incoming: Flow<ByteArray>`.
  Implement it over any BLE stack to use `core` without the `android` module.
- **`hexToBytes()` / `ByteArray.toHexString()`** — helpers for the hex key format.
- **`Checksums`**, **`LockSession`**, **`SecureLockSession`** — protocol
  building blocks (public for testing/reuse; most apps don't need them).

### `android` — `io.github.bataokun.augustble.android`

- **`AugustAndroid.scanAndConnect(context, offlineKey, offlineKeyOffset, …)`**
  — scan for the first lock advertising the August service and connect.
- **`AugustAndroid.connect(context, device, …)`** — connect to a known
  `BluetoothDevice`. Both return a **`ConnectedAugustLock`** with
  `suspend fun lock() / unlock() / unlatch() / secureLock() / status() /
  doorStatus() / lockAndDoorStatus() / battery()`, `suspend fun close()`,
  and `events: Flow<ByteArray>` (the lock's asynchronous event frames).
- **`AugustScanner.scan(context): Flow<ScanResult>`** and
  **`scanForFirst(context, timeoutMs)`** — if you want to do your own device
  picking (e.g. by MAC address or RSSI).
- **`AugustPermissions.required()` / `missing(context)`** — the runtime
  permissions for the device's API level.

### Behaviour notes

- One `ConnectedAugustLock` = one BLE connection + one handshake. Commands
  are request/response, one command on the wire at a time: the protocol
  layer serialises concurrent `suspend` calls internally (a second command
  waits for the first to complete), so callers don't race each other — but
  don't rely on ordering between concurrent calls; await them sequentially
  when the order matters.
- The session channel's encryption is *stateful* across commands within a
  connection (see PROTOCOL.md). Do not share channel state between
  connections; create a fresh connection instead of trying to resume one.
- The lock emits asynchronous event frames around operations; they are
  available on `events` and are never mistaken for command responses.
- Robustness added in 0.16.0 (Phase 2): command mutual exclusion and the
  connect-guard, plus GATT callback resume hardening in the transport.
  These are covered by unit tests at the fake level and were
  **re-verified on real hardware with the full checklist (2026-11-05)**.

## Building & testing

```
./gradlew :core:test            # transcript test + fake-lock test suite (JUnit 5)
./gradlew :sample:assembleDebug # operation-check APK
```

Gradle wrapper is included (Gradle 8.9, AGP 8.5.2). Android SDK location is
taken from `local.properties` (`sdk.dir`) or `ANDROID_HOME`, as usual.

### Publishing

`maven-publish` is configured on both library modules (task 3.1). The
version is a single property in the root `gradle.properties`
(`LIBRARY_VERSION=0.16.0`); sample keeps its own `versionCode`, with
`versionName` kept in step.

```
./gradlew :core:publishToMavenLocal :android:publishToMavenLocal  # local Maven repo, no credentials needed
./gradlew publish                                                 # upload to GitHub Packages
```

- Artifacts: `io.github.bataokun:augustble-core` (plain JVM jar) and
  `io.github.bataokun:augustble-android` (AAR; its POM depends on
  `augustble-core` at the same version), both with sources jars and
  Gradle module metadata. The sample keeps its own `versionCode`; its
  `versionName` follows `LIBRARY_VERSION`.
- GitHub Packages reads the credential pair `gpr.user` / `gpr.key`
  (user-level `gradle.properties` or environment `GITHUB_ACTOR` /
  `GITHUB_TOKEN`): a personal access token with `write:packages`
  scope. Repository URL:
  `https://maven.pkg.github.com/bataokun/augustble-gen1-kotlin`.
- Maven Central is not configured yet — add signing at that point
  (`publishToMavenLocal` stays useful for local iteration regardless).

## Verified on device

**Re-verified on device (0.16.0 code, 2026-11-05, Xiaomi Pad 5 —
DerpFest 17, Android 17 custom ROM with Magisk):** the full checklist
below was re-run with the current code — connect + handshake, unlock /
lock / status several times in one connection (the CBC-chain stress),
and a disconnect + reconnect cycle. **All passed.** This closes the
Phase 2 send-path re-verification: the command-serialisation mutex and
the GATT callback resume hardening are hardware-verified.

- ✅ **Fire TV Cube (2nd gen), Fire OS 7 (Android 9 base)** — sample app
  connects, handshakes and operates the lock (2026-11-01).
- ✅ **Android 16 device** — full success: handshake, lock, unlock and
  status (2026-11-01), using the lock's *current* offline key (see the
  key-state note below). Status reads LOCKED / UNLOCKED correctly; a
  bolt stopped midway reports UNKNOWN_STATIC_POSITION, as the LockState
  value table prescribes.
- ✅ **Xiaomi Pad 5 (DerpFest 17 — Android 17 custom ROM with Magisk)** —
  works (2026-11-01) and is the device used for the 0.16.0 checklist
  re-run above (2026-11-05). Android 17 itself is therefore not the
  blocker; the failure below is specific to that phone's BLE stack.
- ❌ **Galaxy S24 (One UI9.0 Android 17)** — the second CCCD write in a connection is
  never answered, and the link dies with a supervision timeout
  (GATT status 8). The **official August Home app fails on this phone
  too**, and the same write test with nRF Connect is dropped
  identically — this is an incompatibility between that phone's BLE
  stack and the Gen1 lock's Bluetooth 4.0-era firmware, not a defect in
  this library.

  **Connection recipe note:** the connection recipe this library uses —
  preferred PHY LE 1M passed to `connectGatt`, `autoConnect = false`, no
  MTU change, no connection-priority change, no bonding, and reading the
  Device Information service (firmware 0x2A26, serial 0x2A25) around
  enabling indications before the security handshake — mirrors how the
  vendor app connects to this lock. Deviating from it (e.g. assuming
  "the next frame is the response") is what made `status()` return
  UNKNOWN on real hardware in earlier versions.

## ⚠️ Key state is account-managed; old extracted keys go stale

The current official app (August Home) does not read the key from the
legacy store (`PeripheralInfoCache.xml`) that older apps used. It keeps
the lock's key state in its own local database and, at connect time,
uses the OfflineKey whose type is `"loaded"`. The account tracks each
key as created / loaded / deleted, and the app syncs the lock's slots
to that state (setting up "created" keys, deleting "deleted" ones).
Once in sync, repeated use changes nothing — but a key extracted from
the legacy store may have been superseded in the account long ago, and
the first sync then deletes it from the lock itself: it stops working
**everywhere**, even on devices/stacks where it previously worked
(observed 2026-11-01: legacy pair at slot 1 died; the live pair was the
"loaded" entry at slot 2). This is the single most likely cause of a
sudden "security checksum mismatch" at the handshake.

**Calibration can, but does not always, replace the key.** One
calibration run (2026-11-01) appeared to provision a fresh offline key —
the previously extracted pair stopped working afterwards. A later
calibration (2026-11-05) left the live `"loaded"` pair untouched. So
don't assume either way: after calibrating, re-check the `"loaded"`
entry (procedure below) before using this library.

### Retrieving the current key pair

On a **rooted** Android phone with the official August Home app
(com.august.luna) signed in and working with the lock:

1. Open the app's private database:
   `/data/data/com.august.luna/databases/ModelDatabase.db`
   (e.g. copy it out with root and open it in any SQLite browser, or use
   `sqlite3` on-device).
2. Find the lock's row in table `LockData` (match by its `LockID`).
3. Read the `offlineKeys` column: it is a TEXT value holding one JSON
   object per key, joined by the delimiter `@@KEY@@`. Each object has
   `key`, `slot`, `type` (`created` / `loaded` / `deleted`) and `userID`
   fields.
4. The entry with `"type":"loaded"` is the live pair: its `key`
   (hex string) is the `offlineKey` and its `slot` is the
   `offlineKeyOffset` for this library. (At connect time the app itself
   picks the `"loaded"` entry belonging to the currently signed-in user
   the same way.)

Notes:

- Do **not** use `shared_prefs/PeripheralInfoCache.xml` — that is a
  legacy cache the current app no longer reads; it keeps showing the
  stale pair.
- Treat the retrieved key like a house key: enter it directly into your
  app on the device; never paste it into chats, tickets or notes.
- After calibrating the lock in the official app, re-check the
  `"loaded"` entry (below) instead of assuming it is still there — one
  calibration replaced the pair (2026-11-01), another left it unchanged
  (2026-11-05).
- Why "loaded" is the right pick: at connect time the app itself resolves
  the handshake key the same way — it selects the `"loaded"` key of the
  currently signed-in user and reads its `slot`/`key`.

## On-device verification checklist

1. Install the sample APK, grant BLE permissions.
2. Enter the offlineKey/offset, tap **Scan & Connect** near the lock —
   success means the secure key exchange + initialisation both passed
   (a wrong key fails here, at the first response's checksum).
3. **Unlock** / **Lock** and confirm the bolt moves; **Status** should agree
   with the physical state; then **Unlock** again — a second and third
   command in one connection is what exercises the stateful CBC chain.
4. **Disconnect**, reconnect, and repeat once to confirm repeatability.

If step 2 fails, capture an HCI snoop log (Developer options → *Enable
Bluetooth HCI snoop log*) — the frames in PROTOCOL.md are enough to diff
against.

## Further reading

- [`PROTOCOL.md`](PROTOCOL.md) — the wire protocol itself.
- [`CHANGELOG.md`](CHANGELOG.md) — release history (retroactively assembled;
  this project is not a git repository).
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — wire-protocol invariants, the
  Android connection recipe, and hardware-verification expectations for
  code changes.
- Internal research notes and working records are intentionally kept
  outside this repository by the maintainer and are not part of the
  public tree.

## License

MIT — see [LICENSE](LICENSE) and [NOTICE](NOTICE). Original work
© 2014 Dan Walters (augustctl). Portions adapted from
[yalexs-ble](https://github.com/Yale-Libs/yalexs-ble) are under the
Apache License 2.0 — see [APACHE-2.0.txt](APACHE-2.0.txt) and the
attribution in [NOTICE](NOTICE).

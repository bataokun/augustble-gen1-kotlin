# Changelog

All notable changes to this project.

> This project is **not a git repository** (no commit history), so this
> changelog was assembled retroactively (2026-11-04) from the on-device
> verification records and project notes (kept off-repository by the
> maintainer); device-verification evidence is in the README's
> "Verified on device".

## [0.16.0] — 2026-11-04 (sample versionCode 16)

### Added

- **Maven publishing** on both library modules: artifacts
  `io.github.bataokun:augustble-core` (plain JVM jar) and
  `io.github.bataokun:augustble-android` (AAR, POM depends on
  `augustble-core` at the same version), each with sources jars and Gradle
  module metadata. The version is unified in the root `gradle.properties`
  (`LIBRARY_VERSION`). `publishToMavenLocal` works without credentials;
  the GitHub Packages repository (`maven.pkg.github.com/bataokun/augustble-gen1-kotlin`)
  is configured and reads `gpr.user` / `gpr.key` (or `GITHUB_ACTOR` /
  `GITHUB_TOKEN`). Signing will be added if/when Maven Central becomes
  the target.
- **Robustness work** (0.16.0 core changes; re-verified on real hardware
  2026-11-05 with the full on-device checklist on the Xiaomi Pad 5 /
  DerpFest 17 — see README "Verified on device"):
  - command **serialisation**: concurrent `suspend` commands queue on one
    mutex shared by both command routes (plain commands and
    operations), so they can no longer race each other's responses on
    the wire;
  - a **connect-guard**: every public command throws
    `IllegalStateException("call connect() before commands")` when
    issued before `connect()`;
  - **handshake failure containment**: a failure after the session
    becomes "secure" (session-key install, secure-channel
    initialisation) resets `isSecure` and rethrows, so a retried
    `connect()` starts clean; `commandTimeoutMs` must be positive;
  - **GATT callback hardening** in the Android transport: the five
    binder-callback continuation fields are `@Volatile`, and a stale
    double resume is demoted to a debug log instead of an exception on
    the binder thread.
- **Documentation**: internal research notes (public-library parity
  table, official-app observations, protocol/README additions, and
  improvement candidates kept back until re-verified on hardware) were
  gathered and applied to PROTOCOL.md/README; the working records are
  kept off-repository by the maintainer. README "Behaviour notes" now
  describes the internal command serialisation.
- **Test suite**: 15 new unit tests (LockStatus/DoorStatus value tables,
  checksum properties, hand-implemented CBC behaviour, timeout/late-reply
  rerouting, connect/disconnect idempotency) and 4 robustness tests
  (serialisation gate, connect-guard, handshake-failure reset,
  timeout validation) — **27 tests green** via `:core:test` (JUnit 5).

### Removed

- Dead code that invited deviating from the hardware-verified connection
  recipe: `GattLink.requestConnectionPriority` / `setPreferredPhy` and
  the unused `STATUS_INDEX_BATTERY_LEVEL` constant.

## Earlier milestones (pre-0.16.0) — 2026-10-31 … 2026-11-01

Reconstructed from project notes; labels are the versions recorded
there (the sample APK shipped as versionCode 15 near the end).

### [v16] — 2026-11-01

- **yalexs-ble import**: `lock()`/`unlock()` now wait in two stages —
  Acknowledge first, then the operation response — and the operation
  response's result code (byte 0x0F) surfaces jams and mechanical
  failures as `AugustOperationException`; operation bytes added
  (unlock + 0x0A = unlatch, lock + 0x04 = secure-mode lock); door state
  (GetStatus index 46/47) and battery (index 0x0F mV with the AA
  discharge curve ÷ 4); frames that are not exactly 18 bytes are
  dropped **before** decryption so the stateful CBC chain cannot desync.
- **Device verification matrix** recorded in the README ("Verified on
  device"): Fire TV Cube gen2 ✅, Android 16 ✅ (sequential commands,
  current key), Xiaomi Pad 5 / Android 17 ✅, Galaxy / Android 17 ❌ —
  the last being a device-specific BLE-stack incompatibility the
  *official app* reproduces identically.

### [v15] — 2026-10-31

- Completion judged on "a response arrived"; mechanical failures
  (jams, `MECH_*`) could not be reported yet — superseded by the v16
  operation-response handling.

### [v14] — 2026-10-31

- **Event-frame routing**: the frame collector separates response
  candidates from asynchronous event frames. Stale and non-matching
  candidates are re-routed to `events` instead of being treated as the
  response — this fixed `status()` reading `UNKNOWN` because an event
  frame around an operation was misread as the command's answer.

### [v13] — 2026-10-31

- **Deliver-before-log** ordering in GATT callbacks: a logging sink
  touching the UI from the binder thread could throw and swallow the
  indication before it was delivered (handshake timeouts).
- **CCCD write retries** for transient GATT failures (up to three
  attempts on status failures, one on timeouts, 400 ms apart).

## Known key-state behaviour (unchanged machinery, worth repeating)

- The current official app ignores the legacy
  `shared_prefs/PeripheralInfoCache.xml`; a key extracted from it can be
  deleted from the lock itself by the app's first account sync and stops
  working **everywhere**. Retrieve the `"loaded"` entry of the official
  app's database instead (README, "Retrieving the current key pair").
- Calibrating the lock in the official app *can* replace the offline key
  (observed 2026-11-01: the previously retrieved pair stopped working);
  a later calibration left it unchanged (2026-11-05). After calibrating,
  re-check the `"loaded"` entry instead of assuming.

# Contributing

An unofficial Kotlin library for the **August Smart Lock (1st generation)
over BLE**. This page collects the rules that keep changes safe: the
lock's firmware is Bluetooth 4.0-era, its GATT stack is fragile, and
unit tests alone cannot prove that a BLE change works.

## Toolchain

- JDK 17 (toolchain-pinned), Kotlin 2.0.21, Gradle 8.9 wrapper,
  AGP 8.5.2, minSdk 26, compileSdk 34. No CI, no lint config.
- Android SDK location is taken from `local.properties`
  (`sdk.dir`, gitignored) or `ANDROID_HOME`.

## Building & testing

```
./gradlew :core:test             # the only automated tests (JUnit 5)
./gradlew :sample:assembleDebug  # debug APK; the fastest full Android build check
```

`:core` is the only module with tests: a transcript-replay test
(`vectors.json`) plus fake-lock suites. `node tools/gen-vectors.js`
regenerates the vectors fixture (fake fixed keys inside — safe to
commit).

## Wire-protocol invariants (breaking these = real-hardware failures)

The wire format is documented in [PROTOCOL.md](PROTOCOL.md). Do not
"modernise" any of the following without hardware evidence:

- Frames are **exactly 18 bytes**: bytes 0–15 encrypted, 16–17
  plaintext.
- The session channel is AES-128-CBC **with the CBC state carried across
  frames, separate per direction** (`core` implements this as
  `StatefulCbcCipher`). Never build a fresh zero-IV cipher per frame and
  never feed a non-18-byte chunk into it: non-18-byte notifications must
  be dropped **before** decryption, or the CBC chain desyncs everything
  after.
- There is no "next frame = response". The lock emits asynchronous event
  frames around every operation; only frames that **echo the command
  opcode in byte 1** (GetStatus additionally echoes the queried status
  index in byte 4) complete a command. Everything else routes to
  `events`.
- The incoming-frame checksum lives at byte 0x11 (outgoing commands put
  theirs at 0x03; both are the same `-(sum)` invariant).
- `lock()`/`unlock()` finish in two stages: Acknowledge first, then the
  operation response whose byte 0x0F carries the result code (jams
  surface as `AugustOperationException`).

## Android connection recipe — do not "simplify"

Connections survive only with the exact recipe in
`android/src/main/kotlin/io/github/bataokun/augustble/android/GattTransport.kt`:

- preferred PHY LE 1M passed to `connectGatt`, `autoConnect = false`;
- no MTU negotiation, no connection-priority change, no bonding;
- Device Information reads (firmware 0x2A26, then serial 0x2A25)
  around enabling indications;
- deliver-before-log ordering in GATT callbacks (an unguarded log
  exception once swallowed indications);
- CCCD write retries for transient GATT status 133.

Deviating from this recipe is what broke `status()` on real hardware
earlier. Change it only with packet evidence from both directions.

## Verification reality

Unit tests replay canned vectors and fake frames — they cannot prove a
BLE change works. Hardware verification = install `:sample`, run the
on-device checklist in [README.md](README.md) (connect ⇒ unlock/lock ⇒
status ⇒ disconnect ⇒ reconnect; the second and third commands in one
connection are what expose CBC-chain bugs).

When debugging device-side failures, the decisive rule: failures the
companion app reproduces too are device-firmware incompatibilities;
failures only this library shows are ours. Don't blame OS versions
without packet evidence.

## Key handling (security)

The `offlineKey` + slot pair is effectively a house key. Real keys never
belong in source code, logs, issues, or chats. The committed test
vectors use fake fixed keys only.

## Versioning & publishing

The version lives once in the root `gradle.properties`
(`LIBRARY_VERSION`), shared by both library modules and the sample's
`versionName`. Artifacts are `io.github.bataokun:augustble-core` /
`augustble-android` — see README "Publishing" for
`publishToMavenLocal` and GitHub Packages.

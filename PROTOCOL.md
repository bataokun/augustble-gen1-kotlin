# August Smart Lock (1st generation) BLE protocol

Protocol notes for the Gen1 August Smart Lock, as implemented in `core/`:
frame formats, response routing, status semantics, handshake framing and
the stateful CBC behaviour.

> ⚠️ The `offlineKey` + `offlineKeyOffset` pair is all that is needed to open
> the lock. Treat the key like a physical house key.

## BLE layout

GATT service (the lock advertises this UUID):

| Role | UUID |
|---|---|
| Command service | `bd4ac610-0b45-11e3-8ffd-0800200c9a66` |
| Session write | `bd4ac611-0b45-11e3-8ffd-0800200c9a66` |
| Session read (indications) | `bd4ac612-0b45-11e3-8ffd-0800200c9a66` |
| Secure write | `bd4ac613-0b45-11e3-8ffd-0800200c9a66` |
| Secure read (indications) | `bd4ac614-0b45-11e3-8ffd-0800200c9a66` |

Every frame is **18 bytes (0x12)**. Only the **first 16 bytes are encrypted**;
bytes 16–17 travel in plaintext. The default ATT MTU (23 → 20 payload bytes)
is sufficient; no MTU negotiation is needed.

## The two channels

### Secure channel (…613/…614) — AES-128-ECB

Used for key exchange, initialisation and disconnect.

Frame layout (before encryption):

```
offset 0x00: opcode (1 byte)
offset 0x04: payload (up to 8 bytes, in handshake commands)
offset 0x0c: security checksum (uint32 LE)
offset 0x10: 0x0f (constant)
offset 0x11: offlineKeyOffset
```

Security checksum: `-(w0 + w1 + w2) mod 2^32`, where `w0/w1/w2` are the
little-endian uint32 words at offsets 0x00/0x04/0x08. It is computed over the
plaintext frame (with the checksum field zeroed) and written **before**
encryption. A response is valid iff the same formula over the decrypted frame
equals the uint32 at 0x0c.

### Session channel (…611/…612) — AES-128-CBC, *stateful*

Used for lock/unlock/status once the session key exists.

Encryption: AES-128-CBC, zero IV, no padding — **but the cipher object is
created once per `setKey()` and reused**, so the CBC chaining block carries
over from each frame to the next (per direction). Consequence: the same
command encrypts to different bytes every time it is sent within a session,
and a port that creates a fresh zero-IV cipher per message interoperates for
exactly one command and then desynchronises. `core` reproduces the stateful
behaviour by implementing CBC manually over raw AES blocks
(`StatefulCbcCipher`).

#### Frame classes (byte 0, "magic")

Every session frame is classed by its first byte:

| Magic | Class | Meaning |
|---|---|---|
| `0xee` | Command | phone → lock commands |
| `0xbb` | Response | lock → phone command responses; payload parsed per opcode (byte 1) |
| `0xaa` | Acknowledge | lock → phone, header only, no payload |
| `0xcc` | ChecksumError | parser-level error class |

Command frame layout (before encryption):

```
offset 0x00: 0xee (magic)
offset 0x01: opcode
offset 0x03: simple checksum (see below)
offset 0x10: 0x02 (constant)
```

Checksums: for **outgoing commands** the checksum sits at byte 0x03 and
makes the sum of all 18 bytes ≡ 0 (mod 256). For **incoming frames** a
different position is validated: byte 0x11 must equal
`-(sum of bytes 0..0x10) mod 256`. (The two are the same total-sum
invariant whenever the remaining bytes are consistent.)

Naming note: the `Command` / `Response` / `Acknowledge` / `ChecksumError`
names describe the roles on this channel as implemented by `core`; the
security checksum's magic constants and formula are documented above
empirically — verified byte-for-byte on Gen1 hardware.

#### Response matching — read this before touching the code

The lock does **not** answer one frame per command on this channel. Around
every operation it also emits asynchronous **event frames** (lock-event
records and the like). Parsed frames are routed two ways: a per-command
matcher decides whether a frame completes the pending command, and all
frames are additionally offered to event listeners. In `core`:

- Response candidates are frames with magic `0xbb` or `0xaa`; everything
  else is surfaced on `AugustLock.events` and never treated as a response.
- A Response completes the command whose **opcode it echoes in byte 1**;
  for GetStatus it must also **echo the queried status index in byte 4**.
- An Acknowledge completes any command **except GetStatus**, which
  requires the full Response.
- Candidates left over from earlier traffic are drained (and re-routed to
  `events`) before each command is written.

Assuming "the next frame is the response" breaks in the field: after a
lock/unlock, several event frames are queued, so a subsequent status
query reads a stale event frame and reports garbage (observed on real
hardware as `status: UNKNOWN`).

Frame admission: a payload that is not exactly 18 bytes is dropped
before decryption. The session channel's CBC state consumes ciphertext
in 16-byte blocks, so feeding a partial block would desynchronise every
later frame on the connection (adopted from yalexs-ble).

## Session establishment (handshake)

Requires an `offlineKey` (16 bytes) and its `offlineKeyOffset` (a non-zero
slot number) recognised by the lock. This library takes them as parameters.
Note the key state is account-managed: the currently valid pair is
whatever the companion app has marked as its live `"loaded"` key. The
calibration flow has been observed to replace it (2026-11-01), though
another calibration left it unchanged (2026-11-05) — after calibrating,
re-check rather than assume; see the key-state section (with the
retrieval procedure) in README.md.

1. Mobile generates 16 random bytes (`handshakeKeys`).
2. **Key exchange** — secure channel, encrypted with the *offlineKey*:
   command opcode `0x01` with `handshakeKeys[0..8)` at offset 0x04.
   Lock replies with opcode `0x02` and its own 8 random bytes at offset 0x04.
3. Session key (16 bytes) = `handshakeKeys[0..8)` ‖ `lockRandom[0..8)`.
   Both channels are re-keyed to it (the session channel's CBC state starts
   here, with a zero IV).
4. **Initialisation** — secure channel, encrypted with the *session key*:
   command opcode `0x03` with `handshakeKeys[8..16)` at offset 0x04.
   Lock replies with opcode `0x04`.

## Operations (session channel)

| Operation | Command | Response |
|---|---|---|
| Unlock | magic `0xee`, opcode `0x0a` | Acknowledge, then operation response opcode `0x0a` |
| Lock | magic `0xee`, opcode `0x0b` | Acknowledge, then operation response opcode `0x0b` |
| GetStatus | magic `0xee`, opcode `0x02`, byte 4 = status index | Response opcode `0x02`, byte 4 echoes the index, **value = uint32 LE at byte 8** |

Byte 4 of a lock/unlock command is an operation byte: `0x00` is the plain
operation, `0x0A` on Unlock is **unlatch** (momentary latch retraction),
`0x04` on Lock is **secure-mode lock**.

### Operation responses (lock / unlock)

Mechanical operations complete in two stages: the lock first returns an
**Acknowledge** (magic `0xAA`, echoing the opcode and the operation
byte), then — when the motor stops — an **operation response** (magic
`0xBB`, echoing the opcode) whose **byte 0x0F is the result code**:
`0x00` = success; anything else is a failure the caller must surface
(this library throws `AugustOperationException`). The mechanical faults
are 0x1E `MECH_TIMEOUT`, 0x1F `MECH_POSITION`, 0x20 `MECH_MOTPOL`,
0x21 `MECH_TIMEOUT_CAL`, 0x22 `MECH_BACKOFF`, 0x23
`MECH_HANDLE_NOT_LIFTED`; other codes include 0x03 `WRONG_KEY`, 0x32
`VBAT_LOW`, 0x37 `INVALID_OPERATION`, 0x38 `NOT_AUTHORIZED`, 0x3E `BUSY`
— the full table is the `OperationError` enum in `core` (adopted from
the public yalexs-ble library).

Completion semantics: a mechanical operation completes on the
**operation response (magic `0xBB`) only** — the Acknowledge is a
separate frame class and does not complete the command. The result code
at byte 0x0F is numeric; outcome names (`LockSuccess`, `MotorJammed`,
`LowBatAutoUnlock`, …) describe the same events in text form. This
two-stage matching is verified on Gen1 hardware.

### GetStatus status indices (excerpt)

| Index | Name |
|---|---|
| 2 | LOCK_STATE |
| 5 | BATTERY_LEVEL *(name-only observation; unverified on Gen1 hardware — see Battery below)* |
| 0x0F | Battery voltage poll (uint16 millivolts at byte 8) |
| 46 | DOOR_STATE |
| 47 | DOOR_LOCK_STATE (lock state at byte 8, door state at byte 9) |

Note: the lock also **pushes** state changes unsolicited, in the exact
GetStatus-response format (magic `0xbb`, opcode `0x02`, byte 4 = 2, value
at byte 8) — e.g. when the thumb-turn is moved by hand. A midway stop
reports value 7 (UnknownStaticPosition): neither Locked nor Unlocked is
the lock's honest answer there.

Source note: the numeric index mapping is not publicly documented;
the index values above are this library's, verified on Gen1 hardware
(in line with the public yalexs-ble library).

### LOCK_STATE values

| Value | Meaning |
|---|---|
| 0 | Init |
| 1 | Calibrating |
| 2 | Unlocking |
| 3 | Unlocked |
| 4 | Locking |
| 5 | Locked |
| 6 | PolDiscovery |
| 7 | UnknownStaticPosition |
| 8 | None |
| 9 | Unlatching |
| 10 | Unlatched |
| 11 | Integrated_Other_System |
| 12 | SecureMode |
| 13 | Master code not set |
| 14 | Default master code set |
| 15 | Lock not handed |
| 16 | Shabbat Mode |
| 17 | has no Gen1-specific name; reported by the public yalexs-ble library as `INTERNAL_FORCED_LOCKED` (unverified on Gen1 hardware — see note below) |

`LockStatus` distinguishes values 0–12; 13–16 (keypad-oriented states a
Gen1 August lock is not expected to report) map to `UNKNOWN`.

Note: the numeric values in this table are this library's —
hardware-verified where covered above.

### DOOR_STATE values

| Value | Meaning |
|---|---|
| 0 | Unknown / Init |
| 1 | Closed |
| 2 | Ajar |
| 3 | Open |

### Battery

The battery poll (index `0x0F`) returns the pack voltage as a uint16 in
millivolts at byte 8 (little-endian). `core`'s `battery()` converts it
to volts and estimates the remaining percentage per AA cell (pack ÷ 4)
with the discharge curve adopted from yalexs-ble. Index 5
(BATTERY_LEVEL) exists in the status table as well; its response format
is not verified on Gen1 hardware — use `getStatusValue(5)` to probe it.

`BATTERY_LEVEL` (index 5) remains a name-only observation and is unverified
on Gen1 hardware; the millivolt poll (index 0x0F) is what this library
relies on.

## Disconnect

Secure-channel command opcode `0x05` with byte 0x11 zeroed (overriding the
offset); the lock replies with first byte `0x8b`. The BLE connection is then
closed by the transport layer.

Variant note: the wire has a terminate-after-authorization command
(this library's opcode `0x05`) and a terminate-before-authorized variant
for aborting an in-progress handshake (not used by this library). The
recommended flow: send terminate, wait ~1 s without waiting for the
reply, then perform the plain GATT disconnect, falling back to
`BluetoothGatt.disconnect()` when the send fails.

## Known limits

- Implemented: lock / unlock / unlatch / secure-mode lock, status
  (LOCK_STATE, DOOR_STATE, DOOR_LOCK_STATE, battery) and disconnect.
  The protocol has many more commands (GetParameter, keypad management,
  OTA, …) in the same frame model; they are not implemented here.
- Developed and verified for first-generation locks only. Later
  generations share this protocol family (same offline-key format —
  16 bytes plus a slot number — and the same characteristics), but
  differ in service layout (FE24) and are NOT verified; the `android`
  module's `bd4ac610` service lookup is the Gen1-specific assumption.
- This library adds a configurable response timeout (default 10 s),
  because a stalled GATT operation on Android otherwise blocks forever.

## Verification

Two test suites in `core`:

- `AugustLockVectorTest` — replays a fixed transcript (handshake, unlock,
  lock, unlock again, status, disconnect) generated by
  `tools/gen-vectors.js` and asserts byte-identical request traffic.
  Incoming frames in the fixture use the response layout (checksum at
  byte 0x11; GetStatus echo at byte 4, uint32 value at byte 8).
- `AugustLockEventRoutingTest` — a fake lock implementing the frame
  model with live ciphers, including event frames interleaved around
  operations; asserts status reads the GetStatus response (the
  regression test for the on-device `status: UNKNOWN` failure), that a
  reported operation failure raises `AugustOperationException`, that
  unlatch/secure-mode send their operation bytes, that door and battery
  status parse, and that truncated frames are dropped without
  desynchronising the session.

```
node tools/gen-vectors.js   # regenerate vectors.json
./gradlew :core:test
```

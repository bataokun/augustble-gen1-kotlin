#!/usr/bin/env node
'use strict';
/*
 * Generates test vectors for the Kotlin library by simulating BOTH sides
 * of the August Gen1 BLE protocol with Node's crypto, following the frame
 * layouts in PROTOCOL.md: incoming (lock-side) frames use the response
 * layout — checksum at byte 0x11, GetStatus index echo at byte 4, uint32
 * value at byte 8.
 *
 *  - SecureLockSession: AES-128-ECB, no padding, first 16 of 18 bytes encrypted,
 *    security checksum = -(w0+w1+w2) as uint32 LE at offset 0x0c.
 *  - LockSession: AES-128-CBC, zero IV, no padding, first 16 of 18 bytes
 *    encrypted; outgoing commands carry the simple checksum at offset 0x03.
 *    IMPORTANT: the cipher objects are created once per setKey() and keep
 *    being update()d, so the CBC chain persists ACROSS messages.
 *    This generator does the same (one cipher object per side per channel).
 *
 * Output: core/src/test/resources/vectors.json
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

/* ---------- checksums ---------- */
function simpleChecksum(buf) {
  let cs = 0;
  for (let i = 0; i < 0x12; i++) cs = (cs + buf[i]) & 0xff;
  return (-cs) & 0xff;
}
function securityChecksum(buffer) {
  return (0 - (buffer.readUInt32LE(0x00) + buffer.readUInt32LE(0x04) + buffer.readUInt32LE(0x08))) >>> 0;
}

/* ---------- cipher helpers ---------- */
function ecb(key) {
  const enc = crypto.createCipheriv('aes-128-ecb', key, '');
  enc.setAutoPadding(false);
  const dec = crypto.createDecipheriv('aes-128-ecb', key, '');
  dec.setAutoPadding(false);
  return {
    encryptFrame(frame) { const c = enc.update(frame.slice(0, 16)); c.copy(frame, 0); },
    decryptFrame(frame) { const p = dec.update(frame.slice(0, 16)); p.copy(frame, 0); },
  };
}
function cbc(key) {
  const iv = Buffer.alloc(16, 0);
  const enc = crypto.createCipheriv('aes-128-cbc', key, iv);
  enc.setAutoPadding(false);
  const dec = crypto.createDecipheriv('aes-128-cbc', key, iv);
  dec.setAutoPadding(false);
  return {
    encryptFrame(frame) { const c = enc.update(frame.slice(0, 16)); c.copy(frame, 0); },
    decryptFrame(frame) { const p = dec.update(frame.slice(0, 16)); p.copy(frame, 0); },
  };
}

/* ---------- frame builders ---------- */
function secureBuild(opcode, offset) {
  const cmd = Buffer.alloc(0x12, 0);
  cmd.writeUInt8(opcode, 0x00);
  cmd.writeUInt8(0x0f, 0x10);
  cmd.writeUInt8(offset, 0x11);
  return cmd;
}
function sessionBuild(opcode) {
  const cmd = Buffer.alloc(0x12, 0);
  cmd.writeUInt8(0xee, 0x00);
  cmd.writeUInt8(opcode, 0x01);
  cmd.writeUInt8(0x02, 0x10);
  return cmd;
}
function statusBuild() { // session-channel status query
  const cmd = Buffer.alloc(0x12, 0);
  cmd.writeUInt8(0xee, 0x00);
  cmd.writeUInt8(0x02, 0x01);
  cmd.writeUInt8(0x02, 0x04);
  cmd.writeUInt8(0x02, 0x10);
  return cmd;
}

/* ---------- fixed test material ---------- */
const offlineKey = Buffer.from('00112233445566778899aabbccddeeff', 'hex');
const offlineKeyOffset = 1;
const handshakeKeys = Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex'); // mobile random
const lockRandom = Buffer.from('a0a1a2a3a4a5a6a7', 'hex');                      // lock random (8 bytes)

/* mobile side state */
let mobSec = ecb(offlineKey);
let mobSes = null;
/* lock side state */
let lockSec = ecb(offlineKey);
let lockSes = null;
let sessionKey = null;

const steps = [];
function secureExchange(name, plainReq, makePlainResp) {
  const req = Buffer.from(plainReq);
  req.writeUInt32LE(securityChecksum(req), 0x0c);
  const reqPlainHex = req.toString('hex');
  mobSec.encryptFrame(req);
  const wireReq = Buffer.from(req);
  // lock receives
  lockSec.decryptFrame(req);
  if (securityChecksum(req) !== req.readUInt32LE(0x0c)) throw new Error(name + ': bad sec checksum');
  const resp = makePlainResp(req);
  resp.writeUInt32LE(securityChecksum(resp), 0x0c);
  const respPlainHex = resp.toString('hex');
  lockSec.encryptFrame(resp);
  const wireResp = Buffer.from(resp);
  // mobile receives
  mobSec.decryptFrame(resp);
  if (securityChecksum(resp) !== resp.readUInt32LE(0x0c)) throw new Error(name + ': bad resp sec checksum');
  steps.push({ name, channel: 'secure', requestPlainHex: reqPlainHex, requestHex: wireReq.toString('hex'), responseHex: wireResp.toString('hex'), responsePlainHex: respPlainHex });
  return resp;
}
function sessionExchange(name, plainReq, respOpcode, statusByte) {
  const req = Buffer.from(plainReq);
  req.writeUInt8(simpleChecksum(req), 0x03);
  const reqPlainHex = req.toString('hex');
  mobSes.encryptFrame(req);
  const wireReq = Buffer.from(req);
  // lock receives
  lockSes.decryptFrame(req);
  if (simpleChecksum(req) !== 0) throw new Error(name + ': bad simple checksum');
  const resp = Buffer.alloc(0x12, 0);
  resp.writeUInt8(0xbb, 0x00);
  resp.writeUInt8(respOpcode, 0x01);
  if (statusByte !== undefined) {
    // GetStatus response layout: byte 4 echoes the queried status index
    // (2 = LOCK_STATE) and the value is a uint32 LE at byte 8. The
    // response matcher requires the echo.
    resp.writeUInt8(0x02, 0x04);
    resp.writeUInt32LE(statusByte, 0x08);
  }
  resp.writeUInt8(0x02, 0x10);
  // Incoming-frame checksum: byte 0x11 == -(sum of bytes 0..0x10) mod 256
  // (validated for every frame). Outgoing commands put the checksum at
  // byte 3 instead; the total-sum invariant is the same.
  let s = 0;
  for (let i = 0; i <= 0x10; i++) s = (s + resp[i]) & 0xff;
  resp.writeUInt8((-s) & 0xff, 0x11);
  const respPlainHex = resp.toString('hex');
  lockSes.encryptFrame(resp);
  const wireResp = Buffer.from(resp);
  // mobile receives
  mobSes.decryptFrame(resp);
  if (simpleChecksum(resp) !== 0) throw new Error(name + ': bad resp simple checksum');
  if (resp[0] !== 0xbb && resp[0] !== 0xaa) throw new Error(name + ': bad magic');
  steps.push({ name, channel: 'session', requestPlainHex: reqPlainHex, requestHex: wireReq.toString('hex'), responseHex: wireResp.toString('hex'), responsePlainHex: respPlainHex });
  return resp;
}

/* 1. SEC_LOCK_TO_MOBILE_KEY_EXCHANGE (0x01) */
{
  const cmd = secureBuild(0x01, offlineKeyOffset);
  handshakeKeys.copy(cmd, 0x04, 0x00, 0x08);
  secureExchange('sec_key_exchange', cmd, () => {
    const r = Buffer.alloc(0x12, 0);
    r.writeUInt8(0x02, 0x00);
    lockRandom.copy(r, 0x04);
    return r;
  });
  sessionKey = Buffer.alloc(16);
  handshakeKeys.copy(sessionKey, 0x00, 0x00, 0x08);
  lockRandom.copy(sessionKey, 0x08);
  mobSec = ecb(sessionKey); mobSes = cbc(sessionKey);
  lockSec = ecb(sessionKey); lockSes = cbc(sessionKey);
}
/* 2. SEC_INITIALIZATION_COMMAND (0x03) */
{
  const cmd = secureBuild(0x03, offlineKeyOffset);
  handshakeKeys.copy(cmd, 0x04, 0x08, 0x10);
  const resp = secureExchange('sec_init', cmd, () => {
    const r = Buffer.alloc(0x12, 0);
    r.writeUInt8(0x04, 0x00);
    return r;
  });
  if (resp[0] !== 0x04) throw new Error('init resp');
}
/* 3-5. unlock / lock / unlock over the session channel (CBC chain continues) */
sessionExchange('unlock_1', sessionBuild(0x0a), 0x0a);
sessionExchange('lock', sessionBuild(0x0b), 0x0b);
sessionExchange('unlock_2', sessionBuild(0x0a), 0x0a);
/* 6. status: response byte 8 = 0x05 => locked */
{
  const resp = sessionExchange('status', statusBuild(), 0x02, 0x05);
  if (resp.readUInt8(0x08) !== 0x05) throw new Error('status byte');
}
/* 7. DISCONNECT (0x05) over secure channel */
{
  const cmd = secureBuild(0x05, offlineKeyOffset);
  cmd.writeUInt8(0x00, 0x11);
  const resp = secureExchange('disconnect', cmd, () => {
    const r = Buffer.alloc(0x12, 0);
    r.writeUInt8(0x8b, 0x00);
    return r;
  });
  if (resp[0] !== 0x8b) throw new Error('disconnect resp');
}

const out = {
  offlineKeyHex: offlineKey.toString('hex'),
  offlineKeyOffset,
  handshakeKeysHex: handshakeKeys.toString('hex'),
  lockRandomHex: lockRandom.toString('hex'),
  sessionKeyHex: sessionKey.toString('hex'),
  steps,
};
const dest = path.join(__dirname, '..', 'core', 'src', 'test', 'resources', 'vectors.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify(out, null, 2) + '\n');
console.log('wrote', dest);
for (const s of steps) console.log(s.name.padEnd(18), s.requestHex, '->', s.responseHex);

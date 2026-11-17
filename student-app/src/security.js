import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { one, run } from './db.js';

// ---- Password (scrypt) ----
export function hashPassword(pw) {
  const salt = crypto.randomBytes(16);
  const key = crypto.scryptSync(pw, salt, 64, { N: 16384, r: 8, p: 1 });
  return `scrypt$${salt.toString('hex')}$${key.toString('hex')}`;
}
export function verifyPassword(pw, stored) {
  const [alg, saltHex, keyHex] = String(stored).split('$');
  if (alg !== 'scrypt') return false;
  const key = crypto.scryptSync(pw, Buffer.from(saltHex, 'hex'), 64, { N: 16384, r: 8, p: 1 });
  const want = Buffer.from(keyHex, 'hex');
  return want.length === key.length && crypto.timingSafeEqual(want, key);
}
// Strong password: at least 10 characters, with letters and numbers.
export function passwordProblem(pw) {
  if (typeof pw !== 'string' || pw.length < 10) return 'password_too_short';
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) return 'password_needs_letter_and_number';
  return null;
}

export const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
export const randomToken = (n = 32) => crypto.randomBytes(n).toString('base64url');

export function randomCode(len = 6) {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = '';
  const b = crypto.randomBytes(len);
  for (let i = 0; i < len; i++) s += alphabet[b[i] % alphabet.length];
  return s;
}

// ---- TOTP (RFC 6238), two-step sign-in ----
const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
export function base32Encode(buf) {
  let bits = 0, value = 0, out = '';
  for (const byte of buf) {
    value = (value << 8) | byte; bits += 8;
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}
export function base32Decode(str) {
  const clean = str.replace(/=+$/, '').toUpperCase().replace(/\s/g, '');
  let bits = 0, value = 0; const out = [];
  for (const ch of clean) {
    const idx = B32.indexOf(ch);
    if (idx < 0) continue;
    value = (value << 5) | idx; bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 255); bits -= 8; }
  }
  return Buffer.from(out);
}
export const newTotpSecret = () => base32Encode(crypto.randomBytes(20));
export function totpAt(secret, time = Date.now(), step = 30) {
  const counter = Math.floor(time / 1000 / step);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const h = crypto.createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const off = h[h.length - 1] & 15;
  const code = ((h.readUInt32BE(off) & 0x7fffffff) % 1_000_000).toString().padStart(6, '0');
  return code;
}
export function verifyTotp(secret, code, time = Date.now()) {
  if (!/^\d{6}$/.test(String(code || ''))) return false;
  for (const d of [-1, 0, 1]) if (totpAt(secret, time + d * 30_000) === String(code)) return true;
  return false;
}

// ---- Login throttle (stored in the database, survives restarts) ----
const WINDOW_MS = 15 * 60 * 1000;
const MAX_TRIES = 8;
export function throttleCheck(db, key) {
  const row = one(db, 'SELECT * FROM login_attempts WHERE key=?', key);
  if (!row) return { blocked: false };
  if (Date.now() - row.window_start > WINDOW_MS) { run(db, 'DELETE FROM login_attempts WHERE key=?', key); return { blocked: false }; }
  return { blocked: row.count >= MAX_TRIES, retryAfterS: Math.ceil((row.window_start + WINDOW_MS - Date.now()) / 1000) };
}
export function throttleFail(db, key) {
  const row = one(db, 'SELECT * FROM login_attempts WHERE key=?', key);
  if (!row || Date.now() - row.window_start > WINDOW_MS) {
    run(db, 'INSERT OR REPLACE INTO login_attempts (key,count,window_start) VALUES (?,1,?)', key, Date.now());
  } else run(db, 'UPDATE login_attempts SET count=count+1 WHERE key=?', key);
}
export const throttleClear = (db, key) => run(db, 'DELETE FROM login_attempts WHERE key=?', key);

// ---- File encryption (AES-256-GCM), photos in private storage ----
export function saveEncrypted(cfg, orgId, buffer) {
  const dir = path.join(cfg.uploadDir, String(orgId));
  fs.mkdirSync(dir, { recursive: true });
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', cfg.fileKey, iv);
  const enc = Buffer.concat([c.update(buffer), c.final()]);
  const name = randomToken(18) + '.bin';
  const p = path.join(dir, name);
  fs.writeFileSync(p, enc, { mode: 0o600 });
  return { path: path.relative(cfg.uploadDir, p), iv: iv.toString('hex'), tag: c.getAuthTag().toString('hex') };
}
export function readEncrypted(cfg, file) {
  const enc = fs.readFileSync(path.join(cfg.uploadDir, file.path));
  const d = crypto.createDecipheriv('aes-256-gcm', cfg.fileKey, Buffer.from(file.iv, 'hex'));
  d.setAuthTag(Buffer.from(file.tag, 'hex'));
  return Buffer.concat([d.update(enc), d.final()]);
}

// ---- Short-lived links (5 minutes) ----
export function signLink(cfg, fileId, userId, ttlS = 300) {
  const exp = Math.floor(Date.now() / 1000) + ttlS;
  const sig = crypto.createHmac('sha256', cfg.linkSecret).update(`${fileId}.${userId}.${exp}`).digest('base64url');
  return `/api/files/${fileId}?exp=${exp}&sig=${sig}`;
}
export function checkLink(cfg, fileId, userId, exp, sig) {
  if (!exp || !sig || Number(exp) < Date.now() / 1000) return false;
  const want = crypto.createHmac('sha256', cfg.linkSecret).update(`${fileId}.${userId}.${exp}`).digest('base64url');
  return want.length === String(sig).length && crypto.timingSafeEqual(Buffer.from(want), Buffer.from(String(sig)));
}

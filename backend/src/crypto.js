'use strict';
// 应用层信封加密（AES-256-GCM）+ HS256 JWT 签发/校验，全部基于 node:crypto。

const crypto = require('node:crypto');

const IV_LEN = 12;
const TAG_LEN = 16;
const TOKEN_TTL_MS = 30 * 24 * 3600 * 1000; // 30 天

// ---------- 信封 ----------

// key = SHA-256(UTF-8(payloadSecret)) -> 32 字节
function deriveKey(payloadSecret) {
  return crypto.createHash('sha256').update(String(payloadSecret), 'utf8').digest();
}

// 明文对象 -> {"e":"base64(iv||ciphertext||tag)"}
function seal(key, obj) {
  const iv = crypto.randomBytes(IV_LEN);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const data = Buffer.concat([
    cipher.update(Buffer.from(JSON.stringify(obj), 'utf8')),
    cipher.final(),
  ]);
  const tag = cipher.getAuthTag();
  return { e: Buffer.concat([iv, data, tag]).toString('base64') };
}

// {"e":"..."} -> 明文对象；任何失败都抛错（由 http.js 统一转成 bad_envelope）
function open(key, envelope) {
  if (!envelope || typeof envelope.e !== 'string') throw new Error('bad envelope');
  const buf = Buffer.from(envelope.e, 'base64');
  if (buf.length < IV_LEN + TAG_LEN) throw new Error('bad envelope');
  const iv = buf.subarray(0, IV_LEN);
  const tag = buf.subarray(buf.length - TAG_LEN);
  const data = buf.subarray(IV_LEN, buf.length - TAG_LEN);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);
  const plain = Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8');
  const parsed = JSON.parse(plain);
  if (!parsed || typeof parsed !== 'object') throw new Error('bad payload');
  return parsed;
}

// ---------- 密码哈希 scrypt$<saltHex>$<hashHex> ----------

function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(Buffer.from(String(password), 'utf8'), salt, 32, { N: 16384, r: 8, p: 1 });
  return 'scrypt$' + salt.toString('hex') + '$' + hash.toString('hex');
}

function verifyPassword(password, stored) {
  try {
    const parts = String(stored || '').split('$');
    if (parts.length !== 3 || parts[0] !== 'scrypt') return false;
    const salt = Buffer.from(parts[1], 'hex');
    const expect = Buffer.from(parts[2], 'hex');
    const actual = crypto.scryptSync(Buffer.from(String(password), 'utf8'), salt, expect.length, { N: 16384, r: 8, p: 1 });
    return actual.length === expect.length && crypto.timingSafeEqual(actual, expect);
  } catch {
    return false;
  }
}

// ---------- JWT HS256 ----------

function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlDecode(str) {
  const pad = str.length % 4 === 0 ? '' : '='.repeat(4 - (str.length % 4));
  return Buffer.from(str.replace(/-/g, '+').replace(/_/g, '/') + pad, 'base64');
}

function signToken(secret, payload, ttlMs = TOKEN_TTL_MS) {
  const now = Date.now();
  const header = { alg: 'HS256', typ: 'JWT' };
  const body = Object.assign({}, payload, { iat: Math.floor(now / 1000), exp: Math.floor((now + ttlMs) / 1000) });
  const p1 = b64url(JSON.stringify(header));
  const p2 = b64url(JSON.stringify(body));
  const sig = crypto.createHmac('sha256', String(secret)).update(p1 + '.' + p2).digest();
  return p1 + '.' + p2 + '.' + b64url(sig);
}

// 校验签名与过期；返回 payload 或 null
function verifyToken(secret, token) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const expect = crypto.createHmac('sha256', String(secret)).update(parts[0] + '.' + parts[1]).digest();
  const actual = b64urlDecode(parts[2]);
  if (actual.length !== expect.length || !crypto.timingSafeEqual(actual, expect)) return null;
  let payload;
  try {
    payload = JSON.parse(b64urlDecode(parts[1]).toString('utf8'));
  } catch {
    return null;
  }
  if (!payload || typeof payload !== 'object') return null;
  if (typeof payload.exp !== 'number' || payload.exp * 1000 <= Date.now()) return null;
  if (!Number.isInteger(payload.uid)) return null;
  return payload;
}

module.exports = {
  deriveKey, seal, open,
  hashPassword, verifyPassword,
  signToken, verifyToken,
  TOKEN_TTL_MS,
};

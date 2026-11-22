import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes } from 'node:crypto';

/** Tokens are random 256-bit values; only their SHA-256 is stored, so a DB leak can't be replayed. */
export const hashToken = (t) => createHash('sha256').update(t).digest('hex');
export const newToken = () => randomBytes(32).toString('base64url');

export function openDb(path) {
  const db = new DatabaseSync(path);
  db.exec(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS login_tokens (
      token_hash TEXT PRIMARY KEY,
      phone      TEXT NOT NULL,
      expires_at INTEGER NOT NULL,
      used_at    INTEGER
    );
    CREATE TABLE IF NOT EXISTS sessions (
      token_hash TEXT PRIMARY KEY,
      phone      TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS sessions_phone ON sessions(phone);
    CREATE TABLE IF NOT EXISTS sms_log (
      phone      TEXT NOT NULL,
      ip         TEXT,
      created_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS sms_log_phone ON sms_log(phone, created_at);
    CREATE INDEX IF NOT EXISTS sms_log_ip ON sms_log(ip, created_at);
    CREATE TABLE IF NOT EXISTS profiles (
      phone            TEXT PRIMARY KEY,
      first_name       TEXT NOT NULL,
      last_name        TEXT NOT NULL DEFAULT '',
      city             TEXT NOT NULL,
      region           TEXT NOT NULL,
      bio              TEXT NOT NULL DEFAULT '',
      photo            BLOB,
      photo_updated_at INTEGER,
      updated_at       INTEGER NOT NULL
    );
  `);

  const q = {
    insertLogin: db.prepare('INSERT INTO login_tokens (token_hash, phone, expires_at) VALUES (?, ?, ?)'),
    // Atomic single-use consume: only one concurrent caller can flip used_at.
    consumeLogin: db.prepare(
      'UPDATE login_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? RETURNING phone'),
    insertSession: db.prepare('INSERT INTO sessions (token_hash, phone, created_at, expires_at) VALUES (?, ?, ?, ?)'),
    getSession: db.prepare('SELECT phone, expires_at FROM sessions WHERE token_hash = ? AND expires_at > ?'),
    deleteSession: db.prepare('DELETE FROM sessions WHERE token_hash = ?'),
    deleteSessionsForPhone: db.prepare('DELETE FROM sessions WHERE phone = ?'),
    logSms: db.prepare('INSERT INTO sms_log (phone, ip, created_at) VALUES (?, ?, ?)'),
    countByPhone: db.prepare('SELECT COUNT(*) AS n FROM sms_log WHERE phone = ? AND created_at > ?'),
    countByIp: db.prepare('SELECT COUNT(*) AS n FROM sms_log WHERE ip = ? AND created_at > ?'),
    invalidatePhoneLogins: db.prepare('UPDATE login_tokens SET used_at = ? WHERE phone = ? AND used_at IS NULL'),
    getProfile: db.prepare(
      `SELECT first_name, last_name, city, region, bio, photo IS NOT NULL AS has_photo, photo_updated_at
       FROM profiles WHERE phone = ?`),
    upsertProfile: db.prepare(
      `INSERT INTO profiles (phone, first_name, last_name, city, region, bio, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(phone) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name,
         city = excluded.city, region = excluded.region, bio = excluded.bio, updated_at = excluded.updated_at`),
    setPhoto: db.prepare('UPDATE profiles SET photo = ?, photo_updated_at = ? WHERE phone = ?'),
    getPhoto: db.prepare('SELECT photo FROM profiles WHERE phone = ? AND photo IS NOT NULL'),
    prune: [
      db.prepare('DELETE FROM login_tokens WHERE expires_at < ?'),
      db.prepare('DELETE FROM sessions WHERE expires_at < ?'),
      db.prepare('DELETE FROM sms_log WHERE created_at < ?'),
    ],
  };

  return {
    raw: db,
    createLoginToken(phone, ttlMs, now = Date.now()) {
      const token = newToken();
      // A newer link supersedes older unused ones for this phone.
      q.invalidatePhoneLogins.run(now, phone);
      q.insertLogin.run(hashToken(token), phone, now + ttlMs);
      return token;
    },
    /** Returns the phone if the token was valid and unused, else null. Burns the token. */
    consumeLoginToken(token, now = Date.now()) {
      return q.consumeLogin.get(now, hashToken(token), now)?.phone ?? null;
    },
    createSession(phone, ttlMs, now = Date.now()) {
      const token = newToken();
      const expiresAt = now + ttlMs;
      q.insertSession.run(hashToken(token), phone, now, expiresAt);
      return { token, expiresAt };
    },
    getSession(token, now = Date.now()) {
      const row = q.getSession.get(hashToken(token), now);
      return row ? { phone: row.phone, expiresAt: row.expires_at } : null;
    },
    deleteSession: (token) => q.deleteSession.run(hashToken(token)).changes > 0,
    logSms: (phone, ip, now = Date.now()) => q.logSms.run(phone, ip, now),
    smsCountByPhone: (phone, since) => q.countByPhone.get(phone, since).n,
    smsCountByIp: (ip, since) => q.countByIp.get(ip, since).n,
    getProfile(phone) {
      const r = q.getProfile.get(phone);
      return r ? {
        firstName: r.first_name, lastName: r.last_name, city: r.city, region: r.region, bio: r.bio,
        hasPhoto: r.has_photo === 1, photoUpdatedAt: r.photo_updated_at ?? null,
      } : null;
    },
    upsertProfile(phone, p, now = Date.now()) {
      q.upsertProfile.run(phone, p.firstName, p.lastName, p.city, p.region, p.bio, now);
    },
    /** Pass null to remove. Returns false if the profile doesn't exist. */
    setProfilePhoto: (phone, bytes, now = Date.now()) =>
      q.setPhoto.run(bytes, bytes ? now : null, phone).changes > 0,
    getProfilePhoto(phone) {
      const r = q.getPhoto.get(phone);
      return r ? Buffer.from(r.photo) : null;
    },
    prune(now = Date.now()) {
      q.prune[0].run(now - 86_400_000);
      q.prune[1].run(now);
      q.prune[2].run(now - 86_400_000);
    },
    close: () => db.close(),
  };
}

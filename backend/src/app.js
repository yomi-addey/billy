import { createServer } from 'node:http';
import { openDb } from './db.js';
import { createSmsSender } from './sms.js';

const E164 = /^\+[1-9]\d{6,14}$/;
const HOUR = 3_600_000;
const MAX_BODY = 4096;
const MAX_PHOTO = 1024 * 1024;

class HttpError extends Error {
  constructor(status) { super(`HTTP ${status}`); this.status = status; }
}

async function readBody(req, max) {
  if (Number(req.headers['content-length']) > max) throw new HttpError(413);
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > max) throw new HttpError(413);
    chunks.push(c);
  }
  return Buffer.concat(chunks);
}

async function readJson(req) {
  const body = await readBody(req, MAX_BODY);
  try {
    const v = JSON.parse(body.toString('utf8'));
    if (v === null || typeof v !== 'object') throw 0;
    return v;
  } catch {
    throw new HttpError(400);
  }
}

function bearer(req) {
  const m = /^Bearer (\S+)$/.exec(req.headers.authorization ?? '');
  return m ? m[1] : null;
}

// Control characters (incl. newlines) aren't allowed in any profile field except bio, which may contain newlines.
const CONTROL = /[\u0000-\u001f\u007f]/;
const CONTROL_EXCEPT_NEWLINE = /[\u0000-\u0009\u000b-\u001f\u007f]/;

/** Validates a PUT /profile body. Only these fields are accepted; anything else is ignored. */
function parseProfile(body) {
  const field = (name, { min, max, required = true, multiline = false }) => {
    const v = body[name] ?? (required ? undefined : '');
    if (typeof v !== 'string') throw new HttpError(400);
    const s = multiline ? v.trim().replace(/\r\n?/g, '\n') : v.trim();
    if (s.length < min || s.length > max) throw new HttpError(400);
    if ((multiline ? CONTROL_EXCEPT_NEWLINE : CONTROL).test(s)) throw new HttpError(400);
    return s;
  };
  return {
    firstName: field('firstName', { min: 1, max: 50 }),
    lastName: field('lastName', { min: 0, max: 50, required: false }),
    city: field('city', { min: 1, max: 80 }),
    region: field('region', { min: 1, max: 80 }),
    bio: field('bio', { min: 0, max: 280, required: false, multiline: true }),
  };
}

const isJpeg = (b) => b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;

export function createApp(config, { db = openDb(config.dbPath), sms = createSmsSender(config.twilio), now = Date.now } = {}) {
  const base = config.basePath;

  const clientIp = (req) =>
    (config.trustProxy && req.headers['x-forwarded-for']?.split(',').pop().trim()) || req.socket.remoteAddress || 'unknown';

  function send(res, status, body, contentType) {
    if (body === undefined) {
      res.writeHead(status, { 'Cache-Control': 'no-store' }).end();
      return;
    }
    if (Buffer.isBuffer(body)) {
      res.writeHead(status, {
        'Content-Type': contentType,
        'Content-Length': body.length,
        'Cache-Control': 'private, no-store',
        'X-Content-Type-Options': 'nosniff',
      }).end(body);
      return;
    }
    const isStr = typeof body === 'string';
    res.writeHead(status, {
      'Content-Type': isStr ? 'text/html; charset=utf-8' : 'application/json',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    }).end(isStr ? body : JSON.stringify(body));
  }

  /** Returns the signed-in phone number or throws 401. */
  function requireSession(req) {
    const token = bearer(req);
    const s = token && db.getSession(token, now());
    if (!s) throw new HttpError(401);
    return s.phone;
  }

  const routes = {
    // Text a one-time sign-in link. Always 204 for well-formed requests, so the response
    // doesn't reveal whether a number is known or rate limited.
    async 'POST /auth/sms-link'(req, res) {
      const { phone } = await readJson(req);
      if (typeof phone !== 'string' || !E164.test(phone)) throw new HttpError(400);
      const ip = clientIp(req);
      const t = now();
      if (db.smsCountByPhone(phone, t - HOUR) >= config.maxSmsPerPhonePerHour ||
          db.smsCountByIp(ip, t - HOUR) >= config.maxSmsPerIpPerHour) {
        return send(res, 429);
      }
      db.logSms(phone, ip, t);
      const token = db.createLoginToken(phone, config.loginTokenTtlMs, t);
      const link = `https://${config.authHost}/signin?token=${token}`;
      const mins = Math.round(config.loginTokenTtlMs / 60_000);
      try {
        await sms.send(phone, `Your Billy sign-in link (expires in ${mins} min): ${link}`);
      } catch (e) {
        console.error('sms send failed:', e.message);
        throw new HttpError(502);
      }
      send(res, 204);
    },

    async 'POST /auth/verify'(req, res) {
      const { token } = await readJson(req);
      if (typeof token !== 'string' || token.length > 200) throw new HttpError(400);
      const phone = db.consumeLoginToken(token, now());
      if (!phone) throw new HttpError(401);
      const session = db.createSession(phone, config.sessionTtlMs, now());
      send(res, 200, { sessionToken: session.token, expiresAt: new Date(session.expiresAt).toISOString() });
    },

    // Lets the app check a stored session on launch.
    async 'GET /auth/session'(req, res) {
      const token = bearer(req);
      const s = token && db.getSession(token, now());
      if (!s) throw new HttpError(401);
      send(res, 200, { phone: s.phone, expiresAt: new Date(s.expiresAt).toISOString() });
    },

    async 'POST /auth/logout'(req, res) {
      const token = bearer(req);
      if (token) db.deleteSession(token);
      send(res, 204); // idempotent
    },

    // 404 means the user hasn't created a profile yet.
    async 'GET /profile'(req, res) {
      const profile = db.getProfile(requireSession(req));
      if (!profile) throw new HttpError(404);
      send(res, 200, profile);
    },

    async 'PUT /profile'(req, res) {
      const phone = requireSession(req);
      const profile = parseProfile(await readJson(req));
      db.upsertProfile(phone, profile, now());
      send(res, 200, db.getProfile(phone));
    },

    // Raw JPEG body. The app re-encodes photos before upload, which strips EXIF (incl. GPS).
    async 'PUT /profile/photo'(req, res) {
      const phone = requireSession(req);
      const body = await readBody(req, MAX_PHOTO);
      if (!isJpeg(body)) throw new HttpError(400);
      if (!db.setProfilePhoto(phone, body, now())) throw new HttpError(404);
      send(res, 204);
    },

    async 'GET /profile/photo'(req, res) {
      const photo = db.getProfilePhoto(requireSession(req));
      if (!photo) throw new HttpError(404);
      send(res, 200, photo, 'image/jpeg');
    },

    async 'DELETE /profile/photo'(req, res) {
      const phone = requireSession(req);
      if (!db.setProfilePhoto(phone, null, now())) throw new HttpError(404);
      send(res, 204);
    },
  };

  const server = createServer(async (req, res) => {
    try {
      const { pathname } = new URL(req.url, 'http://x');

      if (req.method === 'GET' && pathname === '/.well-known/assetlinks.json' && config.androidCertSha256) {
        return send(res, 200, [{
          relation: ['delegate_permission/common.handle_all_urls'],
          target: { namespace: 'android_app', package_name: config.androidPackage, sa256_cert_fingerprints: [config.androidCertSha256] },
        }]);
      }
      // Shown only when the link is opened without the app installed/verified.
      if (req.method === 'GET' && pathname === '/signin') {
        return send(res, 200, '<!doctype html><meta name="viewport" content="width=device-width"><title>Billy</title><p>Open this link on the phone where Billy is installed.');
      }

      if (!pathname.startsWith(base + '/')) throw new HttpError(404);
      const handler = routes[`${req.method} ${pathname.slice(base.length)}`];
      if (!handler) throw new HttpError(404);
      await handler(req, res);
    } catch (e) {
      if (e instanceof HttpError) return send(res, e.status, e.status === 204 ? undefined : { error: e.status });
      console.error(e);
      send(res, 500, { error: 500 });
    }
  });

  const pruneTimer = setInterval(() => db.prune(now()), HOUR);
  pruneTimer.unref();
  server.on('close', () => { clearInterval(pruneTimer); db.close(); });

  return server;
}

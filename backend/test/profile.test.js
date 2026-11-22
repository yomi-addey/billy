import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/config.js';
import { createApp } from '../src/app.js';
import { openDb } from '../src/db.js';

async function setup() {
  const sent = [];
  const config = loadConfig({ dbPath: ':memory:' });
  const server = createApp(config, {
    db: openDb(':memory:'),
    sms: { send: async (to, body) => { sent.push({ to, body }); } },
    now: () => 1_000_000,
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}${config.basePath}`;

  const call = (path, { method = 'GET', body, token, raw, contentType } = {}) => fetch(url + path, {
    method,
    headers: {
      'Content-Type': contentType ?? 'application/json',
      ...(token && { Authorization: `Bearer ${token}` }),
    },
    body: raw ?? (body === undefined ? undefined : JSON.stringify(body)),
  });

  async function signIn(phone) {
    await call('/auth/sms-link', { method: 'POST', body: { phone } });
    const token = new URL(/https:\/\/\S+/.exec(sent.at(-1).body)[0]).searchParams.get('token');
    const r = await call('/auth/verify', { method: 'POST', body: { token } });
    return (await r.json()).sessionToken;
  }

  return { server, call, signIn };
}

const PROFILE = { firstName: 'Ada', lastName: 'Lovelace', city: 'Boston', region: 'Massachusetts', bio: 'Splits fairly.' };
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 0xff, 0xd9]);

test('profile routes require a session', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  assert.equal((await s.call('/profile')).status, 401);
  assert.equal((await s.call('/profile', { method: 'PUT', body: PROFILE })).status, 401);
  assert.equal((await s.call('/profile/photo', { method: 'PUT', raw: JPEG, contentType: 'image/jpeg' })).status, 401);
  assert.equal((await s.call('/profile/photo')).status, 401);
  assert.equal((await s.call('/profile', { token: 'bogus' })).status, 401);
});

test('first-time user has no profile; create then read it back', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  const token = await s.signIn('+14155550123');

  assert.equal((await s.call('/profile', { token })).status, 404);

  const put = await s.call('/profile', { method: 'PUT', token, body: { ...PROFILE, firstName: '  Ada  ', phone: '+1999', extra: 1 } });
  assert.equal(put.status, 200);
  const expected = { ...PROFILE, hasPhoto: false, photoUpdatedAt: null };
  assert.deepEqual(await put.json(), expected);

  const get = await s.call('/profile', { token });
  assert.equal(get.status, 200);
  assert.deepEqual(await get.json(), expected);

  // Update (upsert) replaces fields.
  const upd = await s.call('/profile', { method: 'PUT', token, body: { ...PROFILE, city: 'Cambridge' } });
  assert.equal((await upd.json()).city, 'Cambridge');
});

test('last name and bio are optional', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  const token = await s.signIn('+14155550123');
  const r = await s.call('/profile', { method: 'PUT', token, body: { firstName: 'Cher', city: 'Boston', region: 'MA' } });
  assert.equal(r.status, 200);
  const p = await r.json();
  assert.equal(p.lastName, '');
  assert.equal(p.bio, '');
});

test('rejects invalid profile fields', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  const token = await s.signIn('+14155550123');
  const bad = [
    { ...PROFILE, firstName: '   ' },
    { ...PROFILE, firstName: undefined },
    { ...PROFILE, firstName: 42 },
    { ...PROFILE, lastName: 7 },
    { ...PROFILE, firstName: 'x'.repeat(51) },
    { ...PROFILE, city: '' },
    { ...PROFILE, region: undefined },
    { ...PROFILE, bio: 'x'.repeat(281) },
    { ...PROFILE, firstName: 'Ada\nLovelace' },
    { ...PROFILE, bio: 'tab\u0000null' },
  ];
  for (const body of bad) {
    assert.equal((await s.call('/profile', { method: 'PUT', token, body })).status, 400, JSON.stringify(body));
  }
  assert.equal((await s.call('/profile', { token })).status, 404);
  // Multi-line bios are fine.
  assert.equal((await s.call('/profile', { method: 'PUT', token, body: { ...PROFILE, bio: 'line 1\nline 2' } })).status, 200);
});

test('photo upload, download and removal', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  const token = await s.signIn('+14155550123');
  const upload = (raw) => s.call('/profile/photo', { method: 'PUT', token, raw, contentType: 'image/jpeg' });

  // Needs a profile first.
  assert.equal((await upload(JPEG)).status, 404);
  await s.call('/profile', { method: 'PUT', token, body: PROFILE });
  assert.equal((await s.call('/profile/photo', { token })).status, 404);

  assert.equal((await upload(JPEG)).status, 204);
  const get = await s.call('/profile/photo', { token });
  assert.equal(get.status, 200);
  assert.equal(get.headers.get('content-type'), 'image/jpeg');
  assert.deepEqual(Buffer.from(await get.arrayBuffer()), JPEG);
  const p = await (await s.call('/profile', { token })).json();
  assert.equal(p.hasPhoto, true);
  assert.equal(p.photoUpdatedAt, 1_000_000);

  // Updating text fields keeps the photo.
  await s.call('/profile', { method: 'PUT', token, body: { ...PROFILE, bio: '' } });
  assert.equal((await (await s.call('/profile', { token })).json()).hasPhoto, true);

  assert.equal((await s.call('/profile/photo', { method: 'DELETE', token })).status, 204);
  assert.equal((await s.call('/profile/photo', { token })).status, 404);
  assert.equal((await (await s.call('/profile', { token })).json()).hasPhoto, false);
});

test('rejects non-JPEG and oversized photos', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  const token = await s.signIn('+14155550123');
  await s.call('/profile', { method: 'PUT', token, body: PROFILE });
  const upload = (raw) => s.call('/profile/photo', { method: 'PUT', token, raw, contentType: 'image/jpeg' });

  assert.equal((await upload(Buffer.from('<svg></svg>'))).status, 400);
  assert.equal((await upload(Buffer.alloc(0))).status, 400);
  const big = Buffer.concat([JPEG, Buffer.alloc(1024 * 1024)]);
  assert.equal((await upload(big)).status, 413);
});

test('profiles are isolated per phone', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  const a = await s.signIn('+14155550123');
  const b = await s.signIn('+14155550124');
  await s.call('/profile', { method: 'PUT', token: a, body: PROFILE });
  assert.equal((await s.call('/profile', { token: b })).status, 404);
  await s.call('/profile', { method: 'PUT', token: b, body: { ...PROFILE, firstName: 'Grace' } });
  assert.equal((await (await s.call('/profile', { token: a })).json()).firstName, 'Ada');
});

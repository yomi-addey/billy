import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../src/config.js';
import { createApp } from '../src/app.js';
import { openDb } from '../src/db.js';

async function setup(overrides = {}) {
  let clock = 1_000_000;
  const sent = [];
  const config = loadConfig({ dbPath: ':memory:', ...overrides });
  const server = createApp(config, {
    db: openDb(':memory:'),
    sms: { send: async (to, body) => { sent.push({ to, body }); } },
    now: () => clock,
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}${config.basePath}`;
  const call = (path, { method = 'POST', body, token } = {}) => fetch(url + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token && { Authorization: `Bearer ${token}` }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const tokenFromSms = () => new URL(/https:\/\/\S+/.exec(sent.at(-1).body)[0]).searchParams.get('token');
  return { server, call, sent, tokenFromSms, advance: (ms) => { clock += ms; }, config };
}

const PHONE = '+14155550123';

test('full flow: send link, verify, session valid, logout invalidates', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());

  assert.equal((await s.call('/auth/sms-link', { body: { phone: PHONE } })).status, 204);
  assert.equal(s.sent[0].to, PHONE);

  const v = await s.call('/auth/verify', { body: { token: s.tokenFromSms() } });
  assert.equal(v.status, 200);
  const { sessionToken } = await v.json();

  assert.equal((await s.call('/auth/session', { method: 'GET', token: sessionToken })).status, 200);
  assert.equal((await s.call('/auth/logout', { token: sessionToken })).status, 204);
  assert.equal((await s.call('/auth/session', { method: 'GET', token: sessionToken })).status, 401);
});

test('login token is single use', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  await s.call('/auth/sms-link', { body: { phone: PHONE } });
  const token = s.tokenFromSms();
  assert.equal((await s.call('/auth/verify', { body: { token } })).status, 200);
  assert.equal((await s.call('/auth/verify', { body: { token } })).status, 401);
});

test('login token expires', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  await s.call('/auth/sms-link', { body: { phone: PHONE } });
  s.advance(s.config.loginTokenTtlMs + 1);
  assert.equal((await s.call('/auth/verify', { body: { token: s.tokenFromSms() } })).status, 401);
});

test('a newer link supersedes the older one', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  await s.call('/auth/sms-link', { body: { phone: PHONE } });
  const first = s.tokenFromSms();
  await s.call('/auth/sms-link', { body: { phone: PHONE } });
  assert.equal((await s.call('/auth/verify', { body: { token: first } })).status, 401);
  assert.equal((await s.call('/auth/verify', { body: { token: s.tokenFromSms() } })).status, 200);
});

test('session expires', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  await s.call('/auth/sms-link', { body: { phone: PHONE } });
  const { sessionToken } = await (await s.call('/auth/verify', { body: { token: s.tokenFromSms() } })).json();
  s.advance(s.config.sessionTtlMs - 1000);
  assert.equal((await s.call('/auth/session', { method: 'GET', token: sessionToken })).status, 200);
  s.advance(2000);
  assert.equal((await s.call('/auth/session', { method: 'GET', token: sessionToken })).status, 401);
});

test('rejects bad phone numbers and bad tokens', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  for (const phone of ['4155550123', '+0123456', '+1abc', 5, null]) {
    assert.equal((await s.call('/auth/sms-link', { body: { phone } })).status, 400, String(phone));
  }
  assert.equal(s.sent.length, 0);
  assert.equal((await s.call('/auth/verify', { body: { token: 'nope' } })).status, 401);
  assert.equal((await s.call('/auth/verify', { body: {} })).status, 400);
  assert.equal((await s.call('/auth/session', { method: 'GET' })).status, 401);
});

test('rate limits SMS per phone', async (t) => {
  const s = await setup();
  t.after(() => s.server.close());
  for (let i = 0; i < s.config.maxSmsPerPhonePerHour; i++) {
    assert.equal((await s.call('/auth/sms-link', { body: { phone: PHONE } })).status, 204);
  }
  assert.equal((await s.call('/auth/sms-link', { body: { phone: PHONE } })).status, 429);
  s.advance(3_600_001);
  assert.equal((await s.call('/auth/sms-link', { body: { phone: PHONE } })).status, 204);
});

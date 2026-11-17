import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { boot, setupOrg, loginWithMfa, activate } from './helpers.js';
import { totpAt, verifyTotp, base32Decode, base32Encode, hashPassword, verifyPassword, passwordProblem } from '../src/security.js';
import { throttleCheck } from '../src/security.js';

let ctx;
before(async () => { ctx = await boot(); });
after(async () => { await ctx.close(); });

test('password hashing and strength rules', () => {
  const h = hashPassword('Secret12345');
  assert.ok(verifyPassword('Secret12345', h));
  assert.ok(!verifyPassword('secret12345', h));
  assert.equal(passwordProblem('short1'), 'password_too_short');
  assert.equal(passwordProblem('onlyletterslong'), 'password_needs_letter_and_number');
  assert.equal(passwordProblem('Letters12345'), null);
});

test('TOTP RFC 6238 test vector', () => {
  const secret = base32Encode(Buffer.from('12345678901234567890'));
  assert.equal(base32Decode(secret).toString(), '12345678901234567890');
  // RFC 6238: T=59 -> 94287082 (8 digits); 6 digits = 287082
  assert.equal(totpAt(secret, 59_000), '287082');
  assert.ok(verifyTotp(secret, totpAt(secret)));
  assert.ok(!verifyTotp(secret, '000000') || totpAt(secret) === '000000');
});

test('setup runs once and the admin must enrol two-step sign-in', async () => {
  const c = ctx.client();
  const r = await c.post('/api/setup', { orgName: 'Alpha School', name: 'Admin', username: 'admin', password: 'AdminPass123', defaultLang: 'ur' });
  assert.equal(r.status, 200);
  // after setup there is a session but no MFA yet: admin API is closed
  const blocked = await c.get('/api/admin/users');
  assert.equal(blocked.status, 401);
  assert.equal(blocked.data.error, 'mfa_required');
  const again = await ctx.client().post('/api/setup', { orgName: 'X', name: 'Y', username: 'zzz', password: 'AdminPass123' });
  assert.equal(again.status, 403);
  const me = await c.get('/api/me');
  assert.equal(me.data.mfaPending, 'enroll');
  assert.equal(me.data.org.defaultLang, 'ur');
  const s = await c.get('/api/mfa/setup');
  assert.match(s.data.qr, /^data:image\/png;base64,/);
  assert.equal((await c.post('/api/mfa/enable', { code: '123' })).status, 400);
  assert.equal((await c.post('/api/mfa/enable', { code: totpAt(s.data.secret) })).status, 200);
  assert.equal((await c.get('/api/admin/users')).status, 200);
});

test('later sign-ins need a TOTP code', async () => {
  const c = ctx.client();
  const r = await c.post('/api/login', { username: 'admin', password: 'AdminPass123' });
  assert.equal(r.data.mfa, 'required');
  assert.equal((await c.get('/api/admin/users')).status, 401);
  assert.equal((await c.post('/api/login/totp', { code: '000001' })).status, 401);
  const secret = ctx.db.prepare("SELECT totp_secret FROM users WHERE username='admin'").get().totp_secret;
  assert.equal((await c.post('/api/login/totp', { code: totpAt(secret) })).status, 200);
  assert.equal((await c.get('/api/admin/users')).status, 200);
});

test('state-changing calls without the CSRF header are rejected', async () => {
  const c = ctx.client();
  const r = await c.post('/api/login', { username: 'admin', password: 'x' }, { headers: { 'x-requested-with': '' } });
  assert.equal(r.status, 403);
  assert.equal(r.data.error, 'csrf');
});

test('login throttle is stored in the database (survives restarts)', async () => {
  const c = ctx.client();
  for (let i = 0; i < 8; i++) await c.post('/api/login', { username: 'nobody', password: 'wrong' });
  const r = await c.post('/api/login', { username: 'nobody', password: 'wrong' });
  assert.equal(r.status, 429);
  assert.ok(throttleCheck(ctx.db, 'u:nobody').blocked);
  // the database row is the state; a new process reads it
  const row = ctx.db.prepare("SELECT count FROM login_attempts WHERE key='u:nobody'").get();
  assert.ok(row.count >= 8);
  ctx.db.prepare('DELETE FROM login_attempts').run();
});

test('new users must change their password; students have no MFA', async () => {
  const admin = await setupOrg(ctx);
  const s = await admin.post('/api/admin/users', { role: 'student', name: 'Sara', username: 'sara' });
  assert.equal(s.status, 200);
  const c = ctx.client();
  const r = await c.post('/api/login', { username: 'sara', password: s.data.tempPassword });
  assert.equal(r.data.mfa, null);
  const blocked = await c.get('/api/student/tasks');
  assert.equal(blocked.data.error, 'must_change_password');
  assert.equal((await c.post('/api/password/change', { current: s.data.tempPassword, password: 'weak' })).status, 400);
  assert.equal((await c.post('/api/password/change', { current: s.data.tempPassword, password: 'SaraPass2026' })).status, 200);
  assert.equal((await c.get('/api/student/tasks')).status, 200);
});

test('password reset: admin link and email flow', async () => {
  const admin = await setupOrg(ctx);
  const u = await admin.post('/api/admin/users', { role: 'teacher', name: 'Tariq', username: 'tariq', email: 'tariq@example.com' });
  const l = await admin.post(`/api/admin/users/${u.data.id}/reset-link`);
  assert.equal(l.status, 200);
  assert.equal(l.data.emailed, true);
  const token = l.data.link.split('/reset/')[1];
  const c = ctx.client();
  assert.equal((await c.post('/api/password/reset', { token, password: 'short' })).status, 400);
  assert.equal((await c.post('/api/password/reset', { token, password: 'TariqNew2026' })).status, 200);
  // single use
  assert.equal((await c.post('/api/password/reset', { token, password: 'TariqNew2027' })).status, 400);
  await loginWithMfa(ctx, c, 'tariq', 'TariqNew2026');
  // "forgot" always answers the same way
  const f1 = await ctx.client().post('/api/password/forgot', { username: 'tariq@example.com' });
  const f2 = await ctx.client().post('/api/password/forgot', { username: 'nobody@example.com' });
  assert.deepEqual(f1.data, f2.data);
  const n = ctx.db.prepare('SELECT COUNT(*) n FROM password_resets WHERE user_id=?').get(u.data.id).n;
  assert.equal(n, 2);
});

test('logout ends the session; security headers are set', async () => {
  const c = await activate(ctx, 'sara', 'SaraPass2026', 'SaraPass2027');
  const r = await c.get('/api/me');
  assert.equal(r.headers.get('x-frame-options'), 'DENY');
  assert.match(r.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  await c.post('/api/logout');
  assert.equal((await c.get('/api/me')).status, 401);
});

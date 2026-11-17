import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { boot, school, essay, form } from './helpers.js';
import { backupNow, restoreTest, applyRetention } from '../src/maintenance.js';
import { parseCsv } from '../src/app.js';

const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
let ctx, s;
before(async () => { ctx = await boot(); s = await school(ctx); });
after(async () => { await ctx.close(); });

test('CSV parser: quotes, commas, blank lines', () => {
  assert.deepEqual(parseCsv('a,b\n"x, y",z\n\n"q""uote",w'), [['a', 'b'], ['x, y', 'z'], ['q"uote', 'w']]);
});

test('backup and restore test', async () => {
  await s.student1.post(`/api/student/tasks/${s.taskId}/submit`, null, {
    form: form({ kind: 'photo' }, { original: { buf: png, type: 'image/png', name: 'a.png' } }),
  });
  const dir = backupNow(ctx.db, ctx.cfg);
  assert.ok(fs.existsSync(dir + '/app.db'));
  const r = restoreTest(ctx.db, ctx.cfg);
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.counts.files, 2);
  assert.equal(r.missingFiles, 0);
  const sys = await s.admin.get('/api/admin/system');
  assert.ok(sys.data.events.some((e) => e.type === 'restore_test' && e.ok === 1));
});

test('student data deletion with proof', async () => {
  const before = ctx.db.prepare('SELECT COUNT(*) n FROM files').get().n;
  assert.ok(before >= 2);
  const r = await s.admin.del(`/api/admin/students/${s.ids.s1}/data`);
  assert.equal(r.status, 200);
  assert.match(r.data.proof, /^[0-9a-f]{64}$/);
  assert.equal(r.data.submissions, 1);
  assert.equal(r.data.files, 2);
  assert.equal(ctx.db.prepare('SELECT COUNT(*) n FROM submissions WHERE student_id=?').get(s.ids.s1).n, 0);
  const u = ctx.db.prepare('SELECT name, active FROM users WHERE id=?').get(s.ids.s1);
  assert.equal(u.name, '[deleted]');
  assert.equal(u.active, 0);
  // the student can no longer sign in
  assert.equal((await s.student1.get('/api/me')).status, 401);
  const sys = await s.admin.get('/api/admin/system');
  assert.ok(sys.data.deletions.some((d) => d.reason === 'school_request'));
});

test('retention deletes old answers automatically', async () => {
  const r = await s.student2.post(`/api/student/tasks/${s.taskId}/submit`, null, { form: form({ kind: 'typed', text: essay() }) });
  ctx.db.prepare("UPDATE submissions SET submitted_at=datetime('now','-400 days') WHERE id=?").run(r.data.id);
  const n = applyRetention(ctx.db, ctx.cfg);
  assert.equal(n, 1);
  assert.equal(ctx.db.prepare('SELECT COUNT(*) n FROM submissions WHERE id=?').get(r.data.id).n, 0);
  assert.ok(ctx.db.prepare("SELECT * FROM deletions WHERE reason='retention'").get());
});

test('org settings: default language, retention limits, tiers', async () => {
  const bad = await s.admin.put('/api/admin/org', { retentionDays: 5, defaultLang: 'fr', langTiers: { en: 'super', ur: 'full' } });
  assert.equal(bad.status, 200);
  const o = await s.admin.get('/api/admin/org');
  assert.equal(o.data.retention_days, 365);
  assert.equal(o.data.default_lang, 'en');
  assert.equal(o.data.lang_tiers.en, 'beta');
  assert.equal(o.data.lang_tiers.ur, 'full');
  await s.admin.put('/api/admin/org', { defaultLang: 'ur' });
  const t = ctx.client();
  // a new user gets the org default language
  const u = await s.admin.post('/api/admin/users', { role: 'student', name: 'New', username: 'newone' });
  await t.post('/api/login', { username: 'newone', password: u.data.tempPassword });
  assert.equal((await t.get('/api/me')).data.lang, 'ur');
});

test('backup/restore only for the server owner (first org) admin', async () => {
  assert.equal((await s.admin.post('/api/admin/restore-test')).status, 200);
});

test('user settings: language and score first', async () => {
  const r = await s.teacher.put('/api/me/settings', { lang: 'en', scoreFirst: false });
  assert.equal(r.data.lang, 'en');
  assert.equal(r.data.scoreFirst, false);
  const st = await s.student2.put('/api/me/settings', { lang: 'ur', feedbackLang: 'en', scoreFirst: false });
  assert.equal(st.data.feedbackLang, 'en');
});

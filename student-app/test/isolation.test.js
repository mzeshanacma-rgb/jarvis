// Tenant isolation: one school can never see another school's data. Runs before every release (npm test).
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { boot, school, essay, form } from './helpers.js';

let ctx, A, B, subA, fileA;
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');

before(async () => {
  ctx = await boot();
  A = await school(ctx, { orgName: 'School A', username: 'admina', suffix: 'a' });
  B = await school(ctx, { orgName: 'School B', username: 'adminb', suffix: 'b' });
  const r = await A.student1.post(`/api/student/tasks/${A.taskId}/submit`, null, {
    form: form({ kind: 'photo' }, { original: { buf: png, type: 'image/png', name: 'copy.png' }, answer: { buf: png, type: 'image/png', name: 'answer.png' } }),
  });
  assert.equal(r.status, 200, JSON.stringify(r.data));
  subA = r.data.id;
  await ctx.runJobs();
  fileA = ctx.db.prepare("SELECT id FROM files WHERE submission_id=? AND role='original'").get(subA).id;
});
after(async () => { await ctx.close(); });

test('school B admin cannot see school A data', async () => {
  const users = await B.admin.get('/api/admin/users');
  assert.ok(users.data.every((u) => !u.username.endsWith('a') || u.username === 'adminb' || u.username.endsWith('b')));
  assert.ok(!users.data.some((u) => u.username === 'ayeshaa'));
  const audit = await B.admin.get('/api/admin/audit');
  assert.ok(!audit.data.some((x) => x.entity === 'submission' && x.entity_id === subA));
  const classes = await B.admin.get('/api/admin/classes');
  assert.ok(!classes.data.some((c) => c.id === A.classId));
  assert.equal((await B.admin.post(`/api/admin/classes/${A.classId}/enroll`, { studentIds: [B.ids.s1] })).status, 404);
  assert.equal((await B.admin.post(`/api/admin/users/${A.ids.s1}/reset-link`)).status, 404);
  assert.equal((await B.admin.post(`/api/admin/users/${A.ids.s1}/active`, { active: false })).status, 404);
  assert.equal((await B.admin.del(`/api/admin/students/${A.ids.s1}/data`)).status, 404);
  // B's admin cannot assign A's teacher to a class
  assert.equal((await B.admin.post('/api/admin/classes', { name: 'x', teacherId: A.ids.teacher })).status, 400);
});

test('school B teacher cannot reach A tasks, answers or classes', async () => {
  assert.equal((await B.teacher.get(`/api/teacher/tasks/${A.taskId}`)).status, 404);
  assert.equal((await B.teacher.get(`/api/teacher/submissions/${subA}`)).status, 404);
  assert.equal((await B.teacher.post(`/api/teacher/submissions/${subA}/approve`, { scores: [], feedback: 'x' })).status, 404);
  assert.equal((await B.teacher.post(`/api/teacher/submissions/${subA}/estimate`, { total: 1 })).status, 404);
  assert.equal((await B.teacher.get(`/api/teacher/classes/${A.classId}/tasks`)).status, 404);
  assert.equal((await B.teacher.post('/api/teacher/tasks', { classId: A.classId, title: 't', instructions: 'i', rubric: [{ name: 'a', max: 1 }] })).status, 404);
  assert.equal((await B.teacher.put(`/api/teacher/classes/${A.classId}`, { aiEnabled: false })).status, 404);
});

test('school B student cannot join A classes or see A tasks', async () => {
  assert.equal((await B.student1.post('/api/student/join', { code: A.classCode })).status, 404);
  assert.equal((await B.student1.get(`/api/student/tasks/${A.taskId}`)).status, 404);
  assert.equal((await B.student1.post(`/api/student/tasks/${A.taskId}/submit`, null, { form: form({ kind: 'typed', text: essay() }) })).status, 404);
  assert.equal((await B.student1.post(`/api/student/submissions/${subA}/recheck`, { reason: 'x' })).status, 404);
});

test('file links are short-lived, bound to the user, useless elsewhere', async () => {
  const view = await A.teacher.get(`/api/teacher/submissions/${subA}`);
  const url = view.data.files.find((f) => f.role === 'original').url;
  const ok = await A.teacher.get(url);
  assert.equal(ok.status, 200);
  assert.ok(Buffer.compare(ok.data, png) === 0, 'decrypted file matches');
  // the file is encrypted on disk
  const fs = await import('node:fs');
  const path = await import('node:path');
  const f = ctx.db.prepare('SELECT path FROM files WHERE id=?').get(fileA);
  assert.notDeepEqual(fs.readFileSync(path.join(ctx.cfg.uploadDir, f.path)), png);
  // the same link is useless for another user
  assert.equal((await B.teacher.get(url)).status, 403);
  assert.equal((await A.student2.get(url)).status, 403);
  assert.equal((await ctx.client().get(url)).status, 401);
  // muddat guzri hui
  const expired = url.replace(/exp=\d+/, 'exp=1');
  assert.equal((await A.teacher.get(expired)).status, 403);
});

test('every API route filters by org: B users cannot use A ids', async () => {
  // call teacher/student GET routes with A ids as B users
  const routes = [
    [B.teacher, `/api/teacher/tasks/${A.taskId}`],
    [B.teacher, `/api/teacher/submissions/${subA}`],
    [B.student1, `/api/student/tasks/${A.taskId}`],
    [B.teacher, `/api/files/${fileA}?exp=9999999999&sig=x`],
  ];
  for (const [c, url] of routes) {
    const r = await c.get(url);
    assert.ok([403, 404].includes(r.status), `${url} -> ${r.status}`);
  }
  // students cannot use the teacher API, teachers cannot use the admin API
  assert.equal((await A.student1.get(`/api/teacher/tasks/${A.taskId}`)).status, 403);
  assert.equal((await A.teacher.get('/api/admin/users')).status, 403);
});

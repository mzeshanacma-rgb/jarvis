import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { boot, school, essay, form } from './helpers.js';

let ctx, s;
before(async () => { ctx = await boot(); s = await school(ctx); });
after(async () => { await ctx.close(); });

test('student submits a typed answer; the draft is made in the background', async () => {
  const r = await s.student1.post(`/api/student/tasks/${s.taskId}/submit`, null, { form: form({ kind: 'typed', text: essay(), writingLog: JSON.stringify({ durationMs: 60000, keystrokes: 400, pastes: [{ at: 1, chars: 12 }] }) }) });
  assert.equal(r.status, 200);
  const sub = ctx.db.prepare('SELECT status FROM submissions WHERE id=?').get(r.data.id);
  assert.equal(sub.status, 'submitted');
  // cannot submit twice
  const again = await s.student1.post(`/api/student/tasks/${s.taskId}/submit`, null, { form: form({ kind: 'typed', text: 'x' }) });
  assert.equal(again.data.error, 'already_submitted');
  await ctx.runJobs();
  assert.equal(ctx.db.prepare('SELECT status FROM submissions WHERE id=?').get(r.data.id).status, 'draft_ready');
  const d = ctx.db.prepare('SELECT * FROM ai_drafts WHERE submission_id=?').get(r.data.id);
  assert.equal(d.prompt_version, 'grade-v2.1');
  assert.equal(d.tier, 'beta');
});

test('student sees no score or feedback before approval', async () => {
  const t = await s.student1.get(`/api/student/tasks/${s.taskId}`);
  assert.equal(t.data.submission.status, 'submitted');
  assert.equal(t.data.submission.total, undefined);
  assert.equal(t.data.submission.feedback, undefined);
});

test('score first: the AI draft is hidden until the teacher estimates', async () => {
  const sub = ctx.db.prepare('SELECT id FROM submissions WHERE task_id=?').get(s.taskId);
  const v1 = await s.teacher.get(`/api/teacher/submissions/${sub.id}`);
  assert.equal(v1.data.draft, null);
  assert.equal(v1.data.draftHidden, 'score_first');
  assert.equal((await s.teacher.post(`/api/teacher/submissions/${sub.id}/estimate`, { total: 7 })).status, 200);
  const v2 = await s.teacher.get(`/api/teacher/submissions/${sub.id}`);
  assert.ok(v2.data.draft);
  // Beta tier: feedback only, no scores in the draft
  assert.equal(v2.data.draft.tier, 'beta');
  assert.ok(v2.data.draft.scores.every((x) => x.score === null));
  assert.equal(v2.data.draft.total, null);
  assert.ok(v2.data.draft.feedbackStudent.length > 10);
  assert.equal(v2.data.draft.oralQuestions.length, 2);
});

test('teacher approval rejects out-of-range scores, then releases', async () => {
  const sub = ctx.db.prepare('SELECT id FROM submissions WHERE task_id=?').get(s.taskId);
  const bad = await s.teacher.post(`/api/teacher/submissions/${sub.id}/approve`, { scores: [{ id: 'c1', score: 9 }, { id: 'c2', score: 3 }], feedback: 'Good' });
  assert.equal(bad.data.error, 'score_out_of_range');
  const ok = await s.teacher.post(`/api/teacher/submissions/${sub.id}/approve`, { scores: [{ id: 'c1', score: 5 }, { id: 'c2', score: 3 }], feedback: 'Well done, add one more example.', reviewMs: 40000 });
  assert.equal(ok.status, 200);
  assert.equal(ok.data.total, 8);
  const st = await s.student1.get(`/api/student/tasks/${s.taskId}`);
  assert.equal(st.data.submission.status, 'approved');
  assert.equal(st.data.submission.total, 8);
  assert.equal(st.data.submission.aiAssisted, true);
  // one student cannot see another student's work
  const other = await s.student2.get(`/api/student/tasks/${s.taskId}`);
  assert.equal(other.data.submission, null);
});

test('dashboard counts only approved scores as final', async () => {
  await s.student2.post(`/api/student/tasks/${s.taskId}/submit`, null, { form: form({ kind: 'typed', text: essay(40) }) });
  await ctx.runJobs();
  const d = await s.teacher.get(`/api/teacher/tasks/${s.taskId}`);
  assert.equal(d.data.stats.submitted, 2);
  assert.equal(d.data.stats.approved, 1);
  assert.equal(d.data.stats.average, 8);
  const pending = d.data.queue.find((q) => q.status !== 'approved');
  assert.equal(pending.final_total, null);
});

test('re-check: student request, new teacher score, old score kept in audit', async () => {
  const sub = ctx.db.prepare('SELECT id FROM submissions WHERE task_id=? AND student_id=?').get(s.taskId, s.ids.s1);
  assert.equal((await s.student1.post(`/api/student/submissions/${sub.id}/recheck`, { reason: '' })).status, 400);
  assert.equal((await s.student1.post(`/api/student/submissions/${sub.id}/recheck`, { reason: 'Language mein ghalti nahi thi' })).status, 200);
  assert.equal((await s.student1.post(`/api/student/submissions/${sub.id}/recheck`, { reason: 'again' })).status, 400);
  const q = await s.teacher.get(`/api/teacher/tasks/${s.taskId}`);
  assert.equal(q.data.queue[0].recheck_status, 'requested'); // top of the queue
  const r = await s.teacher.post(`/api/teacher/submissions/${sub.id}/approve`, { scores: [{ id: 'c1', score: 5 }, { id: 'c2', score: 4 }], feedback: 'Rechecked: language is fine.' });
  assert.equal(r.data.total, 9);
  const log = ctx.db.prepare("SELECT detail FROM audit_log WHERE action='recheck_approved' AND entity_id=?").get(sub.id);
  const detail = JSON.parse(log.detail);
  assert.equal(detail.before.total, 8);
  assert.equal(detail.after.total, 9);
});

test('class code only links existing accounts', async () => {
  const cls2 = await s.admin.post('/api/admin/classes', { name: 'Class 9B', teacherId: s.ids.teacher });
  const r = await s.student1.post('/api/student/join', { code: cls2.data.code });
  assert.equal(r.status, 200);
  const anon = ctx.client();
  assert.equal((await anon.post('/api/student/join', { code: cls2.data.code })).status, 401);
  // there is no sign-up endpoint
  assert.equal((await anon.post('/api/signup', { code: cls2.data.code })).status, 404);
});

test('audit log records views, approvals and AI drafts', async () => {
  const a = await s.admin.get('/api/admin/audit');
  const actions = new Set(a.data.map((x) => x.action));
  for (const k of ['submitted', 'ai_draft_created', 'submission_viewed', 'teacher_estimate', 'approved_released', 'recheck_requested', 'recheck_approved']) assert.ok(actions.has(k), k);
});

test('CSV import: header, class code and bad rows', async () => {
  const csv = 'role,name,username,email,age_band,class_code\nstudent,"Khan, Ali",ali1,,under13,' + s.classCode + '\nstudent,Zara,zara1,,,\nstudent,Bad,ayesha,,,\nwizard,X,x1,,,\n';
  const r = await s.admin.post('/api/admin/import-csv', { csv });
  const res = r.data.results;
  assert.equal(res.length, 4);
  assert.ok(res[0].ok && res[0].tempPassword);
  assert.ok(res[1].ok);
  assert.equal(res[2].error, 'username_taken');
  assert.equal(res[3].error, 'bad_role');
  const ali = ctx.db.prepare("SELECT name, age_band FROM users WHERE username='ali1'").get();
  assert.equal(ali.name, 'Khan, Ali');
  assert.equal(ali.age_band, 'under13');
  const enrolled = ctx.db.prepare("SELECT COUNT(*) n FROM enrollments e JOIN users u ON u.id=e.student_id WHERE u.username='ali1'").get().n;
  assert.equal(enrolled, 1);
});

test('teacher can turn AI off for a class: no drafts then', async () => {
  await s.teacher.put(`/api/teacher/classes/${s.classId}`, { aiEnabled: false });
  const ali = ctx.db.prepare("SELECT id FROM users WHERE username='zara1'").get();
  await s.admin.post(`/api/admin/classes/${s.classId}/enroll`, { studentIds: [ali.id] });
  // reset Zara's password and sign in
  const link = await s.admin.post(`/api/admin/users/${ali.id}/reset-link`);
  await ctx.client().post('/api/password/reset', { token: link.data.link.split('/reset/')[1], password: 'ZaraPass2026' });
  const zara = ctx.client();
  await zara.post('/api/login', { username: 'zara1', password: 'ZaraPass2026' });
  const r = await zara.post(`/api/student/tasks/${s.taskId}/submit`, null, { form: form({ kind: 'typed', text: essay() }) });
  await ctx.runJobs();
  assert.equal(ctx.db.prepare('SELECT status FROM submissions WHERE id=?').get(r.data.id).status, 'ai_unavailable');
  // the teacher can still score and approve
  const ok = await s.teacher.post(`/api/teacher/submissions/${r.data.id}/approve`, { scores: [{ id: 'c1', score: 4 }, { id: 'c2', score: 2 }], feedback: 'Teacher-only feedback.' });
  assert.equal(ok.status, 200);
  const st = await zara.get(`/api/student/tasks/${s.taskId}`);
  assert.equal(st.data.submission.aiAssisted, false);
  await s.teacher.put(`/api/teacher/classes/${s.classId}`, { aiEnabled: true });
});

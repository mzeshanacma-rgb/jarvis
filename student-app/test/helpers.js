import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadConfig } from '../src/config.js';
import { createApp } from '../src/app.js';
import { totpAt } from '../src/security.js';
import { runPendingJobs } from '../src/review.js';

export async function boot(overrides = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'saa-test-'));
  const cfg = loadConfig({ dataDir: dir, startWorkers: false, aiMode: 'demo', require2fa: true, blindSampleRate: 0, publicUrl: 'http://test', ...overrides });
  const app = createApp(cfg);
  const server = await new Promise((r) => { const s = app.listen(0, () => r(s)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const ctx = {
    cfg, app, base, dir, db: app.locals.db,
    client: () => makeClient(base),
    runJobs: () => runPendingJobs(app.locals.db, cfg, app.locals.grader, { limit: 50 }),
    close: async () => { await new Promise((r) => server.close(r)); app.locals.close(); fs.rmSync(dir, { recursive: true, force: true }); },
  };
  return ctx;
}

export function makeClient(base) {
  let cookie = '';
  async function req(method, url, body, { form, headers = {} } = {}) {
    const h = { ...headers };
    if (method !== 'GET') h['x-requested-with'] = h['x-requested-with'] ?? 'app';
    if (cookie) h.cookie = cookie;
    let payload;
    if (form) payload = form;
    else if (body !== undefined) { h['content-type'] = 'application/json'; payload = JSON.stringify(body); }
    const res = await fetch(base + url, { method, headers: h, body: payload });
    const set = res.headers.getSetCookie?.() || [];
    for (const c of set) {
      const m = c.match(/^sid=([^;]*)/);
      if (m) cookie = m[1] ? `sid=${m[1]}` : '';
    }
    const type = res.headers.get('content-type') || '';
    const data = type.includes('json') ? await res.json() : Buffer.from(await res.arrayBuffer());
    return { status: res.status, data, headers: res.headers };
  }
  return {
    get: (u, o) => req('GET', u, undefined, o),
    post: (u, b, o) => req('POST', u, b, o),
    put: (u, b, o) => req('PUT', u, b, o),
    del: (u, b, o) => req('DELETE', u, b, o),
    raw: req,
  };
}

// Create the admin and sign in with two-step sign-in
export async function setupOrg(ctx, { orgName = 'Test School', username = 'admin' } = {}) {
  const c = ctx.client();
  const pw = 'AdminPass123';
  let r = await c.post('/api/setup', { orgName, name: 'Admin', username, password: pw, defaultLang: 'en' });
  if (r.status !== 200 && !ctx.db.prepare('SELECT id FROM users WHERE username=?').get(username)) {
    // second org: setup runs only once, so insert directly
    const { hashPassword } = await import('../src/security.js');
    const o = ctx.db.prepare('INSERT INTO orgs (name) VALUES (?)').run(orgName);
    ctx.db.prepare("INSERT INTO users (org_id,role,name,username,pass_hash,code,age_band) VALUES (?,?,?,?,?,?,'adult')").run(o.lastInsertRowid, 'admin', 'Admin', username, hashPassword(pw), 'A-' + username);
  }
  await loginWithMfa(ctx, c, username, pw);
  return c;
}

export async function loginWithMfa(ctx, c, username, password) {
  const r = await c.post('/api/login', { username, password });
  if (r.status !== 200) throw new Error('login failed ' + JSON.stringify(r.data));
  if (r.data.mfa === 'enroll') {
    const s = await c.get('/api/mfa/setup');
    const e = await c.post('/api/mfa/enable', { code: totpAt(s.data.secret) });
    if (e.status !== 200) throw new Error('mfa enable failed');
  } else if (r.data.mfa === 'required') {
    const secret = ctx.db.prepare('SELECT totp_secret FROM users WHERE username=?').get(username).totp_secret;
    const t = await c.post('/api/login/totp', { code: totpAt(secret) });
    if (t.status !== 200) throw new Error('totp failed');
  }
  return r.data;
}

// Sign in a user with a temporary password and change it
export async function activate(ctx, username, tempPassword, newPassword = 'NewPassword123') {
  const c = ctx.client();
  await loginWithMfa(ctx, c, username, tempPassword);
  const r = await c.post('/api/password/change', { current: tempPassword, password: newPassword });
  if (r.status !== 200) throw new Error('pw change failed ' + JSON.stringify(r.data));
  return c;
}

// A whole school: admin, teacher, 2 students, class, task
export async function school(ctx, opts = {}) {
  const admin = await setupOrg(ctx, opts);
  const sfx = opts.suffix || '';
  const t = await admin.post('/api/admin/users', { role: 'teacher', name: 'Teacher', username: 'teacher' + sfx });
  const s1 = await admin.post('/api/admin/users', { role: 'student', name: 'Ayesha K', username: 'ayesha' + sfx });
  const s2 = await admin.post('/api/admin/users', { role: 'student', name: 'Bilal R', username: 'bilal' + sfx });
  const cls = await admin.post('/api/admin/classes', { name: 'Class 8A', teacherId: t.data.id });
  await admin.post(`/api/admin/classes/${cls.data.id}/enroll`, { studentIds: [s1.data.id, s2.data.id] });
  const teacher = await activate(ctx, 'teacher' + sfx, t.data.tempPassword);
  const student1 = await activate(ctx, 'ayesha' + sfx, s1.data.tempPassword);
  const student2 = await activate(ctx, 'bilal' + sfx, s2.data.tempPassword);
  const task = await teacher.post('/api/teacher/tasks', {
    classId: cls.data.id, title: 'Essay: My City', instructions: 'Write about your city.', answerLang: opts.answerLang || 'en', mode: 'any',
    rubric: [{ name: 'Content', max: 6, description: 'Ideas and examples' }, { name: 'Language', max: 4 }],
    samples: [{ level: 'high', text: 'A strong answer...' }],
  });
  return { admin, teacher, student1, student2, classId: cls.data.id, classCode: cls.data.code, taskId: task.data.id, ids: { teacher: t.data.id, s1: s1.data.id, s2: s2.data.id } };
}

export const essay = (n = 90) => Array.from({ length: n }, (_, i) => ['my', 'city', 'is', 'busy', 'and', 'old', 'with', 'markets', 'parks'][i % 9] + (i % 7)).join(' ');

export function form(fields, files = {}) {
  const f = new FormData();
  for (const [k, v] of Object.entries(fields)) f.append(k, v);
  for (const [k, { buf, type, name }] of Object.entries(files)) f.append(k, new Blob([buf], { type }), name);
  return f;
}

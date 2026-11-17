import express from 'express';
import multer from 'multer';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import QRCode from 'qrcode';
import { openDb, one, all, run, tx, audit } from './db.js';
import {
  hashPassword, verifyPassword, passwordProblem, sha256, randomToken, randomCode,
  newTotpSecret, verifyTotp, throttleCheck, throttleFail, throttleClear,
  saveEncrypted, readEncrypted, signLink, checkLink,
} from './security.js';
import { makeGrader } from './ai.js';
import { enqueueDraft, startWorker, qualityReport } from './review.js';
import { startScheduler, backupNow, restoreTest, deleteStudentData } from './maintenance.js';
import { sendMail } from './mail.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SESSION_MS = 12 * 60 * 60 * 1000;
const LANGS = ['en', 'ur'];
const ANSWER_LANGS = ['en', 'ur', 'ur-roman'];

class HttpError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
const bad = (code = 'bad_request') => new HttpError(400, code);
const notFound = () => new HttpError(404, 'not_found');
const forbidden = (code = 'forbidden') => new HttpError(403, code);

const wrap = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

function parseCookies(header) {
  const out = {};
  for (const part of String(header || '').split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}

function str(v, max = 2000) {
  if (typeof v !== 'string') return '';
  return v.trim().slice(0, max);
}

// Small CSV parser (handles quotes)
export function parseCsv(text) {
  const rows = [];
  let row = [], field = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') q = false;
      else field += c;
    } else if (c === '"') q = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.some((x) => x.trim() !== '')) rows.push(row);
      row = [];
    } else field += c;
  }
  row.push(field);
  if (row.some((x) => x.trim() !== '')) rows.push(row);
  return rows;
}

function tempPassword() {
  return randomCode(5) + '-' + Math.floor(1000 + Math.random() * 9000) + randomCode(3).toLowerCase();
}

export function createApp(cfg) {
  const db = openDb(cfg.dbPath);
  const grader = cfg.grader || makeGrader(cfg);
  const stops = [];
  if (cfg.startWorkers) {
    stops.push(startWorker(db, cfg, grader));
    stops.push(startScheduler(db, cfg));
  }

  const app = express();
  app.disable('x-powered-by');
  if (cfg.behindHttps) app.set('trust proxy', 1);

  // Security headers
  app.use((req, res, next) => {
    res.set({
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'no-referrer',
      'Permissions-Policy': 'camera=(self), microphone=(), geolocation=()',
      'Content-Security-Policy': "default-src 'self'; img-src 'self' blob: data:; style-src 'self'; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    });
    if (cfg.behindHttps) res.set('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
    next();
  });
  app.use(express.json({ limit: '1mb' }));

  // CSRF: every state-changing API call needs this header (another site cannot send it)
  app.use('/api', (req, res, next) => {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) && req.get('x-requested-with') !== 'app') return res.status(403).json({ error: 'csrf' });
    res.set('Cache-Control', 'no-store');
    next();
  });

  // ---- Session ----
  function setSession(res, userId, mfaOk) {
    const token = randomToken();
    run(db, 'INSERT INTO sessions (id_hash,user_id,mfa_ok,expires_at) VALUES (?,?,?,?)', sha256(token), userId, mfaOk ? 1 : 0, Date.now() + SESSION_MS);
    res.append('Set-Cookie', `sid=${token}; HttpOnly; Path=/; SameSite=Strict; Max-Age=${SESSION_MS / 1000}${cfg.behindHttps ? '; Secure' : ''}`);
  }
  app.use((req, res, next) => {
    const sid = parseCookies(req.headers.cookie).sid;
    if (sid) {
      const s = one(db, 'SELECT * FROM sessions WHERE id_hash=? AND expires_at>?', sha256(sid), Date.now());
      if (s) {
        const u = one(db, 'SELECT * FROM users WHERE id=? AND active=1', s.user_id);
        if (u) { req.user = u; req.session = s; req.sid = sid; }
      }
    }
    next();
  });
  const needsMfa = (u) => cfg.require2fa && (u.role === 'teacher' || u.role === 'admin');

  function auth(...roles) {
    return (req, res, next) => {
      if (!req.user) return next(new HttpError(401, 'login_required'));
      if (needsMfa(req.user) && !req.session.mfa_ok) return next(new HttpError(401, 'mfa_required'));
      if (req.user.must_change_password && !req.path.startsWith('/password')) return next(new HttpError(403, 'must_change_password'));
      if (roles.length && !roles.includes(req.user.role)) return next(forbidden());
      next();
    };
  }
  const A = (req, action, entity, entityId, detail) => audit(db, { orgId: req.user?.org_id, userId: req.user?.id, action, entity, entityId, detail });

  function publicUser(u) {
    const org = one(db, 'SELECT * FROM orgs WHERE id=?', u.org_id);
    return {
      id: u.id, role: u.role, name: u.name, username: u.username, email: u.email, code: u.code,
      lang: u.lang || org.default_lang, feedbackLang: u.feedback_lang || u.lang || org.default_lang,
      scoreFirst: !!u.score_first, totpEnabled: !!u.totp_enabled, mustChangePassword: !!u.must_change_password,
      org: { id: org.id, name: org.name, defaultLang: org.default_lang, plan: org.plan, trialEndsAt: org.trial_ends_at, langTiers: JSON.parse(org.lang_tiers) },
    };
  }

  // ---- Setup and login ----
  app.get('/api/health', (req, res) => res.json({ ok: true, ai: cfg.aiMode, androidApkUrl: cfg.androidApkUrl || null }));
  app.get('/api/setup', (req, res) => res.json({ needed: !one(db, 'SELECT id FROM orgs LIMIT 1') }));

  app.post('/api/setup', wrap(async (req, res) => {
    if (one(db, 'SELECT id FROM orgs LIMIT 1')) throw forbidden('already_setup');
    const orgName = str(req.body.orgName, 200), name = str(req.body.name, 200), username = str(req.body.username, 100).toLowerCase();
    const lang = LANGS.includes(req.body.defaultLang) ? req.body.defaultLang : 'en';
    const pw = req.body.password;
    if (!orgName || !name || !/^[a-z0-9._-]{3,}$/.test(username)) throw bad('missing_fields');
    const p = passwordProblem(pw); if (p) throw bad(p);
    const trialEnds = new Date(Date.now() + 60 * 86400_000).toISOString();
    const { orgId, userId } = tx(db, () => {
      const o = run(db, 'INSERT INTO orgs (name, default_lang, trial_ends_at) VALUES (?,?,?)', orgName, lang, trialEnds);
      const u = run(db, "INSERT INTO users (org_id,role,name,username,email,pass_hash,age_band,code) VALUES (?,?,?,?,?,?,'adult',?)",
        o.lastInsertRowid, 'admin', name, username, str(req.body.email, 200) || null, hashPassword(pw), 'A-' + randomCode(5));
      return { orgId: Number(o.lastInsertRowid), userId: Number(u.lastInsertRowid) };
    });
    audit(db, { orgId, userId, action: 'org_created' });
    setSession(res, userId, false);
    res.json({ ok: true });
  }));

  app.post('/api/login', wrap(async (req, res) => {
    const username = str(req.body.username, 100).toLowerCase();
    const ipKey = 'ip:' + req.ip, userKey = 'u:' + username;
    const t = [throttleCheck(db, ipKey), throttleCheck(db, userKey)].find((x) => x.blocked);
    if (t) { res.set('Retry-After', String(t.retryAfterS)); throw new HttpError(429, 'too_many_attempts'); }
    const u = one(db, 'SELECT * FROM users WHERE username=? AND active=1', username);
    if (!u || !verifyPassword(String(req.body.password || ''), u.pass_hash)) {
      throttleFail(db, ipKey); throttleFail(db, userKey);
      audit(db, { orgId: u?.org_id, userId: u?.id, action: 'login_failed', detail: { username } });
      throw new HttpError(401, 'wrong_login');
    }
    throttleClear(db, userKey);
    setSession(res, u.id, !needsMfa(u));
    audit(db, { orgId: u.org_id, userId: u.id, action: 'login' });
    res.json({ ok: true, mfa: needsMfa(u) ? (u.totp_enabled ? 'required' : 'enroll') : null });
  }));

  app.post('/api/login/totp', wrap(async (req, res) => {
    if (!req.user) throw new HttpError(401, 'login_required');
    const key = 'totp:' + req.user.id;
    if (throttleCheck(db, key).blocked) throw new HttpError(429, 'too_many_attempts');
    if (!req.user.totp_enabled || !verifyTotp(req.user.totp_secret, req.body.code)) { throttleFail(db, key); throw new HttpError(401, 'wrong_code'); }
    throttleClear(db, key);
    run(db, 'UPDATE sessions SET mfa_ok=1 WHERE id_hash=?', sha256(req.sid));
    A(req, 'mfa_ok');
    res.json({ ok: true });
  }));

  // Two-step sign-in enrolment: secret and QR on first use
  app.get('/api/mfa/setup', wrap(async (req, res) => {
    if (!req.user) throw new HttpError(401, 'login_required');
    if (req.user.totp_enabled) throw bad('already_enabled');
    let secret = req.user.totp_secret;
    if (!secret) { secret = newTotpSecret(); run(db, 'UPDATE users SET totp_secret=? WHERE id=?', secret, req.user.id); }
    const org = one(db, 'SELECT name FROM orgs WHERE id=?', req.user.org_id);
    const uri = `otpauth://totp/${encodeURIComponent(org.name + ':' + req.user.username)}?secret=${secret}&issuer=${encodeURIComponent(org.name)}`;
    res.json({ secret, uri, qr: await QRCode.toDataURL(uri) });
  }));
  app.post('/api/mfa/enable', wrap(async (req, res) => {
    if (!req.user) throw new HttpError(401, 'login_required');
    if (!req.user.totp_secret || !verifyTotp(req.user.totp_secret, req.body.code)) throw bad('wrong_code');
    run(db, 'UPDATE users SET totp_enabled=1 WHERE id=?', req.user.id);
    run(db, 'UPDATE sessions SET mfa_ok=1 WHERE id_hash=?', sha256(req.sid));
    A(req, 'mfa_enabled');
    res.json({ ok: true });
  }));

  app.post('/api/logout', (req, res) => {
    if (req.sid) run(db, 'DELETE FROM sessions WHERE id_hash=?', sha256(req.sid));
    res.append('Set-Cookie', 'sid=; HttpOnly; Path=/; SameSite=Strict; Max-Age=0');
    res.json({ ok: true });
  });

  app.get('/api/me', (req, res) => {
    if (!req.user) return res.status(401).json({ error: 'login_required' });
    const pending = needsMfa(req.user) && !req.session.mfa_ok ? (req.user.totp_enabled ? 'required' : 'enroll') : null;
    res.json({ ...publicUser(req.user), mfaPending: pending });
  });

  app.put('/api/me/settings', auth(), wrap(async (req, res) => {
    const lang = LANGS.includes(req.body.lang) ? req.body.lang : null;
    const fl = LANGS.includes(req.body.feedbackLang) ? req.body.feedbackLang : null;
    run(db, 'UPDATE users SET lang=COALESCE(?,lang), feedback_lang=COALESCE(?,feedback_lang) WHERE id=?', lang, fl, req.user.id);
    if (req.user.role === 'teacher' && typeof req.body.scoreFirst === 'boolean') run(db, 'UPDATE users SET score_first=? WHERE id=?', req.body.scoreFirst ? 1 : 0, req.user.id);
    res.json(publicUser(one(db, 'SELECT * FROM users WHERE id=?', req.user.id)));
  }));

  // ---- Password ----
  app.post('/api/password/change', wrap(async (req, res) => {
    if (!req.user) throw new HttpError(401, 'login_required');
    if (!verifyPassword(String(req.body.current || ''), req.user.pass_hash)) throw bad('wrong_password');
    const p = passwordProblem(req.body.password); if (p) throw bad(p);
    run(db, 'UPDATE users SET pass_hash=?, must_change_password=0 WHERE id=?', hashPassword(req.body.password), req.user.id);
    run(db, 'DELETE FROM sessions WHERE user_id=? AND id_hash<>?', req.user.id, sha256(req.sid));
    A(req, 'password_changed');
    res.json({ ok: true });
  }));

  function createResetLink(userId) {
    const token = randomToken();
    run(db, 'INSERT INTO password_resets (token_hash,user_id,expires_at) VALUES (?,?,?)', sha256(token), userId, Date.now() + 60 * 60 * 1000);
    return `${cfg.publicUrl}/#/reset/${token}`;
  }

  // Password reset by email. The response is always the same, so it does not reveal whether an account exists.
  app.post('/api/password/forgot', wrap(async (req, res) => {
    const id = str(req.body.username, 200).toLowerCase();
    const key = 'forgot:' + req.ip;
    if (!throttleCheck(db, key).blocked) {
      throttleFail(db, key);
      const u = one(db, 'SELECT * FROM users WHERE (username=? OR lower(email)=?) AND active=1 AND email IS NOT NULL', id, id);
      if (u) {
        const link = createResetLink(u.id);
        await sendMail(cfg, { to: u.email, subject: 'Password reset', text: `Use this link to choose a new password. It works for 1 hour:\n${link}\n\nIf you did not ask for this, you can ignore this email.` });
        audit(db, { orgId: u.org_id, userId: u.id, action: 'password_reset_requested' });
      }
    }
    res.json({ ok: true });
  }));

  app.post('/api/password/reset', wrap(async (req, res) => {
    const row = one(db, 'SELECT * FROM password_resets WHERE token_hash=? AND used=0 AND expires_at>?', sha256(String(req.body.token || '')), Date.now());
    if (!row) throw bad('link_expired');
    const p = passwordProblem(req.body.password); if (p) throw bad(p);
    tx(db, () => {
      run(db, 'UPDATE users SET pass_hash=?, must_change_password=0 WHERE id=?', hashPassword(req.body.password), row.user_id);
      run(db, 'UPDATE password_resets SET used=1 WHERE token_hash=?', row.token_hash);
      run(db, 'DELETE FROM sessions WHERE user_id=?', row.user_id);
    });
    const u = one(db, 'SELECT org_id FROM users WHERE id=?', row.user_id);
    audit(db, { orgId: u.org_id, userId: row.user_id, action: 'password_reset_done' });
    res.json({ ok: true });
  }));

  // ---- Files (short-lived signed link + session + access check) ----
  function canSeeSubmission(user, sub) {
    if (!sub || sub.org_id !== user.org_id) return false;
    if (user.role === 'admin') return true;
    if (user.role === 'student') return sub.student_id === user.id;
    const task = one(db, 'SELECT teacher_id, class_id FROM tasks WHERE id=? AND org_id=?', sub.task_id, user.org_id);
    const cls = task && one(db, 'SELECT teacher_id FROM classes WHERE id=? AND org_id=?', task.class_id, user.org_id);
    return !!task && (task.teacher_id === user.id || cls?.teacher_id === user.id);
  }
  app.get('/api/files/:id', auth(), wrap(async (req, res) => {
    const id = Number(req.params.id);
    if (!checkLink(cfg, id, req.user.id, req.query.exp, req.query.sig)) throw forbidden('link_expired');
    const f = one(db, 'SELECT * FROM files WHERE id=? AND org_id=?', id, req.user.org_id);
    if (!f) throw notFound();
    const sub = one(db, 'SELECT * FROM submissions WHERE id=? AND org_id=?', f.submission_id, req.user.org_id);
    if (!canSeeSubmission(req.user, sub)) throw notFound();
    A(req, 'file_viewed', 'file', f.id);
    res.set({ 'Content-Type': f.mime, 'Cache-Control': 'private, no-store', 'Content-Disposition': `inline; filename="file-${f.id}"` });
    res.send(readEncrypted(cfg, f));
  }));

  // =========================== ADMIN ===========================
  const admin = express.Router();
  admin.use(auth('admin'));

  admin.get('/org', (req, res) => {
    const o = one(db, 'SELECT * FROM orgs WHERE id=?', req.user.org_id);
    res.json({ ...o, lang_tiers: JSON.parse(o.lang_tiers) });
  });
  admin.put('/org', wrap(async (req, res) => {
    const b = req.body, o = one(db, 'SELECT * FROM orgs WHERE id=?', req.user.org_id);
    const tiers = JSON.parse(o.lang_tiers);
    if (b.langTiers && typeof b.langTiers === 'object') {
      for (const [k, v] of Object.entries(b.langTiers)) if (ANSWER_LANGS.includes(k) && ['full', 'beta', 'interface'].includes(v)) tiers[k] = v;
    }
    const lang = LANGS.includes(b.defaultLang) ? b.defaultLang : o.default_lang;
    const ret = Number.isInteger(b.retentionDays) && b.retentionDays >= 30 && b.retentionDays <= 3650 ? b.retentionDays : o.retention_days;
    const lim = Number.isInteger(b.aiMonthlyLimit) && b.aiMonthlyLimit >= 0 ? b.aiMonthlyLimit : o.ai_monthly_limit;
    run(db, 'UPDATE orgs SET default_lang=?, retention_days=?, ai_monthly_limit=?, lang_tiers=? WHERE id=?', lang, ret, lim, JSON.stringify(tiers), o.id);
    A(req, 'org_settings_changed', 'org', o.id, { defaultLang: lang, retentionDays: ret, aiMonthlyLimit: lim, langTiers: tiers });
    res.json({ ok: true });
  }));

  admin.get('/users', (req, res) => {
    res.json(all(db, 'SELECT id, role, name, username, email, code, age_band, active, totp_enabled FROM users WHERE org_id=? ORDER BY role, name', req.user.org_id));
  });

  function createUser(orgId, { role, name, username, email, ageBand }) {
    if (!['teacher', 'student', 'admin'].includes(role)) throw bad('bad_role');
    if (!name || !/^[a-z0-9._-]{3,}$/.test(username)) throw bad('bad_username');
    if (one(db, 'SELECT id FROM users WHERE username=?', username)) throw bad('username_taken');
    const band = ['under13', 'under18', 'adult'].includes(ageBand) ? ageBand : (role === 'student' ? 'under18' : 'adult');
    const pw = tempPassword();
    const prefix = role === 'student' ? 'S-' : role === 'teacher' ? 'T-' : 'A-';
    const r = run(db, 'INSERT INTO users (org_id,role,name,username,email,pass_hash,age_band,code,must_change_password) VALUES (?,?,?,?,?,?,?,?,1)',
      orgId, role, name, username, email || null, hashPassword(pw), band, prefix + randomCode(5));
    return { id: Number(r.lastInsertRowid), username, tempPassword: pw };
  }

  admin.post('/users', wrap(async (req, res) => {
    const u = createUser(req.user.org_id, { role: req.body.role, name: str(req.body.name, 200), username: str(req.body.username, 100).toLowerCase(), email: str(req.body.email, 200), ageBand: req.body.ageBand });
    A(req, 'user_created', 'user', u.id, { role: req.body.role });
    res.json(u);
  }));

  // CSV: role,name,username,email,age_band,class_code
  admin.post('/import-csv', wrap(async (req, res) => {
    const rows = parseCsv(String(req.body.csv || ''));
    if (!rows.length) throw bad('empty_csv');
    const header = rows[0].map((h) => h.trim().toLowerCase());
    const hasHeader = header.includes('username');
    const cols = hasHeader ? header : ['role', 'name', 'username', 'email', 'age_band', 'class_code'];
    const data = hasHeader ? rows.slice(1) : rows;
    if (data.length > 2000) throw bad('too_many_rows');
    const results = [];
    tx(db, () => {
      for (const [i, r] of data.entries()) {
        const rec = Object.fromEntries(cols.map((c, j) => [c, (r[j] || '').trim()]));
        try {
          const u = createUser(req.user.org_id, { role: rec.role.toLowerCase(), name: rec.name, username: rec.username.toLowerCase(), email: rec.email, ageBand: rec.age_band });
          if (rec.class_code) {
            const cls = one(db, 'SELECT id FROM classes WHERE code=? AND org_id=?', rec.class_code.toUpperCase(), req.user.org_id);
            if (cls && rec.role.toLowerCase() === 'student') run(db, 'INSERT OR IGNORE INTO enrollments (org_id,class_id,student_id) VALUES (?,?,?)', req.user.org_id, cls.id, u.id);
          }
          results.push({ row: i + 1, ok: true, ...u });
        } catch (e) {
          results.push({ row: i + 1, ok: false, username: rec.username, error: e.code || e.message });
        }
      }
    });
    A(req, 'csv_import', null, null, { created: results.filter((r) => r.ok).length, failed: results.filter((r) => !r.ok).length });
    res.json({ results });
  }));

  admin.post('/users/:id/reset-link', wrap(async (req, res) => {
    const u = one(db, 'SELECT * FROM users WHERE id=? AND org_id=?', Number(req.params.id), req.user.org_id);
    if (!u) throw notFound();
    const link = createResetLink(u.id);
    if (u.email) await sendMail(cfg, { to: u.email, subject: 'Password reset', text: `Your school admin created a password reset link for you. It works for 1 hour:\n${link}` });
    A(req, 'reset_link_created', 'user', u.id);
    res.json({ link, emailed: !!u.email });
  }));

  admin.post('/users/:id/active', wrap(async (req, res) => {
    const u = one(db, 'SELECT * FROM users WHERE id=? AND org_id=?', Number(req.params.id), req.user.org_id);
    if (!u || u.id === req.user.id) throw notFound();
    run(db, 'UPDATE users SET active=? WHERE id=?', req.body.active ? 1 : 0, u.id);
    if (!req.body.active) run(db, 'DELETE FROM sessions WHERE user_id=?', u.id);
    A(req, req.body.active ? 'user_activated' : 'user_deactivated', 'user', u.id);
    res.json({ ok: true });
  }));

  admin.post('/users/:id/reset-mfa', wrap(async (req, res) => {
    const u = one(db, 'SELECT * FROM users WHERE id=? AND org_id=?', Number(req.params.id), req.user.org_id);
    if (!u) throw notFound();
    run(db, 'UPDATE users SET totp_enabled=0, totp_secret=NULL WHERE id=?', u.id);
    run(db, 'DELETE FROM sessions WHERE user_id=?', u.id);
    A(req, 'mfa_reset', 'user', u.id);
    res.json({ ok: true });
  }));

  admin.delete('/students/:id/data', wrap(async (req, res) => {
    const r = deleteStudentData(db, cfg, req.user.org_id, Number(req.params.id), req.user.id);
    if (!r) throw notFound();
    res.json(r);
  }));

  admin.get('/classes', (req, res) => {
    res.json(all(db, `SELECT c.*, u.name teacher_name, (SELECT COUNT(*) FROM enrollments e WHERE e.class_id=c.id) students
      FROM classes c JOIN users u ON u.id=c.teacher_id WHERE c.org_id=? ORDER BY c.name`, req.user.org_id));
  });
  admin.post('/classes', wrap(async (req, res) => {
    const t = one(db, "SELECT id FROM users WHERE id=? AND org_id=? AND role='teacher'", Number(req.body.teacherId), req.user.org_id);
    if (!t || !str(req.body.name)) throw bad('missing_fields');
    let code; do { code = randomCode(6); } while (one(db, 'SELECT id FROM classes WHERE code=?', code));
    const r = run(db, 'INSERT INTO classes (org_id,name,teacher_id,code) VALUES (?,?,?,?)', req.user.org_id, str(req.body.name, 200), t.id, code);
    A(req, 'class_created', 'class', Number(r.lastInsertRowid));
    res.json({ id: Number(r.lastInsertRowid), code });
  }));
  admin.post('/classes/:id/enroll', wrap(async (req, res) => {
    const cls = one(db, 'SELECT * FROM classes WHERE id=? AND org_id=?', Number(req.params.id), req.user.org_id);
    if (!cls) throw notFound();
    let n = 0;
    for (const sid of [].concat(req.body.studentIds || [])) {
      const s = one(db, "SELECT id FROM users WHERE id=? AND org_id=? AND role='student'", Number(sid), req.user.org_id);
      if (s) n += run(db, 'INSERT OR IGNORE INTO enrollments (org_id,class_id,student_id) VALUES (?,?,?)', req.user.org_id, cls.id, s.id).changes;
    }
    A(req, 'students_enrolled', 'class', cls.id, { count: n });
    res.json({ enrolled: n });
  }));

  admin.get('/audit', (req, res) => {
    const limit = Math.min(Number(req.query.limit) || 200, 1000);
    res.json(all(db, `SELECT a.*, u.name user_name, u.role user_role FROM audit_log a LEFT JOIN users u ON u.id=a.user_id WHERE a.org_id=? ORDER BY a.id DESC LIMIT ?`, req.user.org_id, limit));
  });
  admin.get('/quality', (req, res) => {
    res.json({
      all: qualityReport(db, req.user.org_id),
      en: qualityReport(db, req.user.org_id, { lang: 'en' }),
      ur: qualityReport(db, req.user.org_id, { lang: 'ur' }),
      'ur-roman': qualityReport(db, req.user.org_id, { lang: 'ur-roman' }),
    });
  });
  admin.get('/usage', (req, res) => {
    const o = one(db, 'SELECT ai_monthly_limit FROM orgs WHERE id=?', req.user.org_id);
    res.json({ limit: o.ai_monthly_limit, months: all(db, 'SELECT * FROM ai_usage WHERE org_id=? ORDER BY month DESC LIMIT 12', req.user.org_id), aiMode: cfg.aiMode });
  });
  admin.get('/system', (req, res) => {
    res.json({
      events: all(db, "SELECT * FROM system_events WHERE type IN ('backup','restore_test','retention') ORDER BY id DESC LIMIT 30"),
      deletions: all(db, 'SELECT * FROM deletions WHERE org_id=? ORDER BY id DESC LIMIT 50', req.user.org_id),
      failedJobs: all(db, "SELECT id, ref_id, attempts, last_error FROM jobs WHERE org_id=? AND status='failed' ORDER BY id DESC LIMIT 20", req.user.org_id),
    });
  });
  // Backup and restore test cover the whole server; only an admin of the first organization (the server owner) may run them.
  const ownerOnly = (req) => { if (one(db, 'SELECT MIN(id) id FROM orgs').id !== req.user.org_id) throw forbidden(); };
  admin.post('/backup-now', wrap(async (req, res) => { ownerOnly(req); backupNow(db, cfg); A(req, 'backup_manual'); res.json({ ok: true }); }));
  admin.post('/restore-test', wrap(async (req, res) => { ownerOnly(req); const r = restoreTest(db, cfg); A(req, 'restore_test_manual', null, null, { ok: r.ok }); res.json(r); }));

  app.use('/api/admin', admin);

  // =========================== TEACHER ===========================
  const teacher = express.Router();
  teacher.use(auth('teacher'));

  const myClass = (req, id) => one(db, 'SELECT * FROM classes WHERE id=? AND org_id=? AND teacher_id=?', Number(id), req.user.org_id, req.user.id);
  const myTask = (req, id) => one(db, `SELECT t.* FROM tasks t JOIN classes c ON c.id=t.class_id
    WHERE t.id=? AND t.org_id=? AND c.teacher_id=?`, Number(id), req.user.org_id, req.user.id);
  function mySub(req, id) {
    const sub = one(db, 'SELECT * FROM submissions WHERE id=? AND org_id=?', Number(id), req.user.org_id);
    if (!sub || !canSeeSubmission(req.user, sub)) throw notFound();
    return sub;
  }

  teacher.get('/classes', (req, res) => {
    res.json(all(db, `SELECT c.*, (SELECT COUNT(*) FROM enrollments e WHERE e.class_id=c.id) students FROM classes c WHERE c.org_id=? AND c.teacher_id=? ORDER BY name`, req.user.org_id, req.user.id));
  });
  teacher.put('/classes/:id', wrap(async (req, res) => {
    const c = myClass(req, req.params.id); if (!c) throw notFound();
    run(db, 'UPDATE classes SET ai_enabled=? WHERE id=?', req.body.aiEnabled ? 1 : 0, c.id);
    A(req, 'class_ai_toggled', 'class', c.id, { aiEnabled: !!req.body.aiEnabled });
    res.json({ ok: true });
  }));
  teacher.get('/classes/:id/tasks', (req, res) => {
    const c = myClass(req, req.params.id); if (!c) return res.status(404).json({ error: 'not_found' });
    res.json(all(db, `SELECT t.id, t.title, t.due_at, t.answer_lang, t.mode,
      (SELECT COUNT(*) FROM submissions s WHERE s.task_id=t.id) submitted,
      (SELECT COUNT(*) FROM submissions s WHERE s.task_id=t.id AND s.status='approved') approved,
      (SELECT COUNT(*) FROM submissions s WHERE s.task_id=t.id AND s.recheck_status='requested') rechecks
      FROM tasks t WHERE t.class_id=? AND t.org_id=? ORDER BY t.id DESC`, c.id, req.user.org_id));
  });

  function validateRubric(rubric) {
    if (!Array.isArray(rubric) || !rubric.length || rubric.length > 12) throw bad('bad_rubric');
    return rubric.map((c, i) => {
      const max = Number(c.max);
      if (!str(c.name) || !Number.isFinite(max) || max <= 0 || max > 100) throw bad('bad_rubric');
      return { id: 'c' + (i + 1), name: str(c.name, 200), max, description: str(c.description, 1000) };
    });
  }

  teacher.post('/tasks', wrap(async (req, res) => {
    const c = myClass(req, req.body.classId); if (!c) throw notFound();
    const b = req.body;
    const rubric = validateRubric(b.rubric);
    const samples = (Array.isArray(b.samples) ? b.samples : []).slice(0, 6).map((s) => ({ level: str(s.level, 50), text: str(s.text, 6000) })).filter((s) => s.text);
    if (!str(b.title) || !str(b.instructions)) throw bad('missing_fields');
    const answerLang = ANSWER_LANGS.includes(b.answerLang) ? b.answerLang : 'en';
    const mode = ['typed', 'file', 'photo', 'any'].includes(b.mode) ? b.mode : 'any';
    const crop = Math.max(0, Math.min(40, Number(b.headerCropPct ?? 15) | 0));
    const r = run(db, `INSERT INTO tasks (org_id,class_id,teacher_id,title,instructions,rubric,samples,answer_lang,feedback_lang,mode,handwritten_only,header_crop_pct,extra_time_note,due_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, req.user.org_id, c.id, req.user.id, str(b.title, 200), str(b.instructions, 8000), JSON.stringify(rubric), JSON.stringify(samples),
      answerLang, LANGS.includes(b.feedbackLang) ? b.feedbackLang : null, b.handwrittenOnly ? 'photo' : mode, b.handwrittenOnly ? 1 : 0, crop, str(b.extraTimeNote, 300) || null, str(b.dueAt, 40) || null);
    A(req, 'task_created', 'task', Number(r.lastInsertRowid));
    res.json({ id: Number(r.lastInsertRowid) });
  }));

  // Task dashboard: only approved scores are final
  teacher.get('/tasks/:id', wrap(async (req, res) => {
    const t = myTask(req, req.params.id); if (!t) throw notFound();
    const subs = all(db, `SELECT s.id, s.kind, s.status, s.blind, s.recheck_status, s.submitted_at, s.final_total, u.name student_name, u.code student_code,
      (SELECT flags FROM ai_drafts d WHERE d.submission_id=s.id ORDER BY id DESC LIMIT 1) flags
      FROM submissions s JOIN users u ON u.id=s.student_id WHERE s.task_id=? AND s.org_id=?`, t.id, req.user.org_id);
    const enrolled = all(db, `SELECT u.id, u.name, u.code FROM enrollments e JOIN users u ON u.id=e.student_id WHERE e.class_id=? AND e.org_id=?`, t.class_id, req.user.org_id);
    const rubric = JSON.parse(t.rubric);
    const max = rubric.reduce((a, c) => a + c.max, 0);
    const queue = subs.map((s) => ({
      ...s,
      flags: JSON.parse(s.flags || '[]').map((f) => f.type),
      final_total: s.status === 'approved' ? s.final_total : null,
      priority: (s.recheck_status === 'requested' ? 3 : 0) + (JSON.parse(s.flags || '[]').length ? 2 : 0) + (s.kind === 'photo' ? 1 : 0),
    })).sort((a, b) => b.priority - a.priority || a.id - b.id);
    const finals = queue.filter((s) => s.status === 'approved').map((s) => s.final_total);
    res.json({
      task: { ...t, rubric, samples: JSON.parse(t.samples) }, max, queue,
      notSubmitted: enrolled.filter((e) => !subs.some((s) => s.student_code === e.code)),
      stats: { enrolled: enrolled.length, submitted: subs.length, approved: finals.length, average: finals.length ? Math.round(finals.reduce((a, b) => a + b, 0) / finals.length * 10) / 10 : null },
    });
  }));

  function draftFor(sub) {
    return one(db, 'SELECT * FROM ai_drafts WHERE submission_id=? AND org_id=? ORDER BY id DESC LIMIT 1', sub.id, sub.org_id);
  }

  // Review of one answer. The AI draft is shown only if it is not a blind sample and, with "score first", after the teacher's estimate.
  teacher.get('/submissions/:id', wrap(async (req, res) => {
    const sub = mySub(req, req.params.id);
    const task = one(db, 'SELECT * FROM tasks WHERE id=? AND org_id=?', sub.task_id, req.user.org_id);
    const student = one(db, 'SELECT name, code FROM users WHERE id=?', sub.student_id);
    const files = all(db, 'SELECT id, role, name, mime, size FROM files WHERE submission_id=? AND org_id=?', sub.id, req.user.org_id)
      .map((f) => ({ ...f, url: signLink(cfg, f.id, req.user.id) }));
    const d = draftFor(sub);
    let draft = null, draftHidden = null;
    if (d) {
      if (sub.blind) draftHidden = 'blind_sample';
      else if (req.user.score_first && sub.teacher_estimate === null && sub.status !== 'approved') draftHidden = 'score_first';
      else {
        const beta = d.tier === 'beta';
        draft = {
          model: d.model, promptVersion: d.prompt_version, tier: d.tier,
          scores: JSON.parse(d.scores).map((s) => (beta ? { ...s, score: null } : s)),
          total: beta ? null : d.total,
          feedbackStudent: d.feedback_student, feedbackTeacher: d.feedback_teacher,
          transcription: d.transcription, flags: JSON.parse(d.flags), oralQuestions: JSON.parse(d.oral_questions),
        };
      }
    }
    A(req, 'submission_viewed', 'submission', sub.id);
    res.json({
      submission: { ...sub, writing_log: JSON.parse(sub.writing_log || 'null'), checked: JSON.parse(sub.checked), final_scores: JSON.parse(sub.final_scores || 'null') },
      task: { id: task.id, title: task.title, instructions: task.instructions, rubric: JSON.parse(task.rubric), answerLang: task.answer_lang },
      student, files, draft, draftHidden, draftExists: !!d,
      needsChecks: sub.kind === 'photo',
    });
  }));

  teacher.post('/submissions/:id/estimate', wrap(async (req, res) => {
    const sub = mySub(req, req.params.id);
    const v = Number(req.body.total);
    if (!Number.isFinite(v) || v < 0) throw bad('bad_estimate');
    run(db, 'UPDATE submissions SET teacher_estimate=? WHERE id=?', v, sub.id);
    A(req, 'teacher_estimate', 'submission', sub.id, { total: v });
    res.json({ ok: true });
  }));

  teacher.post('/submissions/:id/check', wrap(async (req, res) => {
    const sub = mySub(req, req.params.id);
    const rubric = JSON.parse(one(db, 'SELECT rubric FROM tasks WHERE id=?', sub.task_id).rubric);
    if (!rubric.some((c) => c.id === req.body.criterionId)) throw bad('bad_criterion');
    const checked = JSON.parse(sub.checked);
    checked[req.body.criterionId] = !!req.body.checked;
    run(db, 'UPDATE submissions SET checked=? WHERE id=?', JSON.stringify(checked), sub.id);
    res.json({ checked });
  }));

  // Approval = release to that student. The score is always the teacher's.
  teacher.post('/submissions/:id/approve', wrap(async (req, res) => {
    const sub = mySub(req, req.params.id);
    const task = one(db, 'SELECT * FROM tasks WHERE id=?', sub.task_id);
    const rubric = JSON.parse(task.rubric);
    const okStatus = ['draft_ready', 'ai_unavailable', 'submitted'].includes(sub.status) || (sub.status === 'approved' && sub.recheck_status === 'requested');
    if (!okStatus) throw bad('not_reviewable');
    const given = new Map((Array.isArray(req.body.scores) ? req.body.scores : []).map((s) => [s.id, Number(s.score)]));
    const scores = rubric.map((c) => {
      const v = given.get(c.id);
      if (!Number.isFinite(v) || v < 0 || v > c.max) throw bad('score_out_of_range');
      return { id: c.id, score: v };
    });
    if (sub.kind === 'photo') {
      const checked = JSON.parse(sub.checked);
      if (!rubric.every((c) => checked[c.id])) throw bad('check_every_answer');
    }
    const feedback = str(req.body.feedback, 6000);
    if (!feedback) throw bad('feedback_required');
    const total = scores.reduce((a, s) => a + s.score, 0);
    const reviewMs = Number.isFinite(Number(req.body.reviewMs)) ? Math.min(Number(req.body.reviewMs), 3 * 3600_000) : null;
    const before = sub.status === 'approved' ? { scores: JSON.parse(sub.final_scores), total: sub.final_total } : null;
    const d = draftFor(sub);
    tx(db, () => {
      run(db, `UPDATE submissions SET status='approved', final_scores=?, final_total=?, final_feedback=?, approved_by=?, approved_at=datetime('now'),
        review_ms=COALESCE(review_ms, ?), recheck_status=CASE WHEN recheck_status='requested' THEN 'done' ELSE recheck_status END WHERE id=?`,
        JSON.stringify(scores), total, feedback, req.user.id, reviewMs, sub.id);
      A(req, before ? 'recheck_approved' : 'approved_released', 'submission', sub.id, {
        before, after: { scores, total }, aiDraftTotal: d?.total ?? null, aiDraftId: d?.id ?? null, promptVersion: d?.prompt_version ?? null,
      });
    });
    res.json({ ok: true, total });
  }));

  teacher.post('/submissions/:id/return', wrap(async (req, res) => {
    const sub = mySub(req, req.params.id);
    if (sub.status === 'approved') throw bad('already_approved');
    run(db, "UPDATE submissions SET status='returned', final_feedback=? WHERE id=?", str(req.body.note, 2000) || null, sub.id);
    A(req, 'returned_to_student', 'submission', sub.id);
    res.json({ ok: true });
  }));

  teacher.post('/submissions/:id/redraft', wrap(async (req, res) => {
    const sub = mySub(req, req.params.id);
    if (!['ai_unavailable', 'draft_ready'].includes(sub.status)) throw bad('not_allowed');
    run(db, "UPDATE submissions SET status='submitted' WHERE id=?", sub.id);
    enqueueDraft(db, sub.org_id, sub.id);
    A(req, 'redraft_requested', 'submission', sub.id);
    res.json({ ok: true });
  }));

  app.use('/api/teacher', teacher);

  // =========================== STUDENT ===========================
  const student = express.Router();
  student.use(auth('student'));

  // A class code only links an existing account to a class; it never creates one.
  student.post('/join', wrap(async (req, res) => {
    const cls = one(db, 'SELECT id, name FROM classes WHERE code=? AND org_id=?', str(req.body.code, 20).toUpperCase(), req.user.org_id);
    if (!cls) throw notFound();
    run(db, 'INSERT OR IGNORE INTO enrollments (org_id,class_id,student_id) VALUES (?,?,?)', req.user.org_id, cls.id, req.user.id);
    A(req, 'joined_class', 'class', cls.id);
    res.json({ ok: true, className: cls.name });
  }));

  function studentTask(req, id) {
    return one(db, `SELECT t.*, c.name class_name FROM tasks t JOIN classes c ON c.id=t.class_id
      JOIN enrollments e ON e.class_id=t.class_id AND e.student_id=?
      WHERE t.id=? AND t.org_id=?`, req.user.id, Number(id), req.user.org_id);
  }
  function studentView(sub) {
    if (!sub) return null;
    const out = { id: sub.id, kind: sub.kind, status: sub.status === 'approved' ? 'approved' : sub.status === 'returned' ? 'returned' : 'submitted', submittedAt: sub.submitted_at, recheckStatus: sub.recheck_status, text: sub.text };
    if (sub.status === 'approved') {
      out.scores = JSON.parse(sub.final_scores);
      out.total = sub.final_total;
      out.feedback = sub.final_feedback;
      out.aiAssisted = !!one(db, 'SELECT id FROM ai_drafts WHERE submission_id=?', sub.id);
    }
    if (sub.status === 'returned') out.note = sub.final_feedback;
    return out;
  }

  student.get('/tasks', (req, res) => {
    const tasks = all(db, `SELECT t.id, t.title, t.due_at, t.mode, t.class_id, c.name class_name, t.rubric FROM tasks t
      JOIN classes c ON c.id=t.class_id JOIN enrollments e ON e.class_id=t.class_id AND e.student_id=?
      WHERE t.org_id=? ORDER BY t.id DESC`, req.user.id, req.user.org_id);
    res.json(tasks.map((t) => {
      const sub = one(db, 'SELECT * FROM submissions WHERE task_id=? AND student_id=?', t.id, req.user.id);
      const v = studentView(sub);
      return { id: t.id, title: t.title, dueAt: t.due_at, mode: t.mode, className: t.class_name, max: JSON.parse(t.rubric).reduce((a, c) => a + c.max, 0), status: v?.status || 'todo', total: v?.total ?? null };
    }));
  });

  student.get('/tasks/:id', wrap(async (req, res) => {
    const t = studentTask(req, req.params.id); if (!t) throw notFound();
    const sub = one(db, 'SELECT * FROM submissions WHERE task_id=? AND student_id=?', t.id, req.user.id);
    const files = sub ? all(db, "SELECT id, name, mime FROM files WHERE submission_id=? AND role='original'", sub.id).map((f) => ({ ...f, url: signLink(cfg, f.id, req.user.id) })) : [];
    A(req, 'task_viewed', 'task', t.id);
    res.json({
      task: { id: t.id, title: t.title, instructions: t.instructions, className: t.class_name, rubric: JSON.parse(t.rubric).map(({ id, name, max, description }) => ({ id, name, max, description })),
        mode: t.mode, handwrittenOnly: !!t.handwritten_only, headerCropPct: t.header_crop_pct, answerLang: t.answer_lang, dueAt: t.due_at, extraTimeNote: t.extra_time_note },
      submission: studentView(sub), files,
    });
  }));

  const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 8 * 1024 * 1024, files: 2 } });
  const ALLOWED = /^(image\/(jpeg|png|webp)|application\/pdf)$/;

  student.post('/tasks/:id/submit', upload.fields([{ name: 'original', maxCount: 1 }, { name: 'answer', maxCount: 1 }]), wrap(async (req, res) => {
    const t = studentTask(req, req.params.id); if (!t) throw notFound();
    const existing = one(db, 'SELECT * FROM submissions WHERE task_id=? AND student_id=?', t.id, req.user.id);
    if (existing && existing.status !== 'returned') throw bad('already_submitted');
    const kind = ['typed', 'file', 'photo'].includes(req.body.kind) ? req.body.kind : null;
    if (!kind) throw bad('bad_kind');
    if (t.mode !== 'any' && t.mode !== kind) throw bad('wrong_kind_for_task');
    const text = kind === 'typed' ? str(req.body.text, 30000) : null;
    if (kind === 'typed' && !text) throw bad('empty_answer');
    const original = req.files?.original?.[0];
    const answer = req.files?.answer?.[0] || original;
    if (kind !== 'typed') {
      if (!original) throw bad('file_required');
      for (const f of [original, answer]) if (!ALLOWED.test(f.mimetype)) throw bad('file_type_not_allowed');
      if (kind === 'photo' && !/^image\//.test(original.mimetype)) throw bad('photo_required');
    }
    let log = null;
    try {
      const l = JSON.parse(req.body.writingLog || 'null');
      if (l && typeof l === 'object') log = {
        startedAt: str(l.startedAt, 40), durationMs: Number(l.durationMs) || 0, keystrokes: Number(l.keystrokes) || 0,
        pastes: (Array.isArray(l.pastes) ? l.pastes : []).slice(0, 200).map((p) => ({ at: Number(p.at) || 0, chars: Number(p.chars) || 0 })),
      };
      if (log) log.largestPaste = log.pastes.reduce((m, p) => Math.max(m, p.chars), 0);
    } catch { /* submit even without a writing record */ }

    const blind = Math.random() < cfg.blindSampleRate ? 1 : 0;
    const subId = tx(db, () => {
      if (existing) run(db, 'DELETE FROM submissions WHERE id=?', existing.id);
      const r = run(db, 'INSERT INTO submissions (org_id,task_id,student_id,kind,text,writing_log,blind) VALUES (?,?,?,?,?,?,?)',
        req.user.org_id, t.id, req.user.id, kind, text, log ? JSON.stringify(log) : null, blind);
      const id = Number(r.lastInsertRowid);
      if (original) {
        const save = (f, role) => {
          const e = saveEncrypted(cfg, req.user.org_id, f.buffer);
          run(db, 'INSERT INTO files (org_id,submission_id,role,name,mime,size,path,iv,tag) VALUES (?,?,?,?,?,?,?,?,?)',
            req.user.org_id, id, role, str(f.originalname, 200) || role, f.mimetype, f.size, e.path, e.iv, e.tag);
        };
        save(original, 'original');
        save(answer, 'answer');
      }
      enqueueDraft(db, req.user.org_id, id);
      return id;
    });
    A(req, existing ? 'resubmitted' : 'submitted', 'submission', subId, { kind });
    // The student gets "received" at once; the draft is made in the background.
    res.json({ ok: true, id: subId });
  }));

  student.post('/submissions/:id/recheck', wrap(async (req, res) => {
    const sub = one(db, 'SELECT * FROM submissions WHERE id=? AND org_id=? AND student_id=?', Number(req.params.id), req.user.org_id, req.user.id);
    if (!sub) throw notFound();
    if (sub.status !== 'approved' || sub.recheck_status) throw bad('recheck_not_allowed');
    const reason = str(req.body.reason, 1000);
    if (!reason) throw bad('reason_required');
    run(db, "UPDATE submissions SET recheck_status='requested', recheck_reason=? WHERE id=?", reason, sub.id);
    A(req, 'recheck_requested', 'submission', sub.id, { reason });
    res.json({ ok: true });
  }));

  app.use('/api/student', student);

  // Static PWA
  app.use(express.static(path.join(__dirname, '..', 'public'), { index: 'index.html', maxAge: 0 }));
  app.use('/api', (req, res) => res.status(404).json({ error: 'not_found' }));

  // Errors
  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    if (err instanceof multer.MulterError) return res.status(400).json({ error: 'upload_' + err.code.toLowerCase() });
    if (err instanceof HttpError) return res.status(err.status).json({ error: err.code });
    if (err?.type === 'entity.parse.failed') return res.status(400).json({ error: 'bad_json' });
    console.error(err);
    res.status(500).json({ error: 'server_error' });
  });

  app.locals.db = db;
  app.locals.grader = grader;
  app.locals.close = () => { stops.forEach((s) => s()); db.close(); };
  return app;
}

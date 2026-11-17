import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { all, run, one, audit, tx } from './db.js';
import { sha256 } from './security.js';

const today = () => new Date().toISOString().slice(0, 10);

function event(db, type, ok, detail) {
  run(db, 'INSERT INTO system_events (type,ok,detail) VALUES (?,?,?)', type, ok ? 1 : 0, JSON.stringify(detail));
}

// Daily backup: database (VACUUM INTO) plus a copy of the encrypted files.
export function backupNow(db, cfg) {
  const dir = path.join(cfg.backupDir, `backup-${new Date().toISOString().replace(/[:.]/g, '-')}`);
  fs.mkdirSync(dir, { recursive: true });
  db.exec(`VACUUM INTO '${path.join(dir, 'app.db').replace(/'/g, "''")}'`);
  if (fs.existsSync(cfg.uploadDir)) fs.cpSync(cfg.uploadDir, path.join(dir, 'uploads'), { recursive: true });
  // Remove old backups, keep the latest N
  const dirs = fs.readdirSync(cfg.backupDir).filter((d) => d.startsWith('backup-')).sort();
  for (const d of dirs.slice(0, Math.max(0, dirs.length - cfg.backupKeep))) fs.rmSync(path.join(cfg.backupDir, d), { recursive: true, force: true });
  event(db, 'backup', true, { dir: path.basename(dir) });
  return dir;
}

// Restore test: open the latest backup, run an integrity check, compare counts.
export function restoreTest(db, cfg) {
  if (!fs.existsSync(cfg.backupDir)) { event(db, 'restore_test', false, { error: 'no backups' }); return { ok: false, error: 'no backups' }; }
  const dirs = fs.readdirSync(cfg.backupDir).filter((d) => d.startsWith('backup-')).sort();
  if (!dirs.length) { event(db, 'restore_test', false, { error: 'no backups' }); return { ok: false, error: 'no backups' }; }
  const latest = path.join(cfg.backupDir, dirs.at(-1));
  try {
    const b = new DatabaseSync(path.join(latest, 'app.db'), { readOnly: true });
    const integrity = b.prepare('PRAGMA integrity_check').get();
    const counts = {};
    for (const t of ['orgs', 'users', 'tasks', 'submissions', 'files']) counts[t] = b.prepare(`SELECT COUNT(*) n FROM ${t}`).get().n;
    const files = b.prepare('SELECT path FROM files').all();
    const missing = files.filter((f) => !fs.existsSync(path.join(latest, 'uploads', f.path))).length;
    b.close();
    const ok = Object.values(integrity)[0] === 'ok' && missing === 0;
    event(db, 'restore_test', ok, { backup: dirs.at(-1), counts, missingFiles: missing });
    return { ok, backup: dirs.at(-1), counts, missingFiles: missing };
  } catch (e) {
    event(db, 'restore_test', false, { error: e.message });
    return { ok: false, error: e.message };
  }
}

// Delete submissions and their files.
export function deleteSubmissions(db, cfg, orgId, ids) {
  if (!ids.length) return [];
  const files = all(db, `SELECT * FROM files WHERE org_id=? AND submission_id IN (${ids.map(() => '?').join(',')})`, orgId, ...ids);
  for (const f of files) fs.rmSync(path.join(cfg.uploadDir, f.path), { force: true });
  run(db, `DELETE FROM submissions WHERE org_id=? AND id IN (${ids.map(() => '?').join(',')})`, orgId, ...ids);
  return files.length;
}

function recordDeletion(db, orgId, reason, summary) {
  const at = new Date().toISOString();
  const proof = sha256(JSON.stringify({ orgId, reason, summary, at }));
  run(db, 'INSERT INTO deletions (org_id,reason,summary,proof,at) VALUES (?,?,?,?,?)', orgId, reason, JSON.stringify(summary), proof, at);
  return { proof, at };
}

// Retention: delete old answers, drafts and files; keep the audit log for 3 years.
export function applyRetention(db, cfg) {
  const orgs = all(db, 'SELECT id, retention_days FROM orgs');
  let total = 0;
  for (const o of orgs) {
    const ids = all(db, `SELECT id FROM submissions WHERE org_id=? AND submitted_at < datetime('now', ?)`, o.id, `-${o.retention_days} days`).map((r) => r.id);
    if (ids.length) {
      const nFiles = tx(db, () => deleteSubmissions(db, cfg, o.id, ids));
      recordDeletion(db, o.id, 'retention', { submissions: ids.length, files: nFiles, olderThanDays: o.retention_days });
      total += ids.length;
    }
  }
  run(db, "DELETE FROM audit_log WHERE at < datetime('now','-1095 days')");
  run(db, "DELETE FROM sessions WHERE expires_at < ?", Date.now());
  run(db, "DELETE FROM password_resets WHERE expires_at < ?", Date.now());
  event(db, 'retention', true, { deletedSubmissions: total });
  return total;
}

// Delete all of a student's data at the school's request, with a proof record.
export function deleteStudentData(db, cfg, orgId, studentId, byUserId) {
  const student = one(db, "SELECT * FROM users WHERE id=? AND org_id=? AND role='student'", studentId, orgId);
  if (!student) return null;
  const result = tx(db, () => {
    const ids = all(db, 'SELECT id FROM submissions WHERE org_id=? AND student_id=?', orgId, studentId).map((r) => r.id);
    const nFiles = deleteSubmissions(db, cfg, orgId, ids);
    run(db, 'DELETE FROM enrollments WHERE org_id=? AND student_id=?', orgId, studentId);
    run(db, 'DELETE FROM sessions WHERE user_id=?', studentId);
    // Anonymise the account (the id stays for the audit log, the name does not)
    run(db, "UPDATE users SET name='[deleted]', username=?, email=NULL, pass_hash='x', active=0, totp_secret=NULL WHERE id=?", `deleted-${studentId}-${Date.now()}`, studentId);
    return { submissions: ids.length, files: nFiles };
  });
  const proof = recordDeletion(db, orgId, 'school_request', { studentCode: student.code, ...result });
  audit(db, { orgId, userId: byUserId, action: 'student_data_deleted', entity: 'user', entityId: studentId, detail: { proof: proof.proof } });
  return { ...result, ...proof };
}

// Small daily scheduler (its state lives in the database).
export function startScheduler(db, cfg) {
  const tick = () => {
    try {
      const last = (type) => one(db, 'SELECT at FROM system_events WHERE type=? AND ok=1 ORDER BY id DESC LIMIT 1', type)?.at?.slice(0, 10);
      if (last('backup') !== today()) backupNow(db, cfg);
      if (last('retention') !== today()) applyRetention(db, cfg);
      const lastRestore = one(db, "SELECT at FROM system_events WHERE type='restore_test' ORDER BY id DESC LIMIT 1")?.at;
      if (!lastRestore || Date.now() - Date.parse(lastRestore + 'Z') > 30 * 86400_000) restoreTest(db, cfg);
    } catch (e) {
      console.error('[scheduler]', e);
    }
  };
  tick();
  const t = setInterval(tick, 60 * 60 * 1000);
  t.unref();
  return () => clearInterval(t);
}

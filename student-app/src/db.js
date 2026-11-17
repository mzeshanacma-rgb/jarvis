import { DatabaseSync } from 'node:sqlite';

// Every table carries org_id. Every query filters by org_id (tenant isolation).
const SCHEMA = `
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS orgs (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  default_lang TEXT NOT NULL DEFAULT 'en',
  plan TEXT NOT NULL DEFAULT 'trial',          -- trial | licence | adult_free
  trial_ends_at TEXT,
  ai_monthly_limit INTEGER NOT NULL DEFAULT 300,
  retention_days INTEGER NOT NULL DEFAULT 365,
  precheck_enabled INTEGER NOT NULL DEFAULT 0,
  lang_tiers TEXT NOT NULL DEFAULT '{"en":"beta","ur":"beta"}',
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES orgs(id),
  role TEXT NOT NULL CHECK (role IN ('admin','teacher','student')),
  name TEXT NOT NULL,
  username TEXT NOT NULL,
  email TEXT,
  pass_hash TEXT NOT NULL,
  lang TEXT,
  feedback_lang TEXT,
  age_band TEXT NOT NULL DEFAULT 'under18',   -- under13 | under18 | adult
  code TEXT NOT NULL,                         -- Claude ko naam ki jagah yeh code jata hai
  totp_secret TEXT,
  totp_enabled INTEGER NOT NULL DEFAULT 0,
  score_first INTEGER NOT NULL DEFAULT 1,     -- "pehle main score karun" (sirf teacher)
  must_change_password INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS sessions (
  id_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mfa_ok INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS login_attempts (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  window_start INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS password_resets (
  token_hash TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL,
  used INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS classes (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL REFERENCES orgs(id),
  name TEXT NOT NULL,
  teacher_id INTEGER NOT NULL REFERENCES users(id),
  code TEXT NOT NULL UNIQUE,
  ai_enabled INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS enrollments (
  org_id INTEGER NOT NULL,
  class_id INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
  student_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (class_id, student_id)
);

CREATE TABLE IF NOT EXISTS tasks (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL,
  class_id INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
  teacher_id INTEGER NOT NULL,
  title TEXT NOT NULL,
  instructions TEXT NOT NULL,
  rubric TEXT NOT NULL,           -- JSON: [{id, name, max, description}]
  samples TEXT NOT NULL DEFAULT '[]', -- JSON: [{level, text}]
  answer_lang TEXT NOT NULL DEFAULT 'en',
  feedback_lang TEXT,             -- teacher ka override; warna student ki pasand
  mode TEXT NOT NULL DEFAULT 'any', -- typed | file | photo | any
  handwritten_only INTEGER NOT NULL DEFAULT 0,
  header_crop_pct INTEGER NOT NULL DEFAULT 15,
  extra_time_note TEXT,
  due_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS submissions (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL,
  task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  student_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,             -- typed | file | photo
  text TEXT,
  writing_log TEXT,               -- JSON: likhne ka record
  status TEXT NOT NULL DEFAULT 'submitted', -- submitted | draft_ready | ai_unavailable | approved | returned
  blind INTEGER NOT NULL DEFAULT 0,  -- 10% jawab: teacher ko AI draft nahi dikhta
  teacher_estimate REAL,
  checked TEXT NOT NULL DEFAULT '{}', -- haath ke likhe jawab: har criterion "checked"
  final_scores TEXT,
  final_total REAL,
  final_feedback TEXT,
  review_ms INTEGER,
  approved_by INTEGER,
  approved_at TEXT,
  recheck_reason TEXT,
  recheck_status TEXT,            -- requested | done
  submitted_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (task_id, student_id)
);

CREATE TABLE IF NOT EXISTS files (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL,
  submission_id INTEGER NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
  role TEXT NOT NULL,             -- original | answer (naam wala hissa kata hua)
  name TEXT NOT NULL,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  path TEXT NOT NULL,
  iv TEXT NOT NULL,
  tag TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_drafts (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL,
  submission_id INTEGER NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
  model TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  tier TEXT NOT NULL,             -- full | beta
  scores TEXT NOT NULL,           -- JSON: [{id, score, reason, confidence}]
  total REAL,
  feedback_student TEXT NOT NULL,
  feedback_teacher TEXT,
  transcription TEXT,
  flags TEXT NOT NULL DEFAULT '[]',
  oral_questions TEXT NOT NULL DEFAULT '[]',
  input_tokens INTEGER,
  output_tokens INTEGER,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS jobs (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL,
  type TEXT NOT NULL,
  ref_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued', -- queued | running | done | failed
  attempts INTEGER NOT NULL DEFAULT 0,
  run_after INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS ai_usage (
  org_id INTEGER NOT NULL,
  month TEXT NOT NULL,
  drafts INTEGER NOT NULL DEFAULT 0,
  input_tokens INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (org_id, month)
);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY,
  org_id INTEGER,
  user_id INTEGER,
  action TEXT NOT NULL,
  entity TEXT,
  entity_id INTEGER,
  detail TEXT,
  at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS deletions (
  id INTEGER PRIMARY KEY,
  org_id INTEGER NOT NULL,
  reason TEXT NOT NULL,
  summary TEXT NOT NULL,
  proof TEXT NOT NULL,
  at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS system_events (
  id INTEGER PRIMARY KEY,
  type TEXT NOT NULL,
  ok INTEGER NOT NULL,
  detail TEXT,
  at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_sub_task ON submissions(org_id, task_id);
CREATE INDEX IF NOT EXISTS idx_jobs ON jobs(status, run_after);
CREATE INDEX IF NOT EXISTS idx_audit ON audit_log(org_id, at);
`;

export function openDb(file) {
  const db = new DatabaseSync(file);
  db.exec(SCHEMA);
  return db;
}

// Small helpers
export const one = (db, sql, ...p) => db.prepare(sql).get(...p);
export const all = (db, sql, ...p) => db.prepare(sql).all(...p);
export const run = (db, sql, ...p) => db.prepare(sql).run(...p);

export function tx(db, fn) {
  db.exec('BEGIN');
  try {
    const r = fn();
    db.exec('COMMIT');
    return r;
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
}

export function audit(db, { orgId, userId, action, entity, entityId, detail }) {
  run(db, 'INSERT INTO audit_log (org_id,user_id,action,entity,entity_id,detail) VALUES (?,?,?,?,?,?)',
    orgId ?? null, userId ?? null, action, entity ?? null, entityId ?? null, detail ? JSON.stringify(detail) : null);
}

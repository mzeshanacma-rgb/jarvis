import { one, all, run, audit, tx } from './db.js';
import { checkDraft, similarity, PROMPT_VERSION, AiUnavailable } from './ai.js';
import { readEncrypted } from './security.js';

const month = () => new Date().toISOString().slice(0, 7);

export function enqueueDraft(db, orgId, submissionId) {
  run(db, "INSERT INTO jobs (org_id,type,ref_id) VALUES (?, 'draft', ?)", orgId, submissionId);
}

function tierFor(db, orgId, lang) {
  const org = one(db, 'SELECT lang_tiers FROM orgs WHERE id=?', orgId);
  const tiers = JSON.parse(org?.lang_tiers || '{}');
  const base = String(lang || 'en').split('-')[0];
  return tiers[lang] || tiers[base] || 'interface';
}

// One job = one draft. The queue lives in the database, so a restart loses nothing.
export async function processDraftJob(db, cfg, grader, job) {
  const sub = one(db, 'SELECT * FROM submissions WHERE id=? AND org_id=?', job.ref_id, job.org_id);
  if (!sub) return 'gone';
  if (!['submitted'].includes(sub.status)) return 'skip';
  const task = one(db, 'SELECT * FROM tasks WHERE id=? AND org_id=?', sub.task_id, sub.org_id);
  const cls = one(db, 'SELECT * FROM classes WHERE id=? AND org_id=?', task.class_id, sub.org_id);
  const student = one(db, 'SELECT * FROM users WHERE id=?', sub.student_id);
  const teacher = one(db, 'SELECT * FROM users WHERE id=?', task.teacher_id);
  const org = one(db, 'SELECT * FROM orgs WHERE id=?', sub.org_id);

  const unavailable = (why) => {
    run(db, "UPDATE submissions SET status='ai_unavailable' WHERE id=?", sub.id);
    audit(db, { orgId: sub.org_id, action: 'ai_skipped', entity: 'submission', entityId: sub.id, detail: { why } });
    return why;
  };

  if (!cls.ai_enabled) return unavailable('ai_disabled_for_class');
  if (org.plan === 'trial' && org.trial_ends_at && Date.parse(org.trial_ends_at) < Date.now()) return unavailable('trial_ended');
  const feedbackLang = task.feedback_lang || student.feedback_lang || student.lang || org.default_lang;
  const tier = tierFor(db, sub.org_id, task.answer_lang);
  if (tier === 'interface') return unavailable('language_not_tested');
  const used = one(db, 'SELECT drafts FROM ai_usage WHERE org_id=? AND month=?', sub.org_id, month())?.drafts || 0;
  if (used >= org.ai_monthly_limit) return unavailable('monthly_ai_limit_reached');

  const rubric = JSON.parse(task.rubric);
  const samples = JSON.parse(task.samples || '[]');
  let image = null;
  if (sub.kind === 'photo' || sub.kind === 'file') {
    // Claude only receives the answer part (name area cropped off).
    const f = one(db, "SELECT * FROM files WHERE submission_id=? AND org_id=? AND role='answer'", sub.id, sub.org_id);
    if (f && /^(image\/(jpeg|png|webp|gif)|application\/pdf)$/.test(f.mime)) image = { mime: f.mime, data: readEncrypted(cfg, f).toString('base64') };
    else if (!sub.text) return unavailable('file_type_not_supported');
  }

  let raw;
  try {
    raw = await grader({
      task, rubric, samples, answerText: sub.text || '', image,
      studentCode: student.code, feedbackLang, teacherLang: teacher.lang || org.default_lang,
    });
  } catch (e) {
    if (e instanceof AiUnavailable) return unavailable('model_declined');
    throw e; // will be retried
  }
  const checked = checkDraft(raw, { rubric, answerText: sub.text, kind: sub.kind });
  const flags = checked.flags;

  // Similar answers: compare with other typed answers to the same task
  if (sub.text) {
    const others = all(db, 'SELECT id, text, student_id FROM submissions WHERE task_id=? AND org_id=? AND id<>? AND text IS NOT NULL', sub.task_id, sub.org_id, sub.id);
    for (const o of others) {
      const sim = similarity(sub.text, o.text);
      if (sim >= 0.6) {
        const code = one(db, 'SELECT code FROM users WHERE id=?', o.student_id)?.code;
        flags.push({ type: 'similar_answer', note: `Very similar (${Math.round(sim * 100)}%) to the answer from ${code}.` });
      }
    }
  }
  const log = JSON.parse(sub.writing_log || 'null');
  if (log && log.largestPaste >= 200) flags.push({ type: 'large_paste', note: `${log.largestPaste} characters were pasted at once.` });

  tx(db, () => {
    run(db, `INSERT INTO ai_drafts (org_id,submission_id,model,prompt_version,tier,scores,total,feedback_student,feedback_teacher,transcription,flags,oral_questions,input_tokens,output_tokens)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      sub.org_id, sub.id, raw.model, PROMPT_VERSION, tier, JSON.stringify(checked.scores), checked.total,
      raw.feedbackStudent, raw.feedbackTeacher, raw.transcription || '', JSON.stringify(flags), JSON.stringify(raw.oralQuestions || []),
      raw.usage?.input ?? 0, raw.usage?.output ?? 0);
    run(db, "UPDATE submissions SET status='draft_ready' WHERE id=? AND status='submitted'", sub.id);
    run(db, `INSERT INTO ai_usage (org_id,month,drafts,input_tokens,output_tokens) VALUES (?,?,1,?,?)
      ON CONFLICT(org_id,month) DO UPDATE SET drafts=drafts+1, input_tokens=input_tokens+excluded.input_tokens, output_tokens=output_tokens+excluded.output_tokens`,
      sub.org_id, month(), raw.usage?.input ?? 0, raw.usage?.output ?? 0);
    audit(db, { orgId: sub.org_id, action: 'ai_draft_created', entity: 'submission', entityId: sub.id, detail: { model: raw.model, prompt: PROMPT_VERSION, tier, flags: flags.map((f) => f.type) } });
  });
  return 'done';
}

export async function runPendingJobs(db, cfg, grader, { limit = 5 } = {}) {
  const now = Date.now();
  const jobs = all(db, "SELECT * FROM jobs WHERE status='queued' AND run_after<=? ORDER BY id LIMIT ?", now, limit);
  for (const job of jobs) {
    const claimed = run(db, "UPDATE jobs SET status='running', attempts=attempts+1 WHERE id=? AND status='queued'", job.id);
    if (!claimed.changes) continue;
    try {
      await processDraftJob(db, cfg, grader, job);
      run(db, "UPDATE jobs SET status='done' WHERE id=?", job.id);
    } catch (e) {
      const attempts = job.attempts + 1;
      if (attempts >= 5) {
        run(db, "UPDATE jobs SET status='failed', last_error=? WHERE id=?", String(e.message).slice(0, 500), job.id);
        run(db, "UPDATE submissions SET status='ai_unavailable' WHERE id=? AND status='submitted'", job.ref_id);
      } else {
        run(db, "UPDATE jobs SET status='queued', run_after=?, last_error=? WHERE id=?", Date.now() + 2 ** attempts * 15_000, String(e.message).slice(0, 500), job.id);
      }
    }
  }
  return jobs.length;
}

export function startWorker(db, cfg, grader) {
  // Jobs left "running" when the server stopped go back into the queue.
  run(db, "UPDATE jobs SET status='queued' WHERE status='running'");
  let busy = false;
  const t = setInterval(async () => {
    if (busy) return;
    busy = true;
    try { await runPendingJobs(db, cfg, grader); } catch (e) { console.error('[worker]', e); }
    busy = false;
  }, 1500);
  t.unref();
  return () => clearInterval(t);
}

// Quality numbers (Gates 1 and 2): AI total vs teacher's final total on approved answers.
export function qualityReport(db, orgId, { taskId, lang } = {}) {
  const params = [orgId];
  let where = "s.org_id=? AND s.status='approved' AND d.total IS NOT NULL";
  if (taskId) { where += ' AND s.task_id=?'; params.push(taskId); }
  if (lang) { where += ' AND t.answer_lang=?'; params.push(lang); }
  const rows = all(db, `SELECT s.id, s.final_total, s.final_scores, s.review_ms, s.blind, s.kind, t.rubric, d.total ai_total, d.scores ai_scores, d.flags
     FROM submissions s JOIN tasks t ON t.id=s.task_id
     JOIN ai_drafts d ON d.id=(SELECT MAX(id) FROM ai_drafts WHERE submission_id=s.id) WHERE ${where}`, ...params);
  const n = rows.length;
  let within10 = 0, over20 = 0, edited = 0, bigDiff = 0, bigDiffFlagged = 0;
  const msAi = [], msBlind = [];
  for (const r of rows) {
    const max = JSON.parse(r.rubric).reduce((a, c) => a + c.max, 0) || 1;
    const diff = Math.abs(r.final_total - r.ai_total) / max;
    if (diff <= 0.10) within10++;
    if (diff > 0.20) { over20++; bigDiff++; if (JSON.parse(r.flags).length) bigDiffFlagged++; }
    if (r.final_scores !== r.ai_scores && JSON.stringify(JSON.parse(r.final_scores || '[]').map((x) => x.score)) !== JSON.stringify(JSON.parse(r.ai_scores).map((x) => x.score))) edited++;
    if (r.review_ms) (r.blind ? msBlind : msAi).push(r.review_ms);
  }
  const avg = (a) => (a.length ? a.reduce((x, y) => x + y, 0) / a.length : null);
  const pct = (x) => (n ? Math.round((x / n) * 1000) / 10 : null);
  const timeSaved = avg(msBlind) && avg(msAi) ? Math.round((1 - avg(msAi) / avg(msBlind)) * 1000) / 10 : null;
  const report = {
    answers: n,
    within10Pct: pct(within10),
    over20Pct: pct(over20),
    editedPct: pct(edited),
    timeSavedPct: timeSaved,
    flagHealthPct: bigDiff ? Math.round((bigDiffFlagged / bigDiff) * 1000) / 10 : null,
    blindSamples: msBlind.length,
  };
  // Thresholds proposed in the plan (Gate 1)
  report.gate1 = {
    within10: report.within10Pct !== null && report.within10Pct >= 90,
    over20: report.over20Pct !== null && report.over20Pct < 3,
    flagHealth: report.flagHealthPct === null || report.flagHealthPct >= 70,
    enoughAnswers: n >= 100,
  };
  report.gate1.pass = Object.values(report.gate1).every(Boolean);
  return report;
}

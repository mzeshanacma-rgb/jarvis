// Every interface string exists in every language.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const src = fs.readFileSync(new URL('../public/i18n.js', import.meta.url), 'utf8');
const sandbox = { window: {} };
vm.runInNewContext(src, sandbox);
const { en, ur } = sandbox.window.I18N;
const app = fs.readFileSync(new URL('../public/app.js', import.meta.url), 'utf8');

const dynamic = {
  status: ['todo', 'submitted', 'draft_ready', 'ai_unavailable', 'approved', 'returned', 'queued'],
  flag: ['unclear_handwriting', 'off_rubric', 'language_doubt', 'instructions_in_answer', 'blank_or_unreadable', 'diagram_or_math', 'score_out_of_range', 'low_confidence', 'similar_answer', 'large_paste'],
  submit: ['typed', 'photo', 'file'], lang: ['en', 'ur', 'ur-roman'], mode: ['any', 'typed', 'photo', 'file'], level: ['high', 'medium', 'low'],
  conf: ['high', 'medium', 'low'], tier: ['full', 'beta', 'interface'], role: ['student', 'teacher', 'admin'], age: ['under13', 'under18', 'adult'],
  system: ['backup', 'restore_test', 'retention'], 'system.reason': ['retention', 'school_request'],
};

test('every key used in app.js exists in every language', () => {
  const used = new Set([...app.matchAll(/(?<![\w.])t\('([a-zA-Z0-9_.-]+)'[,)]/g)].map((m) => m[1]));
  for (const [prefix, list] of Object.entries(dynamic)) for (const k of list) used.add(`${prefix}.${k}`);
  const missing = [...used].filter((k) => !(k in en) || !(k in ur));
  assert.deepEqual(missing, []);
});

test('every server error code has a message', () => {
  const server = fs.readFileSync(new URL('../src/app.js', import.meta.url), 'utf8');
  const codes = new Set([...server.matchAll(/(?:bad|forbidden|HttpError\(\d+,)\s*\(?'([a-z_]+)'/g)].map((m) => m[1]));
  const missing = [...codes].filter((c) => !(`err.${c}` in en) || !(`err.${c}` in ur));
  assert.deepEqual(missing, []);
});

test('placeholders match across languages', () => {
  for (const k of Object.keys(en)) {
    const a = (en[k].match(/\{\w+\}/g) || []).sort().join();
    const b = (ur[k].match(/\{\w+\}/g) || []).sort().join();
    assert.equal(a, b, k);
  }
});

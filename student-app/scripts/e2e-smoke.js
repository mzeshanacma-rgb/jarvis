// Full loop in a browser: setup -> teacher task -> student submits -> AI draft -> teacher approves -> student result.
// Run: node scripts/e2e-smoke.js [screenshots-folder]   (needs Playwright and Chromium)
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { totpAt } from '../src/security.js';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const shots = path.resolve(process.argv[2] || 'screenshots');
fs.mkdirSync(shots, { recursive: true });
const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'saa-e2e-'));
const PORT = 3911;
const BASE = `http://127.0.0.1:${PORT}`;

const server = spawn(process.execPath, ['--no-warnings', 'src/server.js'], { env: { ...process.env, DATA_DIR: dataDir, PORT: String(PORT), PUBLIC_URL: BASE, AI_MODE: 'demo', ANTHROPIC_API_KEY: '', BLIND_SAMPLE_RATE: '0' }, stdio: ['ignore', 'pipe', 'inherit'] });
await new Promise((r) => server.stdout.on('data', (d) => { if (String(d).includes('port')) r(); }));

const browser = await chromium.launch();
const errors = [];
async function page(opts = {}) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, ...opts });
  const p = await ctx.newPage();
  p.on('console', (m) => { if (m.type() === 'error' && !/status of 40[01]/.test(m.text())) errors.push(m.text()); });
  p.on('pageerror', (e) => errors.push(String(e)));
  p.on('dialog', (d) => d.accept());
  p.on('response', (r) => { if (r.status() >= 400 && r.status() !== 401) console.log('  [http]', r.status(), r.request().method(), r.url()); });
  return p;
}
const shot = (p, name) => p.screenshot({ path: path.join(shots, name + '.png'), fullPage: true });
const step = (s) => console.log('•', s);

try {
  // 1. Setup
  const a = await page();
  await a.goto(BASE);
  await a.getByRole('heading', { name: 'Set up your school' }).waitFor();
  await a.fill('#s1', 'Govt. Girls High School');
  await a.selectOption('#s2', 'en');
  await a.fill('#s3', 'Principal');
  await a.fill('#s5', 'principal');
  await a.fill('#s6', 'Principal2026x');
  await a.click('button[type=submit]');
  await a.locator('img.qr').waitFor();
  await shot(a, '01-admin-2fa');
  const secret = await a.locator('.code').innerText();
  await a.fill('#c', totpAt(secret.trim()));
  await a.click('button[type=submit]');
  await a.locator('h1').first().waitFor();
  step('admin set up with 2FA');

  // Admin creates users
  await a.goto(BASE + '/#/admin/users');
  await a.locator('#csv').waitFor();
  await a.fill('#csv', 'role,name,username,email,age_band,class_code\nteacher,Ms Farah,farah,,adult,\nstudent,Ayesha Khan,ayesha,,under18,\nstudent,Bilal Raza,bilal,,under18,');
  await a.getByRole('button', { name: 'Import' }).click();
  await a.locator('.notice.ok table').waitFor();
  const creds = Object.fromEntries(await a.$$eval('.notice.ok tbody tr', (rows) => rows.map((r) => [r.children[0].innerText, r.children[1].innerText])));
  await shot(a, '02-admin-csv');
  step('CSV import: ' + Object.keys(creds).join(', '));

  await a.goto(BASE + '/#/admin/classes');
  await a.fill('#c1', 'Class 8 – Urdu');
  await a.click('form button[type=submit]');
  await a.locator('details summary').first().waitFor();
  await a.locator('details summary').first().click();
  const sel = a.locator('details select');
  await sel.selectOption([{ label: 'Ayesha Khan (ayesha)' }, { label: 'Bilal Raza (bilal)' }]);
  await a.getByRole('button', { name: 'Add selected' }).click();
  await a.waitForTimeout(500);
  await shot(a, '03-admin-classes');
  step('class created and students enrolled');

  // 2. Teacher: change password, 2FA, create task
  const tp = await page({ viewport: { width: 1280, height: 900 } });
  await tp.goto(BASE);
  await tp.fill('#u', 'farah');
  await tp.fill('#p', creds.farah);
  await tp.click('button[type=submit]');
  await tp.locator('img.qr').waitFor();
  const tsecret = (await tp.locator('.code').innerText()).trim();
  await tp.fill('#c', totpAt(tsecret));
  await tp.click('button[type=submit]');
  await tp.locator('#p0').waitFor();
  await tp.fill('#p0', creds.farah);
  await tp.fill('#p1', 'FarahPass2026');
  await tp.fill('#p2', 'FarahPass2026');
  await tp.click('button[type=submit]');
  await tp.getByRole('link', { name: /Open|کھولیں/ }).first().click();
  await tp.getByRole('link', { name: /New task|نیا کام/ }).click();
  await tp.fill('#t1', 'Mazmoon: Mera Shehar');
  await tp.fill('#t2', 'Apne shehar par 150 alfaaz ka mazmoon likhein.');
  await tp.selectOption('#t3', 'en');
  const crit = tp.locator('.criterion');
  await crit.nth(0).locator('[data-k=name]').fill('Content');
  await crit.nth(0).locator('[data-k=max]').fill('6');
  await crit.nth(1).locator('[data-k=name]').fill('Language');
  await crit.nth(1).locator('[data-k=max]').fill('4');
  await crit.nth(2).locator('[data-k=text]').fill('Karachi is a large port city with busy markets...');
  await shot(tp, '04-teacher-new-task');
  await tp.locator('form button[type=submit]').click();
  await tp.locator('.stats').waitFor();
  const taskUrl = tp.url();
  step('teacher created task ' + taskUrl);

  // 3. Student: typed answer (phone)
  const sp = await page();
  await sp.goto(BASE);
  await sp.fill('#u', 'ayesha');
  await sp.fill('#p', creds.ayesha);
  await sp.click('button[type=submit]');
  await sp.locator('#p0').waitFor();
  await sp.fill('#p0', creds.ayesha);
  await sp.fill('#p1', 'Ayesha2026pass');
  await sp.fill('#p2', 'Ayesha2026pass');
  await sp.click('button[type=submit]');
  await sp.locator('.list a.item').first().click();
  await sp.locator('#ans').fill(Array.from({ length: 90 }, (_, i) => ['my', 'city', 'Lahore', 'has', 'old', 'gardens', 'and', 'food', 'streets'][i % 9]).join(' '));
  await shot(sp, '05-student-submit');
  await sp.locator('form button[type=submit]').click();
  await sp.locator('.notice.ok').waitFor();
  await shot(sp, '06-student-received');
  step('student submitted (typed)');

  // Bilal: photo of copy
  const bp = await page();
  await bp.goto(BASE);
  await bp.fill('#u', 'bilal');
  await bp.fill('#p', creds.bilal);
  await bp.click('button[type=submit]');
  await bp.locator('#p0').waitFor();
  await bp.fill('#p0', creds.bilal);
  await bp.fill('#p1', 'Bilal2026pass');
  await bp.fill('#p2', 'Bilal2026pass');
  await bp.click('button[type=submit]');
  await bp.locator('.list a.item').first().click();
  await bp.getByRole('tab').nth(1).click();
  // fake photo of a copy, drawn on a canvas in the browser
  const jpg = await bp.evaluate(async () => {
    const c = document.createElement('canvas'); c.width = 1200; c.height = 1600;
    const x = c.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, 1200, 1600); x.fillStyle = '#123';
    x.font = '40px serif'; x.fillText('Name: Bilal Raza   Roll 12', 60, 90);
    for (let i = 0; i < 28; i++) x.fillText('My city has many old buildings and gardens ' + i, 60, 260 + i * 46);
    const b = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.9));
    return Array.from(new Uint8Array(await b.arrayBuffer()));
  });
  await bp.setInputFiles('#ph', { name: 'copy.jpg', mimeType: 'image/jpeg', buffer: Buffer.from(jpg) });
  await bp.locator('.photo-wrap canvas').waitFor();
  await shot(bp, '07-student-photo-crop');
  await bp.locator('form button[type=submit]').click();
  await bp.locator('.notice.ok').waitFor();
  step('student submitted (photo with name area hidden from AI)');

  // 4. Teacher review: own estimate, then draft, then approval
  await tp.waitForTimeout(3500); // the worker creates the draft
  await tp.reload();
  await tp.locator('.list a.item').first().waitFor();
  await shot(tp, '08-teacher-queue');
  await tp.locator('.list a.item', { hasText: 'Ayesha' }).click();
  await tp.locator('#est').fill('8');
  await shot(tp, '09-teacher-score-first');
  await tp.locator('form button[type=submit]').click();
  await tp.locator('#fb').waitFor();
  const crits = tp.locator('.criterion input[type=number]');
  await crits.nth(0).fill('5');
  await crits.nth(1).fill('3');
  await shot(tp, '10-teacher-review-draft');
  await tp.locator('form button[type=submit]').click();
  await tp.locator('.stats').waitFor();
  step('teacher approved Ayesha');

  await tp.locator('.list a.item', { hasText: 'Bilal' }).click();
  await tp.locator('#est').fill('6');
  await tp.locator('form button[type=submit]').click();
  await tp.locator('#fb').waitFor();
  await crits.nth(0).fill('4');
  await crits.nth(1).fill('2');
  await tp.locator('form button[type=submit]').click();
  await tp.locator('.notice.bad').waitFor();
  const msg = await tp.locator('.notice.bad').innerText();
  if (!/checked/i.test(msg)) throw new Error('expected check-every-answer error, got ' + msg);
  for (const cb of await tp.locator('.criterion input[type=checkbox]').all()) await cb.check();
  await tp.waitForTimeout(300);
  await shot(tp, '11-teacher-handwritten-checked');
  await tp.locator('form button[type=submit]').click();
  await tp.locator('.stats').waitFor();
  await shot(tp, '12-teacher-dashboard');
  step('teacher approved Bilal after checking every criterion');

  // 5. Student result
  await sp.goto(BASE + '/#/');
  await sp.locator('.list a.item').first().click();
  await sp.locator('.badge.ok').first().waitFor();
  await shot(sp, '13-student-result');
  const text = await sp.locator('main').innerText();
  if (!text.includes('8 / 10')) throw new Error('student result not shown: ' + text.slice(0, 300));
  step('student sees released result 8/10 with AI notice');

  // Admin quality and audit
  await a.goto(BASE + '/#/admin/quality');
  await a.locator('table').first().waitFor();
  await shot(a, '14-admin-quality');
  await a.goto(BASE + '/#/admin/audit');
  await a.locator('table').first().waitFor();
  await shot(a, '15-admin-audit');

  if (errors.length) throw new Error('browser errors:\n' + errors.join('\n'));
  console.log('E2E OK. Screenshots:', shots);
} catch (e) {
  console.error('E2E FAILED:', e.message);
  for (const [i, ctx] of browser.contexts().entries()) for (const pg of ctx.pages()) await pg.screenshot({ path: path.join(shots, `fail-${i}.png`), fullPage: true }).catch(() => {});
  if (errors.length) console.error(errors.join('\n'));
  process.exitCode = 1;
} finally {
  await browser.close();
  server.kill();
  fs.rmSync(dataDir, { recursive: true, force: true });
}

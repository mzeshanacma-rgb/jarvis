import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// All settings come from environment variables. Defaults suit local development.
export function loadConfig(overrides = {}) {
  const dataDir = path.resolve(overrides.dataDir || process.env.DATA_DIR || './data');
  fs.mkdirSync(dataDir, { recursive: true });

  // Key for encrypting uploaded files. In production set FILE_KEY (64 hex chars).
  let fileKey = overrides.fileKey || process.env.FILE_KEY;
  if (!fileKey) {
    const keyPath = path.join(dataDir, 'file.key');
    if (!fs.existsSync(keyPath)) fs.writeFileSync(keyPath, crypto.randomBytes(32).toString('hex'), { mode: 0o600 });
    fileKey = fs.readFileSync(keyPath, 'utf8').trim();
  }
  let linkSecret = overrides.linkSecret || process.env.LINK_SECRET;
  if (!linkSecret) {
    const p = path.join(dataDir, 'link.secret');
    if (!fs.existsSync(p)) fs.writeFileSync(p, crypto.randomBytes(32).toString('hex'), { mode: 0o600 });
    linkSecret = fs.readFileSync(p, 'utf8').trim();
  }

  const bool = (v, d) => (v === undefined ? d : ['1', 'true', 'yes'].includes(String(v).toLowerCase()));

  return {
    port: Number(overrides.port ?? process.env.PORT ?? 3000),
    dataDir,
    dbPath: overrides.dbPath || path.join(dataDir, 'app.db'),
    uploadDir: path.join(dataDir, 'uploads'),
    backupDir: path.join(dataDir, 'backups'),
    fileKey: Buffer.from(fileKey, 'hex'),
    linkSecret,
    publicUrl: overrides.publicUrl || process.env.PUBLIC_URL || 'http://localhost:3000',
    // true when running behind an HTTPS proxy (Caddy/nginx): secure cookies and HSTS.
    behindHttps: bool(overrides.behindHttps ?? process.env.BEHIND_HTTPS, false),
    require2fa: bool(overrides.require2fa ?? process.env.REQUIRE_2FA, true),
    anthropicKey: overrides.anthropicKey ?? process.env.ANTHROPIC_API_KEY ?? '',
    aiModel: overrides.aiModel || process.env.AI_MODEL || 'claude-opus-5-5',
    aiFallbackModel: process.env.AI_FALLBACK_MODEL || 'claude-opus-4-8',
    aiEffort: process.env.AI_EFFORT || 'medium',
    // Demo AI only when no key is set. Tests choose it explicitly.
    aiMode: overrides.aiMode || process.env.AI_MODE || ((overrides.anthropicKey ?? process.env.ANTHROPIC_API_KEY) ? 'claude' : 'demo'),
    smtp: process.env.SMTP_URL || '',
    mailFrom: process.env.MAIL_FROM || 'no-reply@localhost',
    backupKeep: Number(process.env.BACKUP_KEEP || 14),
    startWorkers: overrides.startWorkers ?? true,
    // Optional link to the Android APK, shown on the install card for Android browsers.
    androidApkUrl: process.env.ANDROID_APK_URL || '',
    blindSampleRate: Number(overrides.blindSampleRate ?? process.env.BLIND_SAMPLE_RATE ?? 0.1),
  };
}

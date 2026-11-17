import { loadConfig } from '../src/config.js';
import { openDb } from '../src/db.js';
import { backupNow } from '../src/maintenance.js';

const cfg = loadConfig();
const db = openDb(cfg.dbPath);
console.log('Backup:', backupNow(db, cfg));
db.close();

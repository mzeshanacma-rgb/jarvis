import { loadConfig } from '../src/config.js';
import { openDb } from '../src/db.js';
import { restoreTest } from '../src/maintenance.js';

const cfg = loadConfig();
const db = openDb(cfg.dbPath);
const r = restoreTest(db, cfg);
console.log(JSON.stringify(r, null, 2));
db.close();
process.exit(r.ok ? 0 : 1);

import { loadConfig } from './config.js';
import { createApp } from './app.js';

const cfg = loadConfig();
const app = createApp(cfg);
const server = app.listen(cfg.port, () => {
  console.log(`Student Assignment App: ${cfg.publicUrl} (port ${cfg.port})`);
  console.log(`AI: ${cfg.aiMode === 'demo' ? 'DEMO (ANTHROPIC_API_KEY not set)' : cfg.aiModel}`);
  if (!cfg.behindHttps) console.log('Note: set BEHIND_HTTPS=true only when the app runs behind an HTTPS proxy (docs/DEPLOY.md).');
});

const shutdown = () => { server.close(() => { app.locals.close(); process.exit(0); }); };
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

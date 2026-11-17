# Incident plan (data leak or security problem)

This is an outline. Before the pilot, fill in names, phone numbers and your lawyer's advice, and share it with each school.

## Who

| Role | Person | Contact |
|---|---|---|
| Incident lead (decisions) | Muhammad Zeshan | ____ |
| Technical (server, logs) | ____ | ____ |
| School contact | Each school's admin | On the admin page |
| Legal advice | ____ (lawyer) | ____ |

## When

| When | What |
|---|---|
| As soon as it is noticed | Tell the lead. Delete nothing; copy the logs and `DATA_DIR`. |
| Within 1 hour | Contain it: deactivate affected accounts (Admin → Users → Deactivate), rotate passwords and the API key, take the server offline if needed. |
| Within 24 hours | Tell the affected school admins in writing: what happened, whose data, what was done. |
| Within 72 hours | Where the law requires it, report to the regulator, on legal advice (72 hours under EU/UK GDPR; other countries per their law). Parents are informed through the school. |
| Within 2 weeks | Written review: cause, what changed, what prevents a repeat. |

## Where to look

- Admin → Audit log: who viewed what (`submission_viewed`, `file_viewed`), failed sign-ins (`login_failed`).
- The `system_events` table: backups, restore tests, retention.
- Caddy and systemd logs: `journalctl -u saa`, `journalctl -u caddy`.

## First steps

1. Revoke `ANTHROPIC_API_KEY` in the Claude Console and set a new one.
2. End every session: `sqlite3 data/app.db "DELETE FROM sessions;"`.
3. If `FILE_KEY` may have leaked: new server, new key, and re-encrypt the old files with it (script to be written in Phase 2).

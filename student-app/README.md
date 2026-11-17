# Assignment Review

Teachers set a task, students hand in typed work, a file or a photo of their copy, Claude drafts rubric-based feedback, and nothing reaches the student until the teacher approves it.

This is the Phase 1 (pilot) build of the Student Assignment App plan (`docs/Student-Assignment-App-Plan-v2.1.pdf`).

## One app, every device

| Device | How people use it |
|---|---|
| Windows, Mac, Linux laptops | Any modern browser. Chrome and Edge can install it as an app (install icon in the address bar). |
| Mac (Safari) | File → Add to Dock. |
| iPhone and iPad | Safari → Share → Add to Home Screen. Opens full screen with its own icon. |
| Android | The Android app (APK, in `android/`) or Chrome → Install app. |

It is one web app (a Progressive Web App) served by one server, so every device always runs the same version. The sign-in page shows the right install steps for each device. The app shell works offline, and a student's answer waits on the device until the internet is back.

A native App Store app for iPhone needs a Mac with Xcode and an Apple Developer account; see "iPhone App Store" below.

## Run it (5 minutes)

Requires Node.js 22.13 or newer.

```bash
npm install
npm start            # http://localhost:3000
```

The first visit shows "Set up your school": school name, default language (English unless you choose another) and the first admin. Admins and teachers set up two-step sign-in (Google or Microsoft Authenticator) on first sign-in.

Without an API key the app runs in **demo mode**: drafts are marked `[DEMO]` and scores are based only on length. For real Claude drafts:

```bash
ANTHROPIC_API_KEY=sk-ant-... npm start
```

To put it online for real schools (HTTPS, backups): [docs/DEPLOY.md](docs/DEPLOY.md). Phones, tablets and other laptops can only use the app once it runs on a server they can reach.

## Who does what

| Role | Can |
|---|---|
| Admin | Users (one by one or CSV), classes, language quality tiers, retention period, monthly AI limit, audit log, quality report (Gate 1), backups and restore test, delete a student's data with a proof record |
| Teacher | Tasks (rubric, sample answers, language, "handwritten only"), review queue (flags and re-checks first), own estimate before the AI draft, per-criterion scores, edit feedback, approve = release, return, turn AI off for a class |
| Student | Join a class with a code (existing accounts only), hand in typed work, photo or file, offline queue, see released results, ask for a re-check |

## Languages

English is the default. Each person can switch language in Settings, and admins choose the default for new users. Urdu (right-to-left, Nastaliq font bundled with the app) is included. Adding a language means adding one object to `public/i18n.js`; a test checks that every string exists in every language.

AI drafts per answer language follow quality tiers: **Full** (score and feedback), **Beta** (feedback only, teacher scores) or **Interface only** (no drafts). Move a language to Full only after it passes Gate 1 on the Quality page.

## Where the plan's decisions live in the code

| Plan | Code |
|---|---|
| Claude drafts; score and release are always the teacher's | Only `POST /api/teacher/submissions/:id/approve` releases a result (`src/app.js`) |
| "Score first" on by default | `users.score_first`; the API withholds the draft until the teacher's estimate |
| 10% of answers without an AI draft (blind sample) | `BLIND_SAMPLE_RATE`; time comparison in the quality report |
| No names to Claude | Claude gets a student code (`S-XXXXX`); the top of the photo is cropped on the device before upload, the teacher still sees the whole page |
| Teacher checks every handwritten answer | Approval of a photo answer is refused until every criterion is ticked "checked" |
| `[unclear]`, low confidence, prompt injection, similar answers, large paste | Flags (`src/ai.js` `checkDraft`, `src/review.js`) |
| Out-of-range scores | Rejected in code (`score_out_of_range`) |
| Prompt version in the audit | `ai_drafts.prompt_version` and the audit log |
| Tenant isolation | `org_id` on every query; `test/isolation.test.js` runs before every release |
| Two-step sign-in, strong passwords, login throttle | `src/security.js` (throttle stored in the database) |
| Encrypted photos (AES-256-GCM), 5-minute signed links | `src/security.js` |
| AI queue survives restarts, with retries | `jobs` table, `src/review.js` |
| Daily backup, monthly restore test | `src/maintenance.js`, `npm run backup`, `npm run restore-test` |
| Retention and deletion proof | `orgs.retention_days`, `deletions` table |
| Password reset by email | With `SMTP_URL`; otherwise the admin's one-hour reset link |

## Android app

`android/` is a small native app (Kotlin) around the web app. On first launch it asks for the school server address and checks it, then adds camera and gallery picking, an offline screen, the back button and HTTPS-only connections (debug builds also allow http for local testing). See [android/README.md](android/README.md).

GitHub Actions (`.github/workflows/student-app.yml`) runs the web tests, builds the APK, installs it on an Android emulator against a real server, signs in, and publishes the APK and screenshots as the `student-app-latest` release.

## iPhone App Store

The installable web app covers iPhone and iPad today. A native App Store app needs a Mac with Xcode, an Apple Developer account (99 USD a year) and Apple's review. Apple also expects an app to offer more than a wrapped website, so plan this for Phase 3 when you need App Store presence or push notifications.

## Tests

```bash
npm test                          # 47 tests: auth, flow, tenant isolation, AI checks, operations, i18n
node scripts/e2e-smoke.js shots   # the whole loop in a real browser (needs Playwright and Chromium)
```

## Settings (environment variables)

| Variable | Meaning | Default |
|---|---|---|
| `PORT` | Port | 3000 |
| `DATA_DIR` | Database, encrypted files, backups | `./data` |
| `PUBLIC_URL` | Used in reset links | `http://localhost:3000` |
| `BEHIND_HTTPS` | Behind an HTTPS proxy: secure cookies and HSTS | false |
| `ANTHROPIC_API_KEY` | Claude API key; without it, demo mode | – |
| `AI_MODEL` | Model for drafts | `claude-opus-5-5` |
| `AI_FALLBACK_MODEL` | Second try if the model declines | `claude-opus-4-8` |
| `AI_EFFORT` | low / medium / high | `medium` |
| `FILE_KEY` | File encryption key (64 hex). Without it `DATA_DIR/file.key` is created | – |
| `SMTP_URL`, `MAIL_FROM` | Password reset email | – |
| `REQUIRE_2FA` | Two-step sign-in for teachers and admins | true |
| `BLIND_SAMPLE_RATE` | Share of answers without a visible draft | 0.1 |
| `BACKUP_KEEP` | Daily backups to keep | 14 |
| `ANDROID_APK_URL` | Link to the Android APK, shown on Android browsers | – |

If `DATA_DIR/file.key` is lost, photos cannot be decrypted. Keep it somewhere safe, separate from the backups.

## Still to do (Phase 0 onward)

- Run 100 real copies through the real Claude API (Gate 1). The Quality page shows these numbers.
- Legal review in each market, Anthropic's requirements for organisations serving minors, data agreement with each school ([docs/PRIVACY-DRAFT.md](docs/PRIVACY-DRAFT.md) is only an outline).
- SQLite is enough for one server and a pilot of a few schools; move to PostgreSQL in Phase 3.
- Phase 2: papers, projects, class link, student pre-check, penetration test.

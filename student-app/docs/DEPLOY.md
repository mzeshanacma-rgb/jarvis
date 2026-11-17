# Running it online (with HTTPS)

The app does not terminate HTTPS itself. Put a reverse proxy in front of it. The steps below use Caddy, which obtains certificates automatically.

## 1. Server

- A small Ubuntu 22.04/24.04 VPS (1 vCPU, 1–2 GB RAM is enough for a pilot).
- A domain, for example `app.yourschool.org`, with an A record pointing to the server.
- Choose the server's country or region to match where you promise schools their data is stored. Claude API processing runs in the "global" or "us" region; say so in the school agreement as well.

## 2. App

```bash
sudo apt-get install -y nodejs   # Node 22.13+ (NodeSource or nvm)
sudo useradd -r -m -d /opt/saa saa
sudo -u saa git clone <repo> /opt/saa/app   # or copy the zip
cd /opt/saa/app && sudo -u saa npm ci --omit=dev
sudo -u saa mkdir -p /opt/saa/data
openssl rand -hex 32   # for FILE_KEY; also store it somewhere safe and separate
```

`/etc/saa.env` (readable only by root and saa: `chmod 640`):

```
PORT=3000
DATA_DIR=/opt/saa/data
PUBLIC_URL=https://app.yourschool.org
BEHIND_HTTPS=true
ANTHROPIC_API_KEY=sk-ant-...
FILE_KEY=<64 hex>
SMTP_URL=smtps://user:pass@smtp.example.com:465
MAIL_FROM=no-reply@yourschool.org
ANDROID_APK_URL=https://github.com/<owner>/<repo>/releases/download/student-app-latest/assignment-review-debug.apk
```

`/etc/systemd/system/saa.service`:

```
[Unit]
Description=Assignment Review
After=network.target

[Service]
User=saa
WorkingDirectory=/opt/saa/app
EnvironmentFile=/etc/saa.env
ExecStart=/usr/bin/node --no-warnings src/server.js
Restart=always
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/saa/data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now saa
```

## 3. HTTPS (Caddy)

```bash
sudo apt-get install -y caddy
```

`/etc/caddy/Caddyfile`:

```
app.yourschool.org {
    encode gzip
    request_body {
        max_size 20MB
    }
    reverse_proxy 127.0.0.1:3000
}
```

```bash
sudo systemctl reload caddy
```

Keep port 3000 closed to the outside (`ufw allow 80,443/tcp`, not 3000).

HTTPS is required for installing the app on iPhone, Mac, Windows and Android, for the offline cache, and for the Android release app.

## 4. Backups

The app makes a daily backup in `DATA_DIR/backups` and runs a restore test every month (Admin → System). If the server dies, those backups die with it, so copy them elsewhere every day, for example:

```
# /etc/cron.d/saa-offsite
30 2 * * * saa rsync -a --delete /opt/saa/data/backups/ backup@other-host:/srv/saa-backups/
```

Keep `FILE_KEY` apart from the backups.

## 5. Updates

```bash
cd /opt/saa/app && sudo -u saa git pull && sudo -u saa npm ci --omit=dev && npm test && sudo systemctl restart saa
```

`npm test` includes the tenant isolation test; do not release if it fails.

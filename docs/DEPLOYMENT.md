# Deploying the NanoTune server on a VPS

The server signs ShazamKit developer tokens, can route clips to recognition engines you attach (AudD,
ACRCloud), and optionally hosts accounts and sync. It is a single small container (FastAPI + SQLite).

## Requirements

* A Linux VPS with Docker Engine and the Compose plugin (v2.24+).
* A domain or subdomain pointing at the VPS, e.g. `api.example.com`, and HTTPS (nginx/Caddy/Traefik).
* For recognition, at least one of:
  * an Apple Developer Program membership and a Media Services key, for ShazamKit (needs an APK built with
    Apple's AAR), or
  * an AudD API token and/or an ACRCloud project (works with the APK from the Releases page).

  See docs/RECOGNITION_PROVIDERS.md. Without either, the server still runs (accounts/sync work) and the
  app explains what isn't configured.

## 1. Get the code and configure

```bash
sudo mkdir -p /srv/projects && cd /srv/projects
sudo git clone https://github.com/domdzd/shabam.git nanotune && cd nanotune
cp .env.example .env
python3 -c "import secrets; print(secrets.token_urlsafe(48))"   # paste as AUTH_JWT_SECRET
nano .env   # APPLE_TEAM_ID, APPLE_KEY_ID, AUTH_JWT_SECRET, APP_BASE_URL, SERVER_NAME
chmod 600 .env
```

## 2. Install the Apple private key (server only)

Copy the `.p8` file you downloaded from Apple to the server with `scp`, then:

```bash
cd /srv/projects/nanotune/deploy
mkdir -p secrets
sudo install -m 400 -o 10001 -g 10001 ~/AuthKey_ABC123DEFG.p8 secrets/apple_media_services.p8
rm ~/AuthKey_ABC123DEFG.p8
```

The container runs as uid 10001 and mounts `deploy/secrets` read-only at `/run/secrets`. `deploy/secrets/`
is git-ignored. Never put the key in the APK, in Git, or in chat.

## 3. Start

```bash
cd /srv/projects/nanotune/deploy
docker compose up -d --build
docker compose logs -f nanotune-api        # migrations run automatically on start
curl -s http://127.0.0.1:8000/api/v1/health
curl -s http://127.0.0.1:8000/api/v1/config  # "shazamkit": {"available": true} once the key is in place
```

The API listens on `127.0.0.1:8000` only.

## 4. HTTPS reverse proxy

Pick one:

* **nginx**: copy `deploy/nginx.conf.example` to `/etc/nginx/sites-available/nanotune`, replace the
  domain, get a certificate (`sudo certbot --nginx -d api.example.com`), enable the site, `sudo nginx -s reload`.
* **Caddy**: use `deploy/Caddyfile.example` (automatic certificates).
* **Traefik** (existing, on an external Docker network named `proxy`):
  `docker compose -f docker-compose.yml -f docker-compose.traefik.yml up -d --build` after editing the host
  rule and certresolver.

The proxy must forward `X-Forwarded-For`; the container trusts it (`FORWARDED_ALLOW_IPS="*"`) because only the
proxy can reach it. If you expose port 8000 differently, set `FORWARDED_ALLOW_IPS` to the proxy's address.

Then, in the app: **Settings › NanoTune server** → `https://api.example.com` → Save. The status line
should read "Online · ShazamKit tokens ready" (with an Apple key).

## Optional: attach AudD and/or ACRCloud

Both are paid services (after a trial) billed to you, so each needs an explicit switch, credentials and a
daily cap you choose. In `.env`:

```bash
# AudD (https://dashboard.audd.io)
AUDD_ENABLED=true
AUDD_API_TOKEN=your-audd-token
AUDD_DAILY_LIMIT=100            # hard cap per UTC day for the whole server; 0 = off

# ACRCloud (console › your audio & video recognition project)
ACRCLOUD_ENABLED=true
ACRCLOUD_HOST=identify-eu-west-1.acrcloud.com   # the host shown for your project
ACRCLOUD_ACCESS_KEY=...
ACRCLOUD_ACCESS_SECRET=...
ACRCLOUD_DAILY_LIMIT=100

PROVIDER_ORDER=audd,acrcloud    # order in which engines are tried
ENHANCED_PER_USER_DAILY_LIMIT=25

# Only if you want people to use the engines without an account (e.g. the APK without ShazamKit):
SERVER_RECOGNITION_ANONYMOUS=true
SERVER_RECOGNITION_PER_INSTALL_DAILY_LIMIT=10
SERVER_RECOGNITION_PER_IP_DAILY_LIMIT=50
```

Restart (`docker compose up -d`) and check `curl -s http://127.0.0.1:8000/api/v1/config`: the
`server_engines` block should show `"available": true` and list the engines. Without accounts or
`SERVER_RECOGNITION_ANONYMOUS=true` the engines aren't usable, and `message` says why. In the app, people
then see "Your server can identify songs" and choose whether to allow uploads to the engines listed.
Misconfigured engines (missing key or limit 0) are skipped with a warning in the logs; keys are never logged.

## Updates

```bash
cd /srv/projects/nanotune && git pull
cd deploy && docker compose up -d --build
```

## Backup and restore

* Backup (consistent, online): `cd deploy && ./backup.sh` → `deploy/backups/nanotune-<timestamp>.db`
  (keeps the last 14). Schedule it with cron, e.g. `17 3 * * * cd /srv/projects/nanotune/deploy && ./backup.sh`.
  Copy backups off the server. Also keep a secure copy of `.env` and the `.p8` key (Apple lets you download
  the key only once).
* Restore:

  ```bash
  cd /srv/projects/nanotune/deploy
  docker compose stop nanotune-api
  docker run --rm -v deploy_nanotune-data:/data -v "$PWD/backups:/backups" alpine \
    sh -c 'cp /backups/nanotune-YYYYMMDDTHHMMSSZ.db /data/nanotune.db && rm -f /data/nanotune.db-wal /data/nanotune.db-shm && chown 10001:10001 /data/nanotune.db'
  docker compose start nanotune-api
  ```

  (The volume name is `<compose project>_nanotune-data`; check with `docker volume ls`.)

## Operations notes

* One Uvicorn worker on purpose: rate limits are kept in memory.
* Logs never contain tokens, passwords, keys or audio. Docker log rotation is configured (3 × 10 MB).
* `RECOGNITION_KILL_SWITCH=true` disables all server-side recognition engines immediately (restart the
  container after editing `.env`). Usage counters per engine, user, install and IP are kept per UTC day in
  the database.
* To close registrations: `REGISTRATION_OPEN=false`. To disable accounts entirely: `ACCOUNTS_ENABLED=false`
  (anonymous token issuance keeps working).
* **Password reset and email confirmation** work once `SMTP_HOST` and `SMTP_FROM` are set (plus
  `SMTP_USERNAME`/`SMTP_PASSWORD` and `SMTP_SECURITY`, see `.env.example`). The server emails 6-digit codes
  (15 minutes, five tries, stored only as keyed hashes). Without SMTP the app says password reset is
  unavailable. Delivery errors are logged without addresses or codes.
* Delete all server data: `docker compose down -v` (removes the volume).

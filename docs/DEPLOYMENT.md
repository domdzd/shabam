# Deploying the NanoTune server on a VPS

The server signs ShazamKit developer tokens (required for recognition) and, optionally, hosts accounts
and sync. It is a single small container (FastAPI + SQLite).

## Requirements

* A Linux VPS with Docker Engine and the Compose plugin (v2.24+).
* A domain or subdomain pointing at the VPS, e.g. `api.example.com`, and HTTPS (nginx/Caddy/Traefik).
* For recognition: an Apple Developer Program membership and a Media Services key (see
  docs/RECOGNITION_PROVIDERS.md). Without it the server still runs (accounts/sync work) and the app
  explains that recognition isn't configured.

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
should read "Online · ShazamKit tokens ready".

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
  container after editing `.env`).
* To close registrations: `REGISTRATION_OPEN=false`. To disable accounts entirely: `ACCOUNTS_ENABLED=false`
  (anonymous token issuance keeps working).
* Email verification and password reset are **not implemented** (no SMTP flow yet); the app tells users that
  password reset is unavailable.
* Delete all server data: `docker compose down -v` (removes the volume).

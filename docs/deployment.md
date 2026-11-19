# Deployment

Two zero-cost paths. Both have been written against the actual free tiers and
the actual Docker images this project builds; neither has been run end to end
yet, because deploying needs an account that only you can create. Where a
step needs a decision from you, it says so.

| | Option A - one VM | Option B - managed free tiers |
|---|---|---|
| Frontend | Next.js container | Vercel |
| Backend | Spring Boot container | Render free web service |
| Database | PostgreSQL container | Neon free tier |
| Cache | Redis container | Upstash free tier |
| TLS | Caddy, automatic | Provided by each platform |
| Cost | $0 (Oracle Always Free) | $0 |
| Cold starts | none | Render free spins down after 15 min idle |
| Best for | a demo you want always-on and fast | the quickest path, no server to maintain |

Recommendation: **Option A**. Everything runs on one machine with one origin,
so there are no cross-site cookie problems, the backend never cold-starts, and
the whole stack is the same Compose file you already run locally.

---

## Option A - Oracle Cloud Always Free VM behind Caddy

Oracle's Always Free tier includes an Ampere A1 instance (up to 4 OCPUs and
24 GB RAM) that does not expire. That is far more than this needs.

### 1. Create the VM

1. Sign up at <https://cloud.oracle.com/> (a card is required for identity
   verification; Always Free resources are not charged).
2. **Compute → Instances → Create instance.**
   - Image: Ubuntu 22.04
   - Shape: **Ampere A1 Flex**, 2 OCPU / 12 GB (inside Always Free)
   - Add your SSH public key.
3. **Networking → Virtual Cloud Network → Security Lists**: allow ingress on
   TCP 80 and 443 from `0.0.0.0/0`.
4. On the VM, Ubuntu's own firewall also blocks these by default:

   ```bash
   sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```

### 2. Install Docker

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
sudo usermod -aG docker $USER && newgrp docker
```

### 3. Point a domain at it

Any free subdomain works (DuckDNS, or a domain you own). Create an `A` record
for `fasttrack.example.com` pointing at the VM's public IP. Caddy needs this
to issue a certificate.

### 4. Clone and configure

```bash
git clone <your-repo-url> fasttrack && cd fasttrack
cp .env.example .env
```

Edit `.env`:

```bash
POSTGRES_PASSWORD=<a long random string>
TOKEN_ENCRYPTION_KEY=<openssl rand -base64 32>
APP_FRONTEND_URL=https://fasttrack.example.com
APP_BACKEND_URL=https://fasttrack.example.com
NEXT_PUBLIC_API_BASE_URL=https://fasttrack.example.com
APP_SECURE_COOKIES=true
GOOGLE_CLIENT_ID=<from docs/google-setup.md>
GOOGLE_CLIENT_SECRET=<from docs/google-setup.md>
OPENAI_API_KEY=<from docs/llm-setup.md, optional>
# Do not publish the database and cache ports on a public VM.
POSTGRES_PORT=127.0.0.1:5432
REDIS_PORT=127.0.0.1:6379
```

`APP_SECURE_COOKIES=true` is what turns on `Secure` cookies and HSTS. Setting
it without TLS in front will break sign-in, which is the intended failure.

### 5. Add Caddy

One origin for both the app and the API, which keeps cookies first-party.
Create `Caddyfile`:

```
fasttrack.example.com {
    encode gzip zstd

    # The API and the OAuth2 endpoints live on the backend.
    @api path /api/* /oauth2/* /login/* /actuator/* /v3/api-docs* /swagger-ui*
    handle @api {
        reverse_proxy backend:8080
    }

    handle {
        reverse_proxy frontend:3000
    }
}
```

Add to `docker-compose.yml` (or a `docker-compose.prod.yml` overlay):

```yaml
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on: [backend, frontend]
```

and `caddy_data: {}`, `caddy_config: {}` under `volumes:`.

### 6. Update the Google redirect URI

In Google Cloud → Credentials → your OAuth client, add:

```
https://fasttrack.example.com/login/oauth2/code/google
```

### 7. Start it

```bash
docker compose up -d --build
docker compose logs -f backend   # watch Flyway run and the demo seed
```

Caddy issues a certificate on the first HTTPS request. Open
`https://fasttrack.example.com` and press **Try the demo**.

### 8. Keep it running

- `restart: unless-stopped` on every service survives a reboot.
- Back up the database:

  ```bash
  docker compose exec -T postgres pg_dump -U fasttrack fasttrack | gzip > backup-$(date +%F).sql.gz
  ```

- Update: `git pull && docker compose up -d --build`.

---

## Option B - Vercel, Render, Neon and Upstash

### 1. Database: Neon

1. Create a project at <https://neon.tech> (free tier: 0.5 GB).
2. Copy the connection string and convert it to JDBC form:

   ```
   postgresql://user:pass@host/db?sslmode=require
   -> jdbc:postgresql://host/db?sslmode=require   (user and password go in their own variables)
   ```

Neon's free tier suspends an idle database; the first query after that takes
a second or two. Flyway runs on start-up, so the schema is created
automatically.

### 2. Cache: Upstash

Create a Redis database at <https://upstash.com> (free tier: 10,000
commands/day). Note the host, port and password, and that TLS is required:

```bash
SPRING_DATA_REDIS_HOST=<host>
SPRING_DATA_REDIS_PORT=<port>
SPRING_DATA_REDIS_PASSWORD=<password>
SPRING_DATA_REDIS_SSL_ENABLED=true
```

> The daily command cap is the real constraint here: sessions, the
> classification cache and rate limiting all use Redis. One person's usage
> fits comfortably; a public demo with real traffic may not.

### 3. Backend: Render

1. **New → Web Service**, connect the repository, root directory `backend`.
2. Environment: **Docker** (it picks up `backend/Dockerfile`).
3. Instance type: **Free**.
4. Environment variables: everything from `.env.example` except the frontend
   ones, plus `APP_SECURE_COOKIES=true` and `APP_FRONTEND_URL` set to the
   Vercel URL.
5. Health check path: `/actuator/health`.

Render's free tier spins the service down after 15 minutes idle; the next
request waits roughly 50 seconds for a cold start. For a demo link on a
resume, that is the single biggest argument for Option A.

### 4. Frontend: Vercel

1. **Import Project**, root directory `frontend`.
2. Environment variable: `NEXT_PUBLIC_API_BASE_URL=https://<render-url>`.
3. Deploy.

### 5. Cross-origin cookies

Frontend and backend are now on different sites, so the session cookie must
be `SameSite=None; Secure` - `Lax` will not be sent on the API's XHRs. Change
`server.servlet.session.cookie.same-site` to `none` (and the CSRF cookie
customiser in `SecurityConfig`) before using this option, and set
`APP_FRONTEND_URL` exactly, since CORS allows that one origin.

This is the step that makes Option B more fragile than Option A, and the
reason the recommendation stands.

### 6. Google redirect URI

Add `https://<render-url>/login/oauth2/code/google` in Google Cloud.

---

## Before you deploy, either way

- [ ] `TOKEN_ENCRYPTION_KEY` generated fresh for production (never reuse the
      development one).
- [ ] `POSTGRES_PASSWORD` is a long random string.
- [ ] `APP_SECURE_COOKIES=true`.
- [ ] Database and Redis ports are **not** published publicly.
- [ ] The Google OAuth client lists the production redirect URI, and your
      address is a test user (docs/google-setup.md).
- [ ] `.env` is on the server only - it is git-ignored and must stay that way.
- [ ] Decide whether the demo account stays enabled (`APP_DEMO_ENABLED`). For
      a link on a resume, yes: it is the whole point.

## What I still need from you

Deploying needs an account I cannot create. When you are ready, tell me which
option you want and provide either SSH access to the VM (Option A) or the
Render/Vercel/Neon/Upstash URLs and tokens (Option B), and I will do the
configuration and the first deploy.

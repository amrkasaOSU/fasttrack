# Security

What FastTrack does to protect a mailbox's worth of personal data, and how
each claim is verified. Every item marked **tested** has an automated test
that fails the build if it regresses.

## Threat model, briefly

The data at risk is one student's job search: which companies rejected them,
which interviews they have, their resume, and a token that can read their
email. The realistic threats are another user of the same deployment, someone
who gets hold of the database, and a cross-site request from a page the user
visits while signed in.

## Authentication

- Google OAuth2 (Authorization Code) for sign-in; no password is ever
  handled, stored or checked by this application.
- Sessions are opaque cookies backed by Redis - revocable server-side, which
  a JWT would not be. "Disconnect Gmail" and "Delete all my data" take effect
  immediately (DECISIONS.md D-002).
- Session cookie: `HttpOnly`, `SameSite=Lax`, `Secure` when
  `APP_SECURE_COOKIES=true`, with a new session id issued on sign-in
  (session-fixation protection). **tested**
- A demo session is a real but restricted account: it can read everything in
  the demo data and cannot sync Gmail, disconnect, or delete. **tested**

## Authorisation

- One `AuthorizationService` is the only place ownership is decided. The
  acting user comes from the session; **no endpoint accepts a user id** from
  the client, and sending one in the body changes nothing. **tested**
- Every repository query is scoped by `user_id`. There is deliberately no
  finder that takes an id alone.
- A resource owned by someone else returns **404, not 403** - a 403 would
  confirm the id exists.
- `CrossUserIsolationIntegrationTest` seeds one account with an application,
  a resume, a fit check and a classified email, then has a second account try
  every read and every write against all of it: eight tests, each asserting
  both the refusal and that nothing changed. It also asserts that no response
  body contains the other user's content. **tested**

## Cross-site request forgery

- All state-changing requests require the `X-XSRF-TOKEN` header matching the
  readable `XSRF-TOKEN` cookie. A request with the cookie but no header is
  refused. **tested**
- CORS allows exactly one origin, the configured frontend URL, with
  credentials. A request from any other origin is refused at preflight.
  **tested**

## Secrets

- The Gmail refresh token is encrypted with **AES-256-GCM** before it reaches
  the database: ciphertext and a fresh 96-bit nonce in separate columns, key
  from `TOKEN_ENCRYPTION_KEY` and held only in the process environment.
  **tested** (round trip, fresh nonce per encryption, tamper detection, wrong
  key rejected).
- With no key configured the application starts, reports Gmail as not
  configured, and **refuses to store a token at all**. There is no plaintext
  fallback. **tested**
- Access tokens are never persisted. `GmailCredentials.toString()` prints
  `***` so a token cannot reach a log through string interpolation.
  **tested**
- Spring Security's OAuth2 logger is pinned to `WARN`, because its debug
  output includes tokens.
- `.env` is git-ignored; `.env.example` documents every variable with no real
  values. No secret has ever been committed.

## Input handling

- Bean Validation on every request body, with length bounds on each field.
  **tested**
- Uploads: resumes must be real PDFs (checked by parsing, not by extension),
  at most 2 MB; CSV imports at most 5 MB and must end in `.csv`. **tested**
- Fetched job-posting HTML is stripped with Jsoup's `Safelist.basic()` before
  its text is used - it is untrusted input that ends up in a prompt and on a
  page.
- All database access is through JPA with bound parameters; there is no
  string-concatenated SQL in the codebase.
- React escapes by default and the frontend never uses
  `dangerouslySetInnerHTML`.

## Rate limiting

Per-user fixed-window counters in Redis: syncs (12/hour), fit checks
(30/hour), uploads (10/hour), LLM calls (300/hour). A `429` carries
`Retry-After`. Redis being unavailable allows the request rather than taking
the product down.

## Response headers

Set on every API response, and asserted by test:

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `same-origin` |
| `Permissions-Policy` | camera, microphone, geolocation, payment, USB all denied |
| `Strict-Transport-Security` | one year, including subdomains - **only** when `APP_SECURE_COOKIES=true`, since it would be wrong on a plain-HTTP dev machine |

The frontend sets its own CSP allowing only its own origin and the API.

## What is exposed

- Actuator publishes `health` and `info` only. `env`, `beans`, `configprops`,
  `mappings` and `heapdump` are not exposed; health shows no component
  details. **tested**
- Error responses are a single shape with a stable code and a safe message.
  Stack traces, SQL and class names never reach the client. **tested**

## Gmail access

Read-only by construction. The OAuth scope list is
`openid email profile gmail.readonly`, asserted by a test that also fails if
anything matching `gmail.send`, `gmail.modify`, `gmail.compose` or
`mail.google.com` is ever added. The `GmailApi` interface exposes only list,
get, history and profile - there is no method a caller could use to write.

## Known limitations

- **No 2FA of our own.** Account security is Google's; if a user's Google
  account is compromised, so is their FastTrack account.
- **Single-tenant threat model.** This is built for one user per deployment
  plus a shared demo. It is isolated per user, but it has not been reviewed
  for a hostile multi-tenant environment.
- **No audit log of reads.** Writes are recorded in `application_events`;
  reads are not logged.
- **Rate limits are per user, not per IP.** An unauthenticated flood would be
  handled by the reverse proxy, not by the application.
- **Dependency scanning is not automated.** Versions are pinned, but no
  Dependabot or CVE gate runs in CI yet.

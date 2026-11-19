# API

Base URL: `http://localhost:8080` in development. Interactive docs, generated
from the code, at `/swagger-ui.html`; the OpenAPI document is at
`/v3/api-docs`.

## How requests are authenticated

A session cookie, not a token.

1. `GET /api/config` (or any GET) returns an `XSRF-TOKEN` cookie.
2. Sign in: `POST /api/auth/demo`, or send the browser to
   `/oauth2/authorization/google`.
3. Every state-changing request must send the `X-XSRF-TOKEN` header with the
   value of that cookie, and must include cookies (`credentials: "include"`).

An unauthenticated call to a protected endpoint returns `401` with a JSON
body - never a redirect to a login page.

```bash
# the whole handshake, by hand
curl -c jar -b jar -s http://localhost:8080/api/config > /dev/null
TOKEN=$(awk '/XSRF-TOKEN/{print $7}' jar)
curl -c jar -b jar -s -X POST -H "X-XSRF-TOKEN: $TOKEN" \
     http://localhost:8080/api/auth/demo
curl -c jar -b jar -s "http://localhost:8080/api/applications?size=5"
```

## Errors

Every endpoint returns the same shape:

```json
{
  "code": "not_found",
  "message": "Application not found",
  "fieldErrors": [{ "field": "companyName", "message": "must not be blank" }],
  "timestamp": "2027-02-16T15:04:05Z"
}
```

| Code | Status | Means |
|---|---|---|
| `unauthenticated` | 401 | No session |
| `forbidden` | 403 | Signed in, but not allowed (including CSRF failures) |
| `not_found` | 404 | No such resource **or** it belongs to someone else |
| `bad_request` | 400 | Valid syntax, unusable content |
| `validation_failed` | 400 | One or more fields failed validation; see `fieldErrors` |
| `file_too_large` | 413 | Upload over the limit |
| `rate_limited` | 429 | Per-user limit reached; `Retry-After` header is set |
| `not_configured` | 503 | The feature needs credentials this deployment does not have |
| `internal_error` | 500 | Something unexpected; details are logged, never returned |

## Endpoints

### Auth and configuration

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/config` | Which optional integrations are configured |
| `GET` | `/api/auth/me` | Current session (200 with `authenticated: false` when signed out) |
| `POST` | `/api/auth/demo` | Start a session as the seeded demo user |
| `POST` | `/api/auth/logout` | Destroy the session |
| `GET` | `/oauth2/authorization/google` | Begin Google sign-in (browser redirect) |

### Applications

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/applications` | List: `status`, `search`, `includeArchived`, `page`, `size`, `sort` |
| `GET` | `/api/applications/{id}` | One application with its full timeline |
| `GET` | `/api/applications/needs-review` | Applications the matcher was unsure about |
| `POST` | `/api/applications` | Create |
| `PUT` | `/api/applications/{id}` | Edit |
| `POST` | `/api/applications/{id}/status` | Change status, recorded in the timeline |
| `POST` | `/api/applications/{id}/notes` | Append a note |
| `POST` | `/api/applications/{id}/merge` | Merge another application into this one |
| `POST` | `/api/applications/{id}/archive` / `/unarchive` | Archive state |
| `POST` | `/api/applications/{id}/resolve-review` | Clear the review flag |
| `DELETE` | `/api/applications/{id}` | Delete, with its history |
| `POST` | `/api/applications/import` | CSV import (multipart `file`) |
| `GET` | `/api/applications/export` | CSV export |

Sorting accepts `updatedAt` (default), `appliedAt`, `company`, `status`,
`oldest`. `size` is clamped to 100.

### Review and corrections

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/review` | Low-confidence classifications and uncertain matches |
| `POST` | `/api/review/emails/{id}/correct` | Correct a classification; re-derives the application |
| `GET` | `/api/review/accuracy` | Accuracy from your corrections, with its caveat |

### Resume and fit

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/resumes` | Your resume versions |
| `POST` | `/api/resumes` | Upload a PDF (multipart `file`, max 2 MB) |
| `GET` | `/api/resumes/{id}` | One version with its parsed profile |
| `PUT` | `/api/resumes/{id}/profile` | Save corrections to the parsed profile |
| `POST` | `/api/resumes/{id}/activate` | Use this version for fit checks |
| `DELETE` | `/api/resumes/{id}` | Delete a version |
| `POST` | `/api/fit` | Score the active resume: `postingText` or `url`, optional `applicationId` |
| `GET` | `/api/fit` | Recent fit checks |
| `GET` | `/api/fit/{id}` | One fit check with its full breakdown |
| `POST` | `/api/fit/{id}/link/{applicationId}` | Attach a fit check to an application |
| `GET` | `/api/fit/explanation` | The weights and bands, for the UI legend |

### Insights and settings

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/insights` | Totals, weekly volume, funnel, rates, timings, action items |
| `GET` | `/api/settings` | Gmail connection state, last sync, what is stored |
| `POST` | `/api/settings/gmail/sync` | Sync now |
| `POST` | `/api/settings/gmail/disconnect` | Disconnect and revoke at Google |
| `DELETE` | `/api/settings/data` | Delete the account and everything belonging to it |

### Operations

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/actuator/health` | Liveness for Docker and the load balancer |

Nothing else under `/actuator` is exposed.

## Rate limits

Per user, per hour: 12 syncs, 30 fit checks, 10 uploads, 300 LLM calls.
Exceeding one returns `429` with `Retry-After` in seconds.

## Things the API deliberately does not do

- **It never takes a user id from the client.** Ownership comes from the
  session; a `userId` in a request body is ignored.
- **It does not return another user's resource as 403.** It returns 404, so
  ids cannot be probed.
- **It does not return stack traces or SQL**, even in development.

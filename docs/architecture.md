# Architecture

FastTrack is a modular monolith: one Spring Boot process behind one Next.js
frontend, with PostgreSQL for durable state and Redis for sessions, caching and
rate limiting.

```
┌──────────────┐      cookies + CSRF       ┌─────────────────────────────┐
│  Next.js     │  ───────────────────────► │  Spring Boot API            │
│  App Router  │  ◄─────────────────────── │                             │
│  (browser)   │        JSON               │  account  auth   config     │
└──────────────┘                           │  application   classify     │
                                           │  csv   matching   demo      │
                                           │  (resume, fit, gmail: next) │
                                           └────────┬──────────┬─────────┘
                                                    │          │
                                      JPA + Flyway  │          │  Spring Data Redis
                                                    ▼          ▼
                                            ┌────────────┐  ┌────────────┐
                                            │ PostgreSQL │  │  Redis 7   │
                                            │     16     │  │ sessions,  │
                                            └────────────┘  │ LLM cache, │
                                                            │ rate limit │
                                                            └────────────┘
                     ┌──────────────┐        ┌──────────────────────────┐
                     │ Gmail API    │        │ OpenAI-compatible LLM    │
                     │ (read-only)  │        │ (Groq free tier, Ollama) │
                     └──────────────┘        └──────────────────────────┘
                        optional                      optional
```

## Why a monolith

One developer, one database, and a $0 hosting budget. The whole system fits in
a single free-tier VM; splitting it would add a message broker and a second
runtime for no benefit at this scale. Package boundaries keep the option open:
no package reaches into another package's repositories, only its services.

## Packages

| Package | Responsibility |
|---|---|
| `account` | User records and lookup |
| `auth` | Session security, Google OAuth2 registration, ownership checks |
| `config` | Typed configuration, JSON setup, OpenAPI, feature reporting |
| `common` | Error types and the single error response shape |
| `application` | Applications, the status state machine, append-only history |
| `matching` | Normalization of company and position text (and, later, the matcher) |
| `csv` | Import and export of the applications table |
| `classify` | Email categories (classifier lands in milestone 3) |
| `demo` | The seeded demo account |

## Request flow

1. The browser holds an opaque `FASTTRACK_SESSION` cookie; Spring Session
   resolves it against Redis.
2. `SecurityConfig` enforces CSRF on every state-changing request using the
   readable `XSRF-TOKEN` cookie, and answers unauthenticated API calls with
   `401` JSON rather than a redirect.
3. Controllers never accept a user id. They ask `AuthorizationService` for the
   session's user, and every repository query is scoped by it.
4. Writes that change an application's status also append an
   `application_events` row, so the timeline is the record of truth.

## Data flow for an incoming email (milestones 3-5)

```
Gmail history ──► prefilter (rules) ──► cache lookup (Redis, content hash)
                      │                        │ miss
                      │ dropped                ▼
                      ▼                   LLM classify ──► schema validation
                 metrics only                  │                │ invalid
                                               │                ▼
                                               │        rule-based fallback
                                               ▼
                                     matcher ──► application + event
```

Message bodies are processed in memory and never stored; see
[privacy.md](privacy.md).

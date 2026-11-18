# Changelog

All notable changes to FastTrack. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Milestone 1 - scaffold, infrastructure, auth, schema
- Repository scaffold: backend (Spring Boot 3 / Java 21 / Maven), frontend
  (Next.js App Router / TypeScript / Tailwind), docs, eval, prompts.
- Docker Compose for local development: PostgreSQL 16, Redis 7, backend,
  frontend.
- `.env.example` documenting every environment variable; no secrets in git.
- Flyway baseline migration: users, gmail accounts, emails, applications,
  application events, resumes, job postings, fit checks, corrections.
- Session authentication (Redis-backed) with demo login, CSRF protection and
  an ownership-checking authorization service.
- GitHub Actions CI: backend tests + JaCoCo, frontend typecheck/lint/build.

### Milestone 2 - applications, state machine, history, CSV, demo data
- Application domain: sixteen-state status model with an explicit state machine
  that only ever moves forward, never infers a rejection from silence, and
  treats terminal states as user-only.
- Append-only `application_events` history; every status change records what
  caused it, and the application page shows the timeline.
- Applications API: list with search/filter/paging, detail with timeline,
  create, edit, status change, notes, archive, merge, delete - all scoped to
  the session user.
- Company and position normalization (legal suffixes, seasons, requisition
  markers, abbreviations) so one role does not become five applications.
- CSV import and export, including a hand-written RFC 4180 reader, flexible
  date and status parsing, per-row error reporting and duplicate skipping.
- Demo account seeded with 60 applications and realistic outcomes, generated
  from a fixed seed so every machine shows the same demo.
- Frontend: app shell with navigation, applications table (searchable,
  filterable, paginated, responsive), application detail with timeline and
  status editing, add/edit form (React Hook Form + Zod), CSV tools, settings.
- Docs: architecture.md, database.md. Compose host ports are now overridable.

### Milestone 3 - prefilter, LLM abstraction, classifier, caching, evaluation
- Rule-based prefilter that drops job alerts, newsletters, networking
  notifications and account plumbing before any model call, while keeping ATS
  mail and anything carrying a decisive application phrase.
- `LlmProvider` abstraction with one OpenAI-compatible HTTP implementation
  (Groq, Ollama, OpenRouter, vLLM - no vendor SDK), with selective retries,
  backoff and client-side latency measurement.
- Versioned prompt file `prompts/classify_v1.txt`, loaded from the classpath;
  the version is stored on every classification and is part of the cache key.
- Strict schema validation of model output: unknown category or out-of-range
  confidence is refused, one retry is attempted, then the rule-based
  classifier takes over. A classification always comes back.
- Redis classification cache keyed by content hash + prompt version + model,
  with graceful degradation when Redis is unavailable.
- `llm_invocations` metrics for every call that leaves the process.
- Evaluation harness: 200 labeled synthetic emails (`eval/emails.jsonl`,
  generated with a fixed seed), a `-Peval` runner reporting accuracy,
  per-category precision/recall/F1, a confusion matrix, latency percentiles,
  prefilter drop rate and review-queue effectiveness.
- Docs: ai.md (prompts, schema, fallbacks, caching, evaluation caveats).

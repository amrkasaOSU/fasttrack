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

### Milestone 4 - Gmail OAuth, encrypted tokens, sync
- Refresh tokens encrypted at rest with AES-256-GCM, a fresh nonce per
  encryption, and a flat refusal to store anything when no key is configured.
- Gmail access through the official client, restricted to list/get/history/
  profile - there is no code path that can send, label or delete mail.
- Sync: 120-day window on first run, then Gmail's historyId as the cursor;
  idempotent by unique index; per-message transactions; exponential backoff on
  429 and 5xx; a revoked grant marks the account for reconnection.
- Duplicate matcher: requisition id, exact normalized title, token-similarity
  match, and an uncertainty band that attaches but asks for review.
- Scheduled sync every 15 minutes, plus an immediate first sync when a mailbox
  is connected.
- Per-user rate limiting in Redis for sync.
- Settings API and page: connection state, sync now, disconnect (revoking the
  grant at Google), stored-data counts, and "Delete all my data" with a typed
  confirmation.
- Docs: google-setup.md (free Testing-mode setup, step by step) and
  llm-setup.md (Groq free tier or local Ollama).
- Fixed: the application failed to start when Google credentials were absent,
  which broke the "runs without Gmail" guarantee. Caught by the test suite.

### Milestone 5 - matcher evaluation, review queue, corrections
- The matcher's pair comparison is now a pure function, so the evaluation
  measures the same code production runs.
- 100 hand-labeled application pairs and a `-Peval` runner that reports
  precision, recall, F1 and a full threshold sweep, which is what justifies
  the 0.60 similarity threshold.
- Normalization fixes found by that evaluation: "back end"/"backend" and
  similar spellings folded; bracketed platform tags like "(iOS)" are kept
  while "(Summer 2027)" is still stripped; a stated season or platform that
  disagrees now blocks a match outright.
- Needs review queue: low-confidence classifications and uncertain matches,
  with the reason shown for each.
- Corrections: a correction is recorded, the classification is rewritten, and
  the application is re-derived - re-matched and re-run through the state
  machine - so fixing an email actually fixes the tracker.
- Live accuracy endpoint, returned with an explicit caveat that it measures
  uncorrected share rather than verified accuracy.

### Milestone 6 - resume parsing, posting parsing, fit scorer
- Resume upload: PDF only, 2 MB cap, text extracted with PDFBox, refusing
  encrypted files and scans with a usable explanation. Versioned per user;
  the newest is active. The PDF itself is not stored.
- Resume structuring by the model with a heuristic fallback, always shown to
  the user for correction before anything is scored.
- Job postings: pasted text (primary) or a fetched URL (Jsoup, sanitised,
  with an honest failure when the page needs JavaScript).
- Skill dictionary: ~90 canonical skills with aliases ("Postgres" =
  "PostgreSQL", "k8s" = "Kubernetes") and one-way supersets ("Spring Boot"
  implies Spring and Java).
- Deterministic fit score: 50% required coverage, 20% preferred, 15%
  experience relevance, 15% eligibility; a hard eligibility mismatch caps the
  total at 40, and a posting with no readable skills caps it at 50.
- Honest suggestions only, enforced by an automated check across the whole
  evaluation set: gaps produce learning advice, never "add this to your
  resume".
- 20 hand-labeled resume/posting pairs and an eval runner reporting band
  agreement and the honesty check.
- Fit Checker page: upload, parsed-skills review, paste or link a posting,
  and a full breakdown of how the score was reached.
- Fixed by the evaluation: a JSON field-naming mismatch that silently
  disabled every eligibility check, a vague posting scoring 75, and a
  miscalibrated fallback experience rating.
- Fixed: one `with(csrf())` in a new test replaced the shared CSRF repository
  and broke 19 unrelated tests - the real cookie handshake is now used
  everywhere, including multipart uploads.

### Milestone 7 - dashboard and insights
- Insights API: totals, applications per week, funnel, response rate by ATS
  and by location type, time to first response and to rejection, action items
  and the review count, all computed per user.
- The funnel reads the append-only event history, so an application rejected
  after an on-site still counts as having interviewed. Silence is reported as
  its own outcome and never folded into rejections.
- Every rate carries the counts it came from, and a rate over fewer than two
  applications is not charted at all.
- Fit-versus-outcome is withheld until at least eight fit checks are linked to
  applications, with a line saying how many are needed.
- Dashboard page: stat tiles, action items with countdowns, Recharts weekly
  volume, funnel, and two response-rate charts, each with a text equivalent
  next to it.

### Milestone 8 - security hardening, isolation tests, end-to-end suite
- Security headers on every API response (CSP, Permissions-Policy, COOP,
  frame options, referrer policy) and on every frontend page; HSTS only when
  running behind TLS.
- 23 security tests: public versus protected endpoints, CSRF on every
  mutating verb, CORS restriction, actuator exposure, error-message leakage,
  cookie flags, upload validation, input bounds.
- 8 cross-user isolation tests: a second account tries every read and every
  write against another user's application, resume, fit check and email, and
  the responses are also scanned for leaked content.
- Demo mailbox: ~136 synthetic emails tied to the demo applications' own
  histories, including noise the prefilter drops, and a few deliberately
  low-confidence classifications so the review queue demonstrates something.
- Playwright end-to-end suite covering demo login, dashboard, adding an
  application, correcting a classification, the fit checker, settings and
  sign-out. 7 tests, 3.9 s.
- CI now has four jobs: backend verify with the coverage gate, the evaluation
  harnesses (which also check the committed eval sets still match their
  generators), frontend typecheck/lint/build, and end-to-end against a real
  Compose stack.
- Docs: security.md.
- Fixed: a privacy test asserted over every user's emails rather than the one
  under test, so it started failing for the right reason once the demo
  mailbox existed.

### Milestone 9 - performance measurement
- k6 load test driving the real sign-in handshake, run at 50, 100 and 250
  concurrent users after a discarded warm-up pass: 352.9 / 707.9 / 897.6
  requests per second with zero failures across 60,143 requests.
- Sync throughput measured against a WireMock Gmail: 3,888 emails per minute
  (15.4 ms per message) end to end through prefilter, classifier, matcher and
  database.
- Lighthouse run against the real signed-in pages by driving Chromium with
  Playwright and attaching Lighthouse over the DevTools protocol: 100
  performance and 100 accessibility on all four main pages.
- The demo account now has a resume, so the Fit Checker demonstrates a real
  score instead of asking a visitor to upload one.

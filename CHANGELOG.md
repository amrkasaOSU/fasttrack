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

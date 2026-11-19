# FastTrack - final report

Built over one session, in the eleven milestones you specified. Everything
below is in the repository; every number is reproducible with the command
next to it in [RESULTS.md](RESULTS.md).

## What was built

A working internship application tracker: Spring Boot 3.5 on Java 21 with
PostgreSQL 16 and Redis 7, a Next.js 16 frontend, and Docker Compose that
brings the whole thing up with seeded demo data on first boot.

| Feature | State |
|---|---|
| Google sign-in, Redis-backed sessions, demo login | Done |
| Gmail read-only sync: 120-day window, then historyId, idempotent, backoff | Done, tested against a stubbed Gmail |
| Rule prefilter + LLM classifier with schema validation, retry, rule fallback | Done |
| Redis classification cache keyed by content + prompt version + model | Done |
| Applications, 16-state machine, append-only history, CSV import/export | Done |
| Duplicate matcher with requisition / exact / similarity / review-band rules | Done |
| Needs review queue and corrections that re-derive the application | Done |
| Resume upload (PDFBox), posting parsing (paste or URL), fit scorer | Done |
| Dashboard: totals, funnel, weekly volume, response rates, action items | Done |
| Settings: connect, sync now, disconnect, delete all data | Done |
| Security hardening, cross-user isolation tests, Playwright E2E | Done |
| Load tests, sync throughput, Lighthouse | Done, measured |
| Deployment | **Written up, not deployed** - needs an account only you can create |
| LLM-path measurements | **Not measured** - needs an API key |

## Key decisions

All twenty are in [DECISIONS.md](DECISIONS.md) with the alternative that was
rejected. The five that shaped the product most:

1. **Modular monolith, not microservices** (D-001) - one free-tier VM, one
   database, one deployable.
2. **Sessions in Redis, not JWT** (D-002) - "disconnect Gmail" and "delete my
   data" must take effect immediately, which a JWT cannot do.
3. **The LLM sits behind an interface, speaking the OpenAI wire format**
   (D-003) - Groq, Ollama, OpenRouter and vLLM are an environment variable,
   and the rule-based classifier is maintained as a first-class fallback and
   published baseline (D-010).
4. **The similarity threshold was derived from data, not taste** (D-013) -
   the evaluation sweeps every candidate and the table is in RESULTS.md.
5. **Suggestions may never ask you to claim something** (D-018) - enforced by
   a test across every suggestion in the evaluation set, not by review.

## Measured results

Every figure below came from a command that was actually run.

### Classification (200 labeled emails, deterministic path)

| Measure | Value |
|---|---|
| End-to-end accuracy | 96.5 % (193/200) |
| Macro F1 over ten categories | 0.954 |
| Accuracy on the 89 hardest cases | 96.6 % |
| Prefilter removal rate | 9.5 %, zero job-related mail lost |
| Classifier errors caught by the review queue | 7 of 7 - zero silent errors |
| Mean confidence / sent to review | 0.787 / 4.0 % |

### Duplicate detection (100 labeled pairs)

Precision 1.000, recall 1.000, F1 1.000, zero false merges. The threshold
sweep showed precision collapsing below 0.55 (16 false merges at 0.40) and
recall decaying above 0.65, which is why 0.60 is used.

### Fit scorer (20 labeled resume/posting pairs)

Exact band agreement 65 %, within one band 100 %, zero suggestions that would
have the user claim something unevidenced.

### Performance

| Concurrent users | Requests/sec | Errors | Dashboard p50/p95/p99 |
|---:|---:|---:|---|
| 50 | 352.9 | 0.00 % | 104 / 182 / 237 ms |
| 100 | 707.9 | 0.00 % | 53 / 327 / 522 ms |
| 250 | 897.6 | 0.00 % | 246 / 615 / 765 ms |

60,143 requests, zero failures. Sync throughput 3,888 emails/minute
(15.4 ms/message). Lighthouse 100 performance and 100 accessibility on all
four main pages.

### Quality

437 tests under `mvn verify`, 85.6 % line coverage against an 80 % gate, plus
3 evaluation harnesses and 7 Playwright end-to-end tests.

## What the evaluations changed

Worth stating, because it is the argument for having built them:

- The matcher evaluation found three normalization bugs - "back end" vs
  "backend", bracketed platform tags being stripped with the seasons, and no
  notion of a conflicting qualifier (iOS vs Android were being merged).
- The fit-scorer evaluation found a JSON field-naming mismatch that had
  silently disabled **every** eligibility check: a posting requiring 2026
  graduation scored a 2027 graduate at 84/100.
- It also found that a posting with no readable requirements scored 75,
  because the weights redistributed onto the generous components. Capped
  at 50 now, with the reason shown.
- The test suite caught the application failing to start without Google
  credentials, which would have broken the "runs without Gmail" guarantee.

## Needs your action

1. **Google OAuth credentials** - [docs/google-setup.md](docs/google-setup.md)
   is the step-by-step free path (Testing mode, no verification). Put the
   client id and secret in `.env`, plus a `TOKEN_ENCRYPTION_KEY` from
   `openssl rand -base64 32`. Until then the app runs and reports Gmail as
   not configured.
2. **A free LLM key** - [docs/llm-setup.md](docs/llm-setup.md): Groq's free
   tier, or local Ollama. With one set, `mvn -Peval test` measures the LLM
   path against the same 200 emails and writes a second report, which is the
   only number currently missing from this document.
3. **Deployment access** - [docs/deployment.md](docs/deployment.md) has two
   $0 paths. Oracle Always Free behind Caddy is the recommendation (no cold
   starts, one origin). Tell me which you want and give me access, and I will
   configure and deploy it.
4. **Your real resume**, if you want the Fit Checker using it rather than the
   demo one.
5. ~~Repository URL~~ - done: <https://github.com/amrkasaOSU/fasttrack>, and
   the CI badge points at it.

## Incomplete or deliberately deferred

- The LLM path is unmeasured (needs a key). Everything measured above uses
  the deterministic fallbacks, which is the honest floor.
- The evaluation sets are synthetic, written by me, and the rule phrase lists
  were iterated against them - so those numbers are in-sample and should be
  read as an upper bound. The product computes a live-accuracy figure from
  real corrections, shown with that caveat.
- Not deployed.
- `unsafe-inline` remains in the frontend CSP (Next.js needs it without
  per-request nonces) - the single Lighthouse deduction in the app.
- No automated dependency/CVE scanning in CI yet.

## Resume bullets

Each uses only measured numbers from RESULTS.md.

---

### 1. The product

> **Built** a full-stack internship tracker (Java 21/Spring Boot, PostgreSQL,
> Redis, Next.js, Docker) that classifies job-application email into 10
> categories and auto-updates application status, **achieving 96.5 %
> end-to-end accuracy and 0.954 macro F1** on a 200-email labeled evaluation
> set.

**30-second version:** "I applied to 150+ internships and lost track of what
had actually happened. FastTrack reads my inbox read-only, classifies each
message into one of ten categories, and keeps each application's status
current. To know whether it worked I built a 200-email labeled set covering
the hard cases - polite rejections, confirmations that mention interviews -
and it gets 96.5 % end to end, with macro F1 reported too because the
categories are imbalanced."

- *"How do you know 96.5 % isn't overfitting to your own test set?"*
  "It partly is, and I say so in the README. The emails are synthetic because
  I can't commit real ones, and I tuned the rule phrases after seeing
  failures. That's why the app also computes live accuracy from real user
  corrections, and why macro F1 is published next to accuracy - the set is
  imbalanced, 45 rejections against 7 cancellations, so accuracy alone would
  hide a classifier that ignored the rare categories."
- *"Why macro F1 rather than weighted?"*
  "Weighted F1 would let the two big categories carry the score. The
  categories that matter operationally are the rare ones - an offer or a
  cancelled requisition is exactly what you don't want missed - so each
  category should count the same."

---

### 2. The reliability engineering

> **Designed** a four-stage degradation path (cache → LLM → one retry →
> deterministic rule engine) with strict JSON-schema validation, so the
> product runs with no API key at all; **routed low-confidence results to a
> human review queue that caught 7 of 7 classifier errors with zero silent
> mistakes**.

**30-second version:** "An LLM will sometimes be down, rate-limited, or just
return prose instead of JSON, and I didn't want any of those to break the
product. So a classification always comes back: cache first, then the model,
then one retry with a stricter reminder, then a deterministic rule engine
that I maintain as a real component. And anything under 0.7 confidence goes
to a review queue rather than silently changing a status. On the evaluation
set, all seven mistakes landed in that queue - nothing wrong slipped through
confidently."

- *"How did you pick 0.7?"*
  "From the data. At 0.7, 4 % of the set routes for review and that catches
  100 % of the errors. Higher would add review work for nothing; lower let
  the known failure shapes through. It's recorded as decision D-009."
- *"What if the rule engine and the model disagree?"*
  "The model wins when its output validates, because it's better on the
  ambiguous cases. The rules are the floor, not a vote. I publish the rule
  engine's score as the baseline precisely so the model's contribution is
  measurable rather than assumed."

---

### 3. The matcher

> **Implemented** deterministic duplicate detection (requisition id, title
> normalization, Jaccard token similarity) that keeps one application per
> company-and-role, **reaching 1.000 precision and 1.000 recall with zero
> false merges on 100 labeled pairs**, with the similarity threshold chosen
> from a documented sweep rather than by hand.

**30-second version:** "The same role arrives as 'Software Engineer Intern -
Summer 2027' from Greenhouse and 'SWE Intern' from the recruiter, and naively
that's two applications. I normalize company and title - strip legal
suffixes, seasons, requisition markers, fold abbreviations - then match on
requisition id, exact title, or token overlap. I built 100 labeled pairs and
swept every threshold: precision collapses below 0.55 and recall decays above
0.65, so I use 0.60, in the middle of the stable band."

- *"Perfect precision and recall sounds too good."*
  "It's a hundred pairs I wrote, and I added three normalization rules after
  seeing which ones failed - so it's in-sample and I say that in RESULTS.md.
  What I'd defend is the method: the threshold came from the sweep, and the
  rules I added are general - 'back end' versus 'backend' is a real spelling
  variant, not a lookup for a test row."
- *"Why Jaccard and not embeddings?"*
  "Job titles differ by whole words, not meaning - 'backend' versus 'data'.
  Embeddings would need a key, a network call and an unexplainable threshold
  to solve a spelling problem. And I weight precision over recall
  deliberately: a false merge silently destroys one application's history,
  while a missed duplicate is one click to merge."

---

### 4. The performance work

> **Load-tested** the stack with k6 at 50/100/250 concurrent users,
> sustaining **897 requests/second with 0 % errors across 60,143 requests**
> and a 615 ms p95 at peak; **measured sync throughput at 3,888 emails per
> minute** through prefilter, classification, matching and persistence.

**30-second version:** "I wanted real numbers, so each k6 virtual user does
the actual browser handshake - CSRF cookie, then sign-in - and hits the
dashboard aggregation, the applications table and a cached fit check. At 250
users it sustained about 900 requests a second with zero failures. The first
run was misleading because the 50-user level looked worse than the 100-user
level - that was JIT warm-up, so I added a warm-up pass and discarded it."

- *"What was the bottleneck at 250 users?"*
  "The dashboard aggregation - it's the only endpoint that reads the full
  event history to build the funnel. Its p95 goes from 182 ms at 50 users to
  615 ms at 250, roughly linearly, which says connection-pool contention
  rather than a lock. The next step would be a materialized summary per user,
  invalidated on write."
- *"Is 3,888 emails a minute realistic?"*
  "Not against real Gmail - that's with a local stub, so it excludes network
  latency and Gmail's own rate limits, and I say that where I report it. It
  measures the part I control: 15 ms per message through prefilter,
  classification, matching and the database, each message in its own
  transaction."

---

### 5. The privacy and security work

> **Secured** a product that holds mailbox data: AES-256-GCM encryption of
> OAuth refresh tokens, read-only Gmail scope asserted by test, email bodies
> never persisted, and **8 cross-user isolation tests proving a second
> account cannot read or modify any of another user's applications, resumes,
> fit checks or emails** - verified by 31 security tests in CI.

**30-second version:** "The app holds someone's rejections and a token that
reads their email, so I treated isolation as the thing that has to hold.
Every query is scoped by user, another user's resource returns 404 rather
than 403 so ids can't be probed, and there's a test that seeds one account
with every resource type and has a second account attempt every read and
write against all of it. Refresh tokens are AES-256-GCM encrypted, and if the
key is missing the app refuses to store a token at all rather than falling
back to plaintext."

- *"Why 404 instead of 403?"*
  "A 403 confirms the resource exists. If you can enumerate ids, that's an
  information leak on its own - you learn that a given application id is
  real. 404 tells an attacker nothing, and the owner never sees it."
- *"How do you know the Gmail scope is really read-only?"*
  "There's a test that asserts the exact scope list and fails if anything
  matching gmail.send, gmail.modify, gmail.compose or mail.google.com is ever
  added. The Gmail interface only exposes list, get, history and profile, so
  there's no method a caller could misuse. And bodies are processed in memory
  and discarded - there is no column for them in any table."

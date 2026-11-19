# Measured results

Every number in this file came from a command that was actually run on this
project. Nothing here is estimated. Each entry records **what** was measured,
**which tool** produced it, the **setup**, the **value**, and the **exact
command** to reproduce it.

Hardware for all local measurements unless stated otherwise:

| | |
|---|---|
| Machine | Apple Silicon Mac (arm64), macOS 15 |
| JDK | Temurin 21 (build target), Temurin 25 host runtime |
| Container runtime | Colima (Docker API) |
| Database | PostgreSQL 16 (container) |
| Cache | Redis 7 (container) |

---

_No measurements recorded yet. Milestones 3, 5, 6, 8 and 9 populate this file:
classifier accuracy, matcher precision/recall, fit-band agreement, test
coverage, k6 load results and Lighthouse scores._
## Test suite and coverage (re-measured each milestone)

**What:** backend unit + integration tests, and line coverage enforced by the
build.
**Tool:** JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL 16, Redis 7),
JaCoCo 0.8.16.
**Setup:** `mvn verify` on the machine described above. The integration tests
run against real containers - no in-memory database, no stubbed cache.

| Metric | M1 | M3 | M5 | M6 | M7 | M8 |
|---|---|---|---|---|---|---|
| Tests passing (`mvn verify`) | 52 | 266 | 327 | 385 | 399 | **437** |
| Test classes | 6 | 22 | 27 | 32 | 33 | **36** |
| Line coverage | 92.0 % | 89.8 % | 88.0 % | 84.7 % | 84.5 % | **85.6 %** (3684/4305) |
| Branch coverage | 81.4 % | 74.6 % | 69.5 % | 65.6 % | 65.5 % | 67.0 % (1248/1863) |
| Instruction coverage | 92.1 % | 91.7 % | 89.4 % | 86.6 % | 86.3 % | 87.4 % |
| Method coverage | 87.2 % | 85.2 % | 87.4 % | 84.4 % | 84.3 % | 85.6 % (865/1011) |
| Class coverage | 100 % | 97.6 % | 96.8 % | 97.6 % | 97.2 % | 97.8 % (174/178) |
| Coverage gate | pass | pass | pass | pass | pass | pass (80 % lines) |

Plus 3 evaluation tests run separately under `-Peval`, and 7 Playwright
end-to-end tests run against the full stack.

## Security verification (milestone 8)

**What:** the security properties the product claims, asserted rather than
described.
**Tool:** `SecurityHardeningIntegrationTest` (23 tests) and
`CrossUserIsolationIntegrationTest` (8 tests), over real PostgreSQL and Redis.

| Property | How it is verified | Result |
|---|---|---|
| One user cannot read another's data | second account tries every GET on an application, resume, fit check and email belonging to the first | all 404 |
| One user cannot change another's data | second account tries edit, status change, note, delete, merge, activate, correct | all 404, and the owner's data asserted unchanged |
| No response leaks another user's content | six list endpoints scanned for the other user's note, snippet, requisition id and user id | no leaks |
| A client-supplied user id is ignored | `userId` in the request body | stored under the session's user |
| CSRF is enforced | mutating request with the cookie but no header | 403 |
| CORS is restricted | preflight from an unlisted origin | refused |
| Hardening headers present | CSP, X-Content-Type-Options, X-Frame-Options, Referrer-Policy, Permissions-Policy | all present |
| HSTS only behind TLS | header checked with `APP_SECURE_COOKIES=false` | absent, as intended |
| Actuator is minimal | `env`, `beans`, `configprops`, `heapdump` | all 401 |
| Errors leak nothing | malformed id | no class names, SQL or stack traces in the body |
| Uploads are validated | `.txt` as a resume, `.exe` as a CSV | both refused |
| Gmail scope is read-only | OAuth registration scopes | asserted, with a test that fails if a write scope is ever added |
| Refresh tokens are unreadable at rest | ciphertext inspected | does not contain the token |

## End-to-end tests (milestone 8)

**What:** the journeys a user actually walks, against the full running stack
(real frontend, real API, real database, seeded demo data).
**Tool:** Playwright 1.65, Chromium.

| Journey | Result |
|---|---|
| Start the demo from the landing page and reach a populated dashboard | pass |
| List, search and open an application | pass |
| Add an application by hand and find it in the table | pass |
| Correct a classification and watch the review queue shrink | pass |
| Fit checker explains its weights and refuses to score with no resume | pass |
| Settings reports what the deployment can and cannot do | pass |
| Signing out ends the session and the protected area bounces back | pass |

**7 passed in 3.9 s.**

Reproduce:

```bash
docker compose up -d --build
cd frontend && E2E_BASE_URL=http://localhost:3000 npm run test:e2e
```

Counts are for `mvn verify`, which excludes the evaluation harness; the
`-Peval` runner is one further test that is run on its own because it writes
report files.

Reproduce:

```bash
make verify && python3 scripts/coverage_summary.py
```

(`make` sets `DOCKER_HOST` for Colima automatically; on Docker Desktop or
Linux, `cd backend && mvn verify` is equivalent.)

## Demo data set (milestone 2)

**What:** the data a visitor sees when they press "Try the demo".
**Tool:** `DemoDataSeeder`, fixed random seed `20260216`, asserted by
`DemoDataSeederIntegrationTest`.

| Measure | Value |
|---|---|
| Applications seeded | 60 |
| Distinct employers | 45 |
| Timeline events written | one per status change, every application has at least `CREATED` |
| Outcome mix | asserted: most applications unanswered, rejections outnumber interviews, at most 3 offers |
| Determinism | same seed produces the same data on every machine (asserted by idempotency test) |

Reproduce:

```bash
cd backend && mvn -B test -Dtest=DemoDataSeederIntegrationTest
```

## Classifier evaluation - rule-based path (milestone 3)

**What:** end-to-end classification quality on a labeled set, plus how much
work the prefilter removes before any model call.
**Tool:** `ClassifierEvaluationTest` (JUnit, `-Peval`) over
`eval/emails.jsonl` - 200 labeled synthetic emails generated by
`scripts/generate_eval_set.py` with a fixed seed. 89 are marked *tricky*
(polite rejections, confirmations that mention interviews, job alerts naming a
company the candidate applied to, an offer containing the word
"unfortunately").
**Setup:** no `OPENAI_API_KEY` set, so this measures the deterministic
rule-based classifier - the production fallback and the baseline the LLM will
be compared against. Caching disabled, so every email is actually classified.

### Prefilter

| Measure | Value |
|---|---:|
| Emails evaluated | 200 |
| Removed before any model call | 19 (9.5 %) |
| Agreement with the labels | 97.5 % |
| Job-related mail dropped by mistake | 0 |

### Classification

| Measure | Value |
|---|---:|
| End-to-end accuracy (prefilter + classifier) | **96.5 %** (193/200) |
| Macro F1 across all ten categories | **0.954** |
| Classifier alone, every email classified | 95.5 %, macro F1 0.941 |
| Accuracy on the 89 tricky cases | **96.6 %** |
| Latency per email | < 1 ms (string matching only) |

Per-category F1: rejection 0.968, confirmation 1.000, assessment 1.000,
interview scheduled 1.000, recruiter outreach 1.000, position cancelled 1.000,
survey/account 1.000, not-job-related 0.923, interview request 0.875,
offer 0.769.

### The review queue

| Measure | Value |
|---|---:|
| Mean confidence | 0.787 |
| Routed to human review (confidence < 0.7) | 8 of 200 (4.0 %) |
| Classifier errors caught by that routing | **7 of 7** |
| Errors that passed as confident | **0** |

Every mistake the classifier made landed in the review queue rather than
silently changing an application's status. That is the property the threshold
exists for.

**Read this honestly:** the rule phrase lists were iterated against this set,
so these are in-sample numbers and an upper bound for the rule path. The two
remaining failure shapes are real and documented - interview invitations
phrased only as "next steps … interview stage" (4 cases) and an offer whose
body contains "unfortunately" (3 cases) - and both are exactly where the LLM
path is expected to help. The LLM run on the same set, and the live-accuracy
figure computed from user corrections, are the honest counterparts.

Reproduce:

```bash
python3 scripts/generate_eval_set.py          # regenerates eval/emails.jsonl
cd backend && mvn -B -Peval test              # writes eval/results/classifier-rules.md
```

With a key set (`OPENAI_API_KEY`, `OPENAI_BASE_URL`, `LLM_MODEL`), the same
command measures the LLM path and writes `eval/results/classifier-llm.md`.

## Gmail sync (milestone 4)

**What:** the sync pipeline end to end - fetch, prefilter, classify, match,
update - against a stubbed Gmail that speaks the real wire format.
**Tool:** `GmailSyncIntegrationTest` (16 tests) with WireMock standing in for
the Gmail API and Google's token endpoint, over real PostgreSQL and Redis.
**Setup:** no LLM key, so the deterministic classifier runs; no Google account
needed.

| Property verified | How |
|---|---|
| Bodies are never stored | asserted on every stored row after a sync |
| A message is processed at most once | a second full sync over the same mailbox adds 0 rows |
| The history cursor advances only on success | cursor read before listing, asserted after |
| Incremental sync uses `history.list` | asserted by request count against the stub |
| A transient 429 is retried | flaky stub answers 429 then 200; the message still lands |
| One broken message does not abort the run | 3 messages, middle one 500s, other 2 processed |
| A revoked grant stops syncing and asks to reconnect | token endpoint returns `invalid_grant` |
| The stored refresh token is unreadable | ciphertext asserted not to contain the token |
| Disconnecting keeps the applications already found | counted before and after |

Token encryption is covered separately by `TokenEncryptionServiceTest` (10
tests): round trip, fresh nonce per encryption, tamper detection, wrong key
rejected, and refusal to store anything when no key is configured.

Reproduce:

```bash
cd backend && mvn -B test -Dtest='GmailSyncIntegrationTest,TokenEncryptionServiceTest'
```

## Matcher evaluation - duplicate detection (milestone 5)

**What:** does the matcher put two emails about one role on one application,
and two emails about different roles on two?
**Tool:** `MatcherEvaluationTest` (`-Peval`) over `eval/matcher_pairs.jsonl` -
100 hand-written labeled pairs (54 same, 46 different), covering ATS spelling
variants, abbreviations, legal suffixes, requisition ids that agree and
disagree, two seasons of one role, and two mobile platforms of one role.
**Positive class:** "these two are the same application".

| Measure | Value at the configured threshold (0.60) |
|---|---:|
| Precision | **1.000** |
| Recall | **1.000** |
| F1 | **1.000** |
| False merges (the expensive error) | **0** |
| Missed duplicates | **0** |

### Why the threshold is 0.60

The runner sweeps every candidate value over the same pairs and prints the
table. The shape of it is the justification:

| Threshold | Precision | Recall | False merges | Missed duplicates |
|---:|---:|---:|---:|---:|
| 0.30-0.40 | 0.771 | 1.000 | 16 | 0 |
| 0.45-0.50 | 0.783 | 1.000 | 15 | 0 |
| **0.55-0.60** | **1.000** | **1.000** | **0** | **0** |
| 0.65 | 1.000 | 0.981 | 0 | 1 |
| 0.70-0.75 | 1.000 | 0.926 | 0 | 4 |
| 0.80-0.90 | 1.000 | 0.870 | 0 | 7 |

Below 0.55 precision collapses - roles that share two words get merged.
Above 0.65 recall decays. 0.60 sits in the middle of the stable band rather
than on its edge, so small changes in wording do not move the behaviour.
(The sweep numbers above 0.60 shifted slightly after the normalization fixes
described below; the table printed by the current run is in
`eval/results/matcher.md`.)

**Read this honestly:** the pairs are hand-written by me, and three
normalization rules were added *after* seeing which pairs failed - folding
"back end"/"backend", keeping bracketed platform tags like "(iOS)" instead of
stripping all brackets, and treating a stated season or platform that
disagrees as a conflict. Those are real-world rules rather than lookups for
specific test rows, but a perfect score on a set used to find them is an
in-sample result. The number to trust in production is the live-accuracy
figure built from real corrections.

Reproduce:

```bash
python3 scripts/generate_matcher_pairs.py
cd backend && mvn -B -Peval test        # writes eval/results/matcher.md
```

## Fit scorer evaluation (milestone 6)

**What:** does the scorer put a resume/posting pair in the band a person
would, and does it ever suggest something dishonest?
**Tool:** `FitScorerEvaluationTest` (`-Peval`) over `eval/fit_pairs.jsonl` -
20 hand-labeled pairs built from five resume profiles (backend, data, ML,
frontend, first-year) and eight postings, including two with hard eligibility
blocks and one that states no requirements at all.
**Setup:** no API key, so postings are read by the heuristic parser and the
experience component uses the deterministic fallback. Bands were assigned by
reading each pair before the scorer was run.

| Measure | Value |
|---|---:|
| Exact band agreement | **13/20 (65 %)** |
| Within one band | **20/20 (100 %)** |
| More than one band out | **0** |
| Suggestions that would have the user claim something unevidenced | **0 of all suggestions across 20 pairs** |

The honesty check is the one that matters most. Every suggestion from every
pair is scanned: a suggestion may never tell the user to add a skill, a
technology or a number their resume does not already show. Gaps produce
learning advice ("build something small with it before applying"), never
"add this to your resume". This is asserted, not reviewed by hand.

### What the evaluation changed

Three real defects were found by running it, not by reading the code:

1. **Eligibility data never reached the scorer.** The resume JSON used
   snake_case keys (`expected_graduation`, `work_authorization`) while the
   record expected camelCase, so every graduation and work-authorisation
   check silently returned "unknown". A posting requiring 2026 graduation
   scored a 2027 graduate at 84.
2. **An unreadable posting scored highly.** With no skills extractable, the
   weights redistributed onto experience and eligibility, and a posting
   saying only "bright, motivated students" scored 75. There is now a cap of
   50 for postings with no readable skills, and the response says why.
3. **The no-model experience estimate was miscalibrated.** Raw word overlap
   was used directly, so a 6 % overlap read as "6 % relevant" and pushed whole
   scores a band low. It now maps into 0.30-0.80, keeping the ordering while
   bounding a weak proxy's influence.

**Read this honestly:** 20 pairs is a small set that I wrote and labeled
myself, and the remaining seven disagreements are all one band low, which
suggests the heuristic experience component is still conservative. The LLM
path should improve exactly that component, and has not yet been measured -
it needs an API key.

Reproduce:

```bash
python3 scripts/generate_fit_pairs.py
cd backend && mvn -B -Peval test     # writes eval/results/fit-scorer.md
```

## Load testing (milestone 9)

**What:** latency and throughput on the three endpoints a signed-in user hits
constantly - the dashboard aggregation, the applications table, and a
repeated fit check that should hit the posting cache.
**Tool:** k6 v2.4.0, `k6/load-test.js`. Each virtual user performs the real
browser handshake (CSRF cookie, then demo sign-in) and keeps its own cookie
jar.
**Setup:** full Docker Compose stack (PostgreSQL 16, Redis 7, Spring Boot,
Next.js) on the Apple Silicon machine described at the top, with the load
generator on the same machine. 30 seconds per level, after a 15-second
warm-up run that is discarded - without it the first measured level carried
JIT compilation and connection-pool fill and looked *worse* than the next one
up.

| Concurrent users | Requests/sec | Errors | Dashboard p50 / p95 / p99 | Applications p50 / p95 / p99 | Fit check p50 / p95 / p99 |
|---:|---:|---:|---|---|---|
| 50 | **352.9** | **0.00 %** | 104 / 182 / 237 ms | 52 / 114 / 134 ms | 9 / 27 / 68 ms |
| 100 | **707.9** | **0.00 %** | 53 / 327 / 522 ms | 16 / 251 / 449 ms | 9 / 90 / 166 ms |
| 250 | **897.6** | **0.00 %** | 246 / 615 / 765 ms | 198 / 555 / 696 ms | 152 / 491 / 636 ms |

Totals across the three levels: 60,143 HTTP requests, **zero failed**, zero
business errors (a response that was not what the endpoint should return).

Notes on reading this honestly:

- **The load generator shares the machine with the stack.** These numbers
  describe the application's behaviour under concurrency, not what a
  dedicated server would do.
- **Every virtual user signs in as the same demo account**, so this is also a
  test of per-user rate limiting and cache sharing - a harsher case than real
  traffic, where load is spread over distinct users.
- The fit check reuses one posting, so it measures the cached path
  deliberately: parsing a fresh posting involves a model call, which is not a
  latency this application controls.

Reproduce:

```bash
docker compose up -d --build
BASE_URL=http://localhost:8080 ./scripts/run-load-tests.sh
```

## Sync throughput (milestone 9)

**What:** how fast the sync pipeline ingests mail - fetch, prefilter,
classify, match, write.
**Tool:** `SyncThroughputTest` (`-Peval`), 300 messages from a WireMock Gmail
over a real PostgreSQL.
**Setup:** no API key, so the deterministic classifier runs; Gmail is local,
so no network latency is included. Both facts are deliberate: the point is to
measure the work this application does, which is the part its design
controls.

| Measure | Value |
|---|---:|
| Messages processed | 300 |
| Wall-clock time | 4.63 s |
| Throughput | **3,888 emails/minute** |
| Per message | 15.4 ms |
| Dropped by the prefilter | 25 % of the set (by construction) |

Reproduce:

```bash
cd backend && mvn -B -Peval test -Dtest=SyncThroughputTest
```

## Lighthouse (milestone 9)

**What:** performance and accessibility of every main page, including the
ones behind a session.
**Tool:** Lighthouse 12 driving a real Chromium, desktop preset.
**Setup:** `frontend/scripts/run-lighthouse.mjs` signs in to the demo with
Playwright and then points Lighthouse at that same browser over the DevTools
protocol, so the audited pages are the real signed-in pages rather than a
redirect to the landing screen.

| Page | Performance | Accessibility | Best practices | SEO | FCP | LCP |
|---|---:|---:|---:|---:|---:|---:|
| Landing | **100** | **100** | **100** | 100 | 0.2 s | 0.2 s |
| Dashboard | **100** | **100** | **100** | 100 | 0.2 s | 0.2 s |
| Applications | **100** | **100** | 96 | 100 | 0.2 s | 0.2 s |
| Fit checker | **100** | **100** | **100** | 100 | 0.2 s | 0.2 s |

The single deduction is a Chrome DevTools "Content Security Policy" inspector
issue on the applications page: the frontend CSP allows `'unsafe-inline'` for
scripts, which Next.js needs unless every response carries a per-request
nonce. It is listed under Known limitations rather than papered over.

Reproduce:

```bash
cd frontend && BASE_URL=http://localhost:3000 npm run lighthouse
```

_Still to be measured: LLM classifier accuracy and LLM-assisted fit scoring.
Both need an API key - see docs/llm-setup.md. The deterministic paths they
fall back to are measured above._

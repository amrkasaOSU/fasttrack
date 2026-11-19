# Engineering decisions

Every entry: what was decided, why, and what the alternative was. Decisions
that deviate from the original spec are marked **[deviation]**.

---

## D-001 - Modular monolith, not microservices
**Decision:** One Spring Boot application split into packages by domain
(`account`, `email`, `classify`, `application`, `resume`, `fit`, `insight`),
each with its own service layer and no cross-package repository access.
**Why:** A single developer, a single database, and a $0 hosting budget. A
monolith deploys as one container on one free-tier VM; microservices would
need a service mesh, multiple runtimes, and more RAM than any free tier gives.
Package boundaries keep the option of splitting later.
**Alternative rejected:** Separate sync/classification services communicating
over a queue - more operationally expensive with no benefit at this scale.

## D-002 - Session cookies in Redis, not JWT
**Decision:** Spring Session backed by Redis; the browser holds an opaque
`SESSION` cookie (HttpOnly, SameSite=Lax, Secure in production).
**Why:** Sessions are revocable server-side - needed for "Disconnect Gmail"
and "Delete all my data", which must invalidate access immediately. A JWT
stays valid until it expires. Redis is already required for the LLM cache and
rate limiting, so this adds no new infrastructure.
**Alternative rejected:** Stateless JWT in localStorage - not revocable and
XSS-readable.

## D-003 - LLM access behind a provider interface, OpenAI-compatible wire format
**Decision:** `LlmProvider` interface with a single HTTP implementation
speaking the OpenAI `/chat/completions` schema, configured by
`OPENAI_BASE_URL`, `OPENAI_API_KEY`, `LLM_MODEL`.
**Why:** Groq's free tier, Ollama, OpenRouter, llama.cpp and vLLM all speak
this format, so the provider can be changed with an environment variable and
the test suite can point at WireMock. No vendor SDK is on the classpath.
**Alternative rejected:** An official vendor SDK - lock-in, and it makes the
"runs with no LLM key" requirement harder to honour.

## D-004 - Java 21 toolchain on a JDK 25 host
**Decision:** The build targets Java 21 (`maven.compiler.release=21`). The
machine this was built on ships Temurin 25, so `openjdk@21` was installed via
Homebrew and pinned through a Maven toolchain / `JAVA_HOME`. CI uses
`temurin` 21.
**Why:** The spec fixes Java 21 (the current LTS that every free host
supports). Compiling with `--release 21` guarantees no Java 22+ API leaks in.

## D-005 - Colima instead of Docker Desktop **[deviation]**
**Decision:** The development machine had no container runtime. Colima
(open source, Apache-2.0) plus the Docker CLI and Compose plugin were
installed through Homebrew rather than Docker Desktop.
**Why:** Docker Desktop requires a paid licence for some commercial use and a
privileged install; Colima runs a lightweight VM with the same Docker API, so
`docker compose` and Testcontainers work unchanged and the cost stays $0.
**Impact:** None on the committed project - `docker-compose.yml` and
Testcontainers are runtime-identical. `docs/deployment.md` documents both.

## D-006 - PostgreSQL 16 + Flyway, no Hibernate schema generation
**Decision:** `spring.jpa.hibernate.ddl-auto=validate`; every schema change is
a numbered Flyway migration under `backend/src/main/resources/db/migration`.
**Why:** The schema is the contract. Flyway makes it reviewable, repeatable in
CI and in Testcontainers, and safe to deploy. `validate` fails fast when an
entity and the schema disagree.

## D-007 - Demo mode is a real user, not a mock layer
**Decision:** The demo account is a normal row in `users` with
`is_demo = true`, seeded with synthetic emails and applications through the
same services real data flows through. `POST /api/auth/demo` starts a session
as that user; the session is marked read-only for destructive operations.
**Why:** A mock layer would mean two code paths and a demo that proves
nothing. A seeded user exercises the real queries, the real state machine and
the real dashboard aggregations - which is also what makes the demo useful to
a recruiter.

## D-008 - Project directory name contains a trailing space
**Decision:** The working directory is `~/AI Intership Tracker ` (trailing
space, as created). Scripts quote all paths; nothing in the repo depends on
the folder name.
**Why:** Renaming the directory would break the running session's working
directory. The git repository root is portable regardless of folder name.

## D-009 - 0.7 confidence threshold for the review queue
**Decision:** a classification below 0.7 confidence does not change an
application's status on its own; it goes to the Needs review queue.
**Why:** measured. On the 200-email evaluation set every one of the classifier's
seven mistakes carried confidence below the threshold, and only one correct
result was flagged unnecessarily - 4% of the set routed for review caught 100%
of the errors (RESULTS.md). A higher bar would add review work for no gain; a
lower one would let the known failure shapes through silently.

## D-010 - The rule-based classifier is maintained, not vestigial
**Decision:** the deterministic classifier is a first-class component: it is
the fallback whenever the model is absent, unreachable or invalid, and it is
the published baseline the LLM is measured against.
**Why:** the product must work with no API key at all, and "the LLM is better"
is only a claim if there is something to compare it with. Keeping the baseline
honest also makes the LLM's contribution measurable rather than assumed.
**Cost:** phrase lists need occasional maintenance. That is accepted.

## D-011 - Prompt files, not string literals **
**Decision:** prompts live in `prompts/<name>_v<N>.txt` at the repository
root, copied onto the classpath by Maven. The version string is stored on every
classification and forms part of the cache key.
**Why:** a prompt change is a behaviour change, so it should appear in a diff
and in review. Including the version in the cache key stops results produced by
two different prompts from being mixed, which would quietly corrupt any
evaluation run after a prompt edit.

## D-012 - Evaluation set is synthetic and generated, not collected
**Decision:** the 200-email evaluation set is generated from templates by
`scripts/generate_eval_set.py` with a fixed seed.
**Why:** real application email cannot be committed - it contains personal data
belonging to other people as well as to me (docs/privacy.md). A seeded
generator gives a set that is reproducible on any machine, reviewable in a
diff, and safe to publish.
**Known weakness, stated plainly:** templated text flatters a phrase-matching
classifier, and the phrase lists were iterated against this set, so the
rule-based numbers are in-sample and are an upper bound. The live-accuracy
metric computed from real user corrections is the honest counterpart, and the
LLM path is measured on the same set without tuning.

## D-013 - Similarity threshold 0.60, chosen from the sweep
**Decision:** two titles at the same company are the same application when
their normalized token overlap (Jaccard) is at least 0.60; between 0.45 and
0.60 they are attached but flagged for review; below that they are separate.
**Why:** measured, not guessed. `MatcherEvaluationTest` scores every candidate
threshold over 100 labeled pairs and prints the table (RESULTS.md). Precision
collapses below 0.55 (16 false merges at 0.40) and recall decays above 0.65.
0.60 is the middle of the stable band, so wording changes do not flip
behaviour.
**Why Jaccard rather than edit distance:** job titles differ by whole words
("backend" vs "data"), not characters. Edit distance would score "data" and
"date" as nearly identical and a reordered title as distant.
**Why precision is favoured over recall:** a false merge silently folds two
real applications into one and loses the history of one of them; a missed
duplicate shows the user two rows they can merge in a click.

## D-014 - A conflicting season or platform beats title similarity
**Decision:** if both titles state a season (Summer vs Fall) or a mobile
platform (iOS vs Android) and they disagree, the applications are different -
even when the rest of the title is identical.
**Why:** normalization strips seasons and bracketed asides so that "SWE Intern
- Summer 2027" and "SWE Intern" match, which is right. But that stripping made
"Mobile Engineer Intern (iOS)" and "(Android)" identical, which merged two real
postings in the evaluation. Checking the raw titles for a *stated and
disagreeing* qualifier keeps both behaviours.

## D-015 - Live accuracy is reported with its caveat attached
**Decision:** the "live accuracy" figure is the share of classifications the
user has not corrected, and the API returns a `caveat` string saying so, which
the UI displays.
**Why:** the obvious framing - corrections divided by classifications - is
flattering nonsense on its own, because emails nobody reviewed count as
correct. Shipping the number without the caveat would be the kind of metric
that looks good on a dashboard and means nothing. The offline evaluation
measures accuracy properly; this measures disagreement, and says so.

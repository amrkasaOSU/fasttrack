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

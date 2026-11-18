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

| Metric | Milestone 1 | Milestone 2 |
|---|---|---|
| Tests passing | 52 | 190 |
| Test classes | 6 | 16 |
| Line coverage | 92.0 % (391/425) | 92.3 % (1158/1254) |
| Branch coverage | 81.4 % (57/70) | 76.7 % (355/463) |
| Instruction coverage | 92.1 % | 92.9 % (6129/6597) |
| Method coverage | 87.2 % | 89.7 % (315/351) |
| Class coverage | 100 % | 100 % (59/59) |
| Coverage gate | 80 % lines - passes | 80 % lines - passes |

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

_Still to be measured in later milestones: classifier accuracy and per-category
F1 (M3), matcher precision/recall (M5), fit-band agreement (M6), k6 load
results and Lighthouse scores (M9)._

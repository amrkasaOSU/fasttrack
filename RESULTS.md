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
## Milestone 1 - test suite and coverage

**What:** backend unit + integration tests, and line coverage enforced by the
build.
**Tool:** JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL 16, Redis 7),
JaCoCo 0.8.16.
**Setup:** `mvn verify` on the machine described above; containers started by
Testcontainers, no stubs for the database or the cache.

| Metric | Value |
|---|---|
| Tests | 52 passing, 0 failing, 0 skipped |
| Line coverage | 92.0 % (391 / 425) |
| Branch coverage | 81.4 % (57 / 70) |
| Instruction coverage | 92.1 % (1641 / 1782) |
| Method coverage | 87.2 % (164 / 188) |
| Class coverage | 100 % (33 / 33) |
| Coverage gate | 80 % lines, enforced in `mvn verify` (passes) |

Reproduce:

```bash
make verify && python3 scripts/coverage_summary.py
```

(`make` sets `DOCKER_HOST` for Colima automatically; on Docker Desktop or
Linux, `cd backend && mvn verify` is equivalent.)

_Coverage is re-measured at every milestone; this row will move as the
codebase grows._

# Sync throughput

Generated 2026-11-19T06:53:05.016484Z

| Setting | Value |
|---|---|
| Messages | 300 |
| Gmail | local WireMock stub (no network latency) |
| Classifier | deterministic path (no API key set) |
| Database | PostgreSQL 16 in Testcontainers |
| Command | `mvn -Peval test -Dtest=SyncThroughputTest` |

| Measure | Value |
|---|---:|
| Wall-clock time | 3.70 s |
| Throughput | **4864 emails/minute** |
| Per message | 12.3 ms |
| Kept by the prefilter | 225 |
| Dropped by the prefilter | 75 (25%) |
| Applications created | 225 |

Each message is fetched, prefiltered, classified, matched to an
application and written in its own transaction. The stubbed Gmail
removes network time on purpose: what is being measured is the work
this application does, which is the part its design controls.

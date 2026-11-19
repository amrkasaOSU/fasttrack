# Fit scorer evaluation

Generated 2026-11-19T06:53:05.072665Z

| Setting | Value |
|---|---|
| Dataset | `eval/fit_pairs.jsonl`, 20 hand-labeled resume/posting pairs |
| Posting parsing | heuristic (no API key needed) |
| Experience component | deterministic word-overlap fallback |
| Weights | 50% required skills, 20% preferred skills, 15% experience relevance, 15% eligibility. A hard eligibility mismatch caps the total at 40; a posting with no readable skills caps it at 50. |
| Command | `mvn -Peval test` |

## Band agreement

- Exact band: **13/20 (65%)**
- Within one band: **20/20 (100%)**

| Pair | Expected | Score | Band | Required | Preferred | Experience | Agreement |
|---|---|---:|---|---:|---:|---:|---|
| f01 | STRONG | 93 | STRONG | 100% | 80% | 0.80 | match |
| f02 | GOOD | 65 | GOOD | 67% | 50% | 0.43 | match |
| f03 | GOOD | 46 | STRETCH | 50% | 0% | 0.37 | one band |
| f04 | STRETCH | 28 | LONG_SHOT | 25% | 50% | 0.36 | one band |
| f05 | LONG_SHOT | 27 | LONG_SHOT | 0% | 0% | 0.41 | match |
| f06 | GOOD | 49 | STRETCH | 50% | 0% | 0.58 | one band |
| f07 | STRETCH | 40 | STRETCH | 100% | 100% | 0.61 | match |
| f08 | STRONG | 86 | STRONG | 100% | 50% | 0.74 | match |
| f09 | STRETCH | 53 | STRETCH | 67% | 0% | 0.30 | match |
| f10 | GOOD | 40 | STRETCH | 50% | 50% | 0.66 | one band |
| f11 | LONG_SHOT | 21 | LONG_SHOT | 0% | 0% | 0.37 | match |
| f12 | STRONG | 92 | STRONG | 100% | 100% | 0.45 | match |
| f13 | LONG_SHOT | 36 | STRETCH | 33% | 0% | 0.30 | one band |
| f14 | LONG_SHOT | 20 | LONG_SHOT | 0% | 0% | 0.36 | match |
| f15 | GOOD | 40 | STRETCH | 100% | 50% | 0.72 | one band |
| f16 | STRETCH | 40 | STRETCH | 50% | 100% | 0.30 | match |
| f17 | STRETCH | 57 | GOOD | 67% | 20% | 0.30 | one band |
| f18 | GOOD | 56 | GOOD | 67% | 0% | 0.49 | match |
| f19 | STRETCH | 40 | STRETCH | 67% | 0% | 0.30 | match |
| f20 | STRETCH | 50 | STRETCH | 0% | 0% | 0.50 | match |

## Pairs more than one band out

None.

## Honesty check

Every suggestion across all 20 pairs was checked against one rule: a suggestion may never tell the user to claim a skill, technology or number their resume does not already evidence. Suggestions about genuine gaps must propose learning, not listing.

**No violations.**

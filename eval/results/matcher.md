# Matcher evaluation - duplicate detection

Generated 2026-11-19T03:01:01.759064Z

| Setting | Value |
|---|---|
| Dataset | `eval/matcher_pairs.jsonl`, 100 labeled pairs |
| Positive class | "these two are the same application" |
| Threshold in production | 0.6 |
| Command | `mvn -Peval test` |

## At the configured threshold

| Measure | Value |
|---|---:|
| Precision | **1.000** |
| Recall | **1.000** |
| F1 | **1.000** |
| Accuracy | **100.0%** |
| False merges (worst error) | **0** |
| Missed duplicates | 0 |

## Threshold sweep

Every candidate value scored against the same 100 pairs. A false positive merges two real applications and loses one; a false negative shows a duplicate the user can merge in one click. The chosen value is the one that removes false merges while keeping recall high.

| Threshold | Precision | Recall | F1 | False merges | Missed duplicates |
|---:|---:|---:|---:|---:|---:|
| 0.30 | 0.771 | 1.000 | 0.871 | 16 | 0 |
| 0.35 | 0.771 | 1.000 | 0.871 | 16 | 0 |
| 0.40 | 0.771 | 1.000 | 0.871 | 16 | 0 |
| 0.45 | 0.783 | 1.000 | 0.878 | 15 | 0 |
| 0.50 | 0.783 | 1.000 | 0.878 | 15 | 0 |
| 0.55 | 1.000 | 1.000 | 1.000 | 0 | 0 |
| 0.60 **(in use)** | 1.000 | 1.000 | 1.000 | 0 | 0 |
| 0.65 | 1.000 | 0.981 | 0.991 | 0 | 1 |
| 0.70 | 1.000 | 0.926 | 0.962 | 0 | 4 |
| 0.75 | 1.000 | 0.926 | 0.962 | 0 | 4 |
| 0.80 | 1.000 | 0.870 | 0.931 | 0 | 7 |
| 0.85 | 1.000 | 0.870 | 0.931 | 0 | 7 |
| 0.90 | 1.000 | 0.870 | 0.931 | 0 | 7 |

Best F1 in this sweep: 1.000 at threshold 0.55.

## Every disagreement at the configured threshold

None.

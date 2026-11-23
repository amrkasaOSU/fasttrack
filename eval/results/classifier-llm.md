# Classifier evaluation - LLM path

Generated 2026-11-23T03:04:16.353142Z

| Setting | Value |
|---|---|
| Dataset | `eval/emails.jsonl`, 200 labeled synthetic emails |
| Method | LLM with rule-based fallback |
| Model | `openai/gpt-oss-120b` |
| Prompt | `prompts/classify_v1.txt` (version v1) |
| Cache | disabled for this run |
| Command | `mvn -Peval test -Dtest=ClassifierEvaluationTest` |

## Prefilter

| Measure | Value |
|---|---:|
| Emails in set | 200 |
| Kept for classification | 181 |
| Removed before any LLM call | 19 (9.5%) |
| Prefilter agreement with labels | 97.5% |
| Job-related mail dropped by mistake | 0 |

Disagreements:

- e022 kept but should drop (strong_signal)
- e045 kept but should drop (strong_signal)
- e060 kept but should drop (strong_signal)
- e146 kept but should drop (strong_signal)
- e154 kept but should drop (strong_signal)

## End-to-end accuracy (prefilter + classifier)

This is what production does: a dropped email is never classified.

- Overall accuracy: **98.5%** (197/200)
- Macro F1: **0.982**

| Category | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| APPLICATION_CONFIRMATION | 40 | 1.000 | 1.000 | 1.000 |
| REJECTION | 45 | 0.978 | 1.000 | 0.989 |
| ASSESSMENT_INVITE | 20 | 1.000 | 1.000 | 1.000 |
| INTERVIEW_REQUEST | 18 | 1.000 | 0.889 | 0.941 |
| INTERVIEW_SCHEDULED | 12 | 1.000 | 1.000 | 1.000 |
| OFFER | 8 | 1.000 | 0.875 | 0.933 |
| RECRUITER_OUTREACH | 15 | 1.000 | 1.000 | 1.000 |
| POSITION_CANCELLED | 7 | 1.000 | 1.000 | 1.000 |
| SURVEY_OR_ACCOUNT | 11 | 1.000 | 1.000 | 1.000 |
| NOT_JOB_RELATED | 24 | 0.923 | 1.000 | 0.960 |

### Confusion matrix

| actual \ predicted | CONF | REJ | ASMT | IVREQ | IVSCH | OFFER | OUTR | CANC | SURV | NJR |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **CONF** | 40 | . | . | . | . | . | . | . | . | . |
| **REJ** | . | 45 | . | . | . | . | . | . | . | . |
| **ASMT** | . | . | 20 | . | . | . | . | . | . | . |
| **IVREQ** | . | . | . | 16 | . | . | . | . | . | 2 |
| **IVSCH** | . | . | . | . | 12 | . | . | . | . | . |
| **OFFER** | . | 1 | . | . | . | 7 | . | . | . | . |
| **OUTR** | . | . | . | . | . | . | 15 | . | . | . |
| **CANC** | . | . | . | . | . | . | . | 7 | . | . |
| **SURV** | . | . | . | . | . | . | . | . | 11 | . |
| **NJR** | . | . | . | . | . | . | . | . | . | 24 |

## Classifier alone (every email classified)

- Overall accuracy: **98.5%**
- Macro F1: **0.982**
- Accuracy on the 89 emails marked tricky: **98.9%**
- Fell back to rules (invalid or unavailable): **38 of 200**

## Why calls fell back to the rule classifier

**38 of 200 classifications did not come from the model.** The figures above are therefore a blend: 162 from `openai/gpt-oss-120b` and 38 from the deterministic classifier. Treat the headline accuracy as the pipeline's, not the model's.

| Cause | Calls |
|---|---:|
| HTTP 429 (rate limited) | 38 |

200 HTTP calls were made in total (retries included).

## Confidence and the review queue

A result below 0.7 confidence is not acted on silently - it goes to the Needs review queue.

| Measure | Value |
|---|---:|
| Mean confidence | 0.944 |
| Sent to review | 4 of 200 (2.0%) |
| Errors caught by the review queue | 3 of 3 |
| Errors that passed as confident | 0 of 3 |

## Latency

Measured client-side, per email, with the cache disabled.

| Measure | Value |
|---|---:|
| Mean | 2394 ms |
| p50 | 1220 ms |
| p95 | 5373 ms |
| p99 | 6071 ms |

## Every disagreement

Each line is one email the pipeline got wrong, with the label, the prediction and why the case is in the set.

```
e187 | expected INTERVIEW_REQUEST, got NOT_JOB_RELATED | "Next steps: interview for Data Engineer Intern - Summer 2027" | interview request through the ATS
e188 | expected INTERVIEW_REQUEST, got NOT_JOB_RELATED | "Next steps: interview for Software Engineering Intern (Summer 2027)" | interview request through the ATS
e194 | expected OFFER, got REJECTION | "Action required: review your offer" | tricky: an offer that contains the word 'unfortunately'
```

## How to read this

The emails are synthetic and written by the generator in `scripts/generate_eval_set.py`; they imitate the templates real ATS platforms send, including the cases that are genuinely hard (polite rejections, confirmations that mention interviews, job alerts that name a company you applied to). Because the text is templated, these numbers are an upper bound on real-world performance for the rule-based path, which keys on exactly those phrases. The live-accuracy figure computed from user corrections is the honest counterpart.

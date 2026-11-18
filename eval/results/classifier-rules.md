# Classifier evaluation - rule-based path

Generated 2026-11-18T01:48:35.931402Z

| Setting | Value |
|---|---|
| Dataset | `eval/emails.jsonl`, 200 labeled synthetic emails |
| Method | rule-based only (no API key set) |
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

- Overall accuracy: **96.5%** (193/200)
- Macro F1: **0.954**

| Category | Support | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| APPLICATION_CONFIRMATION | 40 | 1.000 | 1.000 | 1.000 |
| REJECTION | 45 | 0.938 | 1.000 | 0.968 |
| ASSESSMENT_INVITE | 20 | 1.000 | 1.000 | 1.000 |
| INTERVIEW_REQUEST | 18 | 1.000 | 0.778 | 0.875 |
| INTERVIEW_SCHEDULED | 12 | 1.000 | 1.000 | 1.000 |
| OFFER | 8 | 1.000 | 0.625 | 0.769 |
| RECRUITER_OUTREACH | 15 | 1.000 | 1.000 | 1.000 |
| POSITION_CANCELLED | 7 | 1.000 | 1.000 | 1.000 |
| SURVEY_OR_ACCOUNT | 11 | 1.000 | 1.000 | 1.000 |
| NOT_JOB_RELATED | 24 | 0.857 | 1.000 | 0.923 |

### Confusion matrix

| actual \ predicted | CONF | REJ | ASMT | IVREQ | IVSCH | OFFER | OUTR | CANC | SURV | NJR |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **CONF** | 40 | . | . | . | . | . | . | . | . | . |
| **REJ** | . | 45 | . | . | . | . | . | . | . | . |
| **ASMT** | . | . | 20 | . | . | . | . | . | . | . |
| **IVREQ** | . | . | . | 14 | . | . | . | . | . | 4 |
| **IVSCH** | . | . | . | . | 12 | . | . | . | . | . |
| **OFFER** | . | 3 | . | . | . | 5 | . | . | . | . |
| **OUTR** | . | . | . | . | . | . | 15 | . | . | . |
| **CANC** | . | . | . | . | . | . | . | 7 | . | . |
| **SURV** | . | . | . | . | . | . | . | . | 11 | . |
| **NJR** | . | . | . | . | . | . | . | . | . | 24 |

## Classifier alone (every email classified)

- Overall accuracy: **95.5%**
- Macro F1: **0.941**
- Accuracy on the 89 emails marked tricky: **96.6%**

## Confidence and the review queue

A result below 0.7 confidence is not acted on silently - it goes to the Needs review queue.

| Measure | Value |
|---|---:|
| Mean confidence | 0.787 |
| Sent to review | 8 of 200 (4.0%) |
| Errors caught by the review queue | 7 of 7 |
| Errors that passed as confident | 0 of 7 |

## Latency

Measured client-side, per email, with the cache disabled.

| Measure | Value |
|---|---:|
| Mean | <1 ms |
| p50 | <1 ms |
| p95 | <1 ms |
| p99 | <1 ms |

The rule-based path does string matching only, so every measurement rounds to under a millisecond.

## Every disagreement

Each line is one email the pipeline got wrong, with the label, the prediction and why the case is in the set.

```
e009 | expected INTERVIEW_REQUEST, got NOT_JOB_RELATED | "Next steps: interview for Machine Learning Engineer Intern" | interview request through the ATS
e018 | expected INTERVIEW_REQUEST, got NOT_JOB_RELATED | "Next steps: interview for Security Engineer Intern - Summer 2027" | interview request through the ATS
e026 | expected OFFER, got REJECTION | "Action required: review your offer" | tricky: an offer that contains the word 'unfortunately'
e179 | expected OFFER, got REJECTION | "Action required: review your offer" | tricky: an offer that contains the word 'unfortunately'
e187 | expected INTERVIEW_REQUEST, got NOT_JOB_RELATED | "Next steps: interview for Data Engineer Intern - Summer 2027" | interview request through the ATS
e188 | expected INTERVIEW_REQUEST, got NOT_JOB_RELATED | "Next steps: interview for Software Engineering Intern (Summer 2027)" | interview request through the ATS
e194 | expected OFFER, got REJECTION | "Action required: review your offer" | tricky: an offer that contains the word 'unfortunately'
```

## How to read this

The emails are synthetic and written by the generator in `scripts/generate_eval_set.py`; they imitate the templates real ATS platforms send, including the cases that are genuinely hard (polite rejections, confirmations that mention interviews, job alerts that name a company you applied to). Because the text is templated, these numbers are an upper bound on real-world performance for the rule-based path, which keys on exactly those phrases. The live-accuracy figure computed from user corrections is the honest counterpart.

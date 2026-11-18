# AI: prompts, schemas, fallbacks, caching, evaluation

FastTrack uses a language model for three jobs: classifying an email,
structuring a resume, and structuring a job posting. This document covers the
classifier (milestone 3); the resume and posting parsers follow the same
pattern.

Everything here is designed around one assumption: **the model will sometimes
be wrong, slow, rate limited, or absent entirely.** None of those may break
the product.

## The provider abstraction

```java
public interface LlmProvider {
    boolean isConfigured();
    String model();
    LlmResponse complete(LlmRequest request);   // throws LlmUnavailableException
}
```

One implementation, `OpenAiCompatibleLlmProvider`, speaks the OpenAI
`/chat/completions` wire format over plain `java.net.http`. Groq's free tier,
Ollama, OpenRouter, llama.cpp and vLLM all accept it, so changing provider is
three environment variables and no code:

```bash
OPENAI_BASE_URL=https://api.groq.com/openai/v1   # or http://localhost:11434/v1
OPENAI_API_KEY=...                               # "ollama" for a local model
LLM_MODEL=llama-3.3-70b-versatile
```

No vendor SDK is on the classpath. Tests point the same client at WireMock.

Retries are limited to what retrying can fix - 408, 429 and 5xx - with
exponential backoff and jitter. A 400 or 401 fails immediately, because a
second identical request will fail the same way.

## The prompt

`prompts/classify_v1.txt`, copied onto the classpath by the build. It is a
file rather than a string literal so that a prompt change is a reviewable
diff, and the version (`v1`) is stored on every classification **and** forms
part of the cache key - results from two prompt versions are never mixed.

The prompt is written around the mistakes that matter:

- *Politeness is not acceptance.* "Thank you for your interest … we have
  decided to move forward with other candidates" is a rejection; the decision
  sentence outranks the greeting.
- *An email that mentions interviews is not an interview invitation* unless
  **this** candidate is being invited.
- *Never guess.* A field that is not stated is `null`.
- *Be honestly uncertain.* A confidence below 0.7 routes the email to a human
  review queue, so a low number is more useful than a confident wrong answer.

## The schema and its validation

The model must return exactly:

```json
{
  "category": "APPLICATION_CONFIRMATION | REJECTION | ASSESSMENT_INVITE | INTERVIEW_REQUEST | INTERVIEW_SCHEDULED | OFFER | RECRUITER_OUTREACH | POSITION_CANCELLED | SURVEY_OR_ACCOUNT | NOT_JOB_RELATED",
  "company": "string|null",
  "position_title": "string|null",
  "requisition_id": "string|null",
  "interview_datetime": "ISO-8601|null",
  "assessment_deadline": "ISO-8601|null",
  "platform": "string|null",
  "confidence": 0.0,
  "one_sentence_reason": "string"
}
```

Validation is deliberately uneven, because the consequences are uneven:

| Field | Rule | Why |
|---|---|---|
| `category` | must be one of the ten, else reject the whole reply | it drives a status change |
| `confidence` | must be a number in 0..1, else reject | it decides whether a human looks |
| dates | unparseable value becomes `null` | losing a date is better than discarding a good classification |
| strings | `"null"`, `"unknown"`, `"N/A"` and blanks become `null` | models say these instead of emitting null |
| formatting | markdown fences and surrounding prose are stripped | failing on a code fence would waste a retry |

## Degradation, in order

```
cache hit                       → result, 0 calls
no API key                      → rule-based result
valid JSON                      → result, cached
invalid JSON → retry once       → result, cached
invalid twice                   → rule-based result
provider unreachable / 429 / 5xx → rule-based result
```

A classification always comes back. The `method` field (`LLM`, `RULE`,
`MANUAL`) records how it was produced, and it is shown in the UI, so a user is
never misled about what judged their mail.

The rule-based classifier is not a stub. It matches ordered phrase sets, with
the ordering chosen around the costly failure - a decision sentence beats a
polite opening, and the weak signal "unfortunately" is consulted only after
the positive stages, so an offer that happens to contain the word is not read
as a rejection.

## The prefilter

Before any model call, a rule-based prefilter decides whether an email could
possibly concern an application the user made. It drops job alerts,
newsletters, networking notifications, event invitations and account plumbing;
it keeps anything from an ATS domain, anything with application language, and
anything carrying a decisive phrase ("your application was sent", "regret to
inform") even when it also looks like a job board.

This is the cheapest cost control in the system: an email the prefilter drops
is a model call that never happens.

## Caching

Classification results are cached in Redis under
`fasttrack:classify:<promptVersion>:<model>:<sha256 of sender+subject+body>`,
with a 30-day TTL. Re-running a sync, or receiving the same templated email
twice, costs nothing. Redis being unavailable is a cache miss, never an error.

## Evaluation

`eval/emails.jsonl` holds 200 labeled synthetic emails generated by
`scripts/generate_eval_set.py` (fixed seed, regenerable byte for byte). 89 of
them are marked *tricky* - the cases above, plus job alerts that name a company
the candidate really applied to, assessment invitations phrased as "next
steps", and an offer that contains the word "unfortunately".

Run it:

```bash
cd backend && mvn -Peval test
```

With no `OPENAI_API_KEY` set it measures the rule-based path; with a key set it
measures the LLM path against the same 200 emails and writes a separate
report. Reports land in `eval/results/`, and the measured numbers are copied
into [RESULTS.md](../RESULTS.md).

**An honest caveat.** The rule phrase lists were iterated against this
evaluation set, so the rule-based numbers are in-sample and should be read as
an upper bound. Two things keep that from being self-congratulatory: the LLM
path is measured on the same set without any such tuning, so the comparison is
fair in the direction that matters, and the app computes a *live accuracy*
figure from real user corrections (`email_corrections`), which is the number
that actually reflects production.

## What is sent to the model

Sender, subject, received date and the first 4,000 characters of the body -
truncated because the decision sentence is always near the top. Nothing else
leaves the process, and if `OPENAI_API_KEY` is unset, nothing leaves at all.
Pointing the base URL at a local Ollama keeps every byte on your own machine.
See [privacy.md](privacy.md).

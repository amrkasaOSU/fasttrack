# FastTrack

**An internship application tracker that reads your email so you don't have to.**

[![CI](https://github.com/OWNER/fasttrack/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/fasttrack/actions/workflows/ci.yml)

> Status: in development. Milestone 1 (scaffold, infrastructure, schema, auth)
> is in place. Screenshots, measured results and the full feature set arrive in
> later milestones - nothing in this README claims a number that has not been
> measured.

## Why I built this

I applied to more than 150 internships in one cycle and lost track of which
companies had replied, which assessments were about to expire, and which
rejections I had already received. A spreadsheet needs constant manual upkeep,
and the information was already sitting in my inbox.

## What it does

- Reads job-application email with **read-only** Gmail access - it can never
  send, delete, label or modify mail.
- Classifies each message (confirmation, rejection, assessment, interview,
  offer, recruiter outreach, ...) and updates the matching application.
- Keeps an append-only status history, so every status change links back to the
  email that caused it.
- Scores a resume against a job posting with a deterministic, explainable
  hybrid score and honest suggestions.

## Quick start

```bash
cp .env.example .env     # every variable is documented in the file
docker compose up --build
```

Then open <http://localhost:3000> and press **Try the demo**. Gmail credentials
and an LLM key are optional: without them the app runs and says which features
are unavailable.

## Documentation

| Document | What it covers |
|---|---|
| [DECISIONS.md](DECISIONS.md) | Every engineering decision and its alternative |
| [RESULTS.md](RESULTS.md) | Measured numbers and how to reproduce them |
| [CHANGELOG.md](CHANGELOG.md) | What changed, milestone by milestone |

More documents (architecture, database, api, ai, security, privacy,
google-setup, deployment) land with their milestones.

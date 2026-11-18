# Database

PostgreSQL 16. The schema is owned by Flyway migrations in
`backend/src/main/resources/db/migration`; Hibernate runs with
`ddl-auto=validate`, so a mismatch between an entity and the schema fails
start-up instead of silently altering a table.

## Conventions

- UUID primary keys (`gen_random_uuid()` default, assigned by the app).
- Every user-owned table carries `user_id` with an index and an
  `ON DELETE CASCADE` foreign key, so "delete all my data" is one statement.
- Enumerated values are `TEXT` plus a `CHECK` constraint: readable in `psql`,
  and a new value is a one-line migration.
- All timestamps are `timestamptz`, stored in UTC.

## Tables

| Table | Holds | Notable constraints |
|---|---|---|
| `users` | Accounts, including the demo user | unique `lower(email)`, unique `google_subject` |
| `gmail_accounts` | One connected mailbox per user, refresh token encrypted | unique `user_id` |
| `applications` | One company + one position | `ux_applications_req` makes a requisition id unique per company per user |
| `application_events` | Append-only status history | links to the email that caused the change |
| `email_messages` | Email metadata only, never bodies | `ux_email_user_gmail_id` makes sync idempotent |
| `email_classifications` | One structured result per email | confidence constrained to 0..1 |
| `email_corrections` | User disagreements with the classifier | source of the live-accuracy metric |
| `resumes` | Versioned resume text + parsed JSON | one active resume per user |
| `job_postings` | Pasted or fetched posting + parsed JSON | hashed for cache lookups |
| `fit_checks` | Component scores and the explanation payload | score constrained to 0..100 |
| `sync_runs` | One row per Gmail sync | counts for throughput and prefilter metrics |
| `llm_invocations` | One row per call that left the process | latency, cache hit, fallback flags |

## The two uniqueness rules that matter

1. **`(user_id, gmail_message_id)`** - a Gmail message is processed at most
   once per user. This is what makes re-running a sync safe.
2. **`(user_id, company_normalized, requisition_id)`** - the same requisition
   at the same company is the same application, which is matching rule 1.

## Status values

`applications.status` is constrained to the sixteen values of
`ApplicationStatus`. Terminal states (`REJECTED`, `POSITION_CANCELLED`,
`WITHDRAWN`, `OFFER_ACCEPTED`, `OFFER_DECLINED`) are only ever left by an
explicit user action - no automated path reopens them.

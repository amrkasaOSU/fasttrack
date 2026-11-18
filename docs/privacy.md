# Privacy

FastTrack reads a mailbox. That makes privacy a design constraint, not a
policy page. This document states exactly what the application can do, what it
keeps, and what it never keeps.

## What access FastTrack asks for

| Scope | Why |
|---|---|
| `openid`, `email`, `profile` | To create your account and show your name |
| `https://www.googleapis.com/auth/gmail.readonly` | To read message metadata and bodies while classifying them |

There is no scope here that can send, reply, delete, label, archive or modify
mail. The application has no code path that writes to Gmail - the Gmail client
is only ever used for `list` and `get`. The scope list is asserted by a test
(`GoogleOAuthConfigTest`), so it cannot drift silently.

## What is stored

For each email that passes the prefilter:

- Gmail message id and thread id (to avoid processing the same message twice)
- Sender address, sender display name and sender domain
- Subject
- A short snippet (the first ~200 characters Gmail itself returns)
- The received timestamp
- A content hash, used as the classification cache key
- The fields the classifier extracted: category, company, position,
  requisition id, interview or assessment date, platform, confidence

## What is never stored

- **Full message bodies.** Bodies are fetched, used in memory to build the
  classification prompt, and discarded when the request ends. There is no
  column for them, in any table.
- **Attachments.** They are never downloaded.
- **Access tokens.** Only the refresh token is persisted, encrypted.
- **Anything from an email the prefilter dropped**, beyond the counter used for
  the "LLM calls avoided" metric.

## How the refresh token is protected

The Gmail refresh token is encrypted with AES-256-GCM before it reaches the
database, using a key supplied in `TOKEN_ENCRYPTION_KEY` and held only in the
process environment. The ciphertext and its nonce are stored in separate
columns. Tokens are never logged: Spring Security's OAuth2 logger is pinned to
`WARN` for exactly this reason.

If `TOKEN_ENCRYPTION_KEY` is absent the application still starts, but Gmail
connection is reported as not configured and no token is ever written.

## What leaves the machine

- **To Google:** the OAuth2 exchange and Gmail read requests.
- **To the LLM provider:** the subject, sender and a truncated body of an email
  being classified, or the text of a resume or job posting being parsed. If no
  LLM key is configured, nothing leaves - the rule-based path runs instead.
  Pointing `OPENAI_BASE_URL` at a local Ollama instance keeps everything on
  your own machine.
- **Nothing else.** There is no analytics, no tracking, no third-party script
  in the frontend.

## Your controls

- **Disconnect Gmail** - deletes the stored refresh token and stops all syncing.
- **Delete all my data** - removes your user row; every table cascades from it,
  so applications, emails, classifications, resumes and fit checks go with it.
- **Export CSV** - your applications in a format you can keep.

## The demo account

The demo account contains generated data only: invented applications against
real company names, with no real email, no real person and nothing scraped. It
has no Gmail connection and cannot be given one.

# Changelog

All notable changes to FastTrack. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Milestone 1 - scaffold, infrastructure, auth, schema
- Repository scaffold: backend (Spring Boot 3 / Java 21 / Maven), frontend
  (Next.js App Router / TypeScript / Tailwind), docs, eval, prompts.
- Docker Compose for local development: PostgreSQL 16, Redis 7, backend,
  frontend.
- `.env.example` documenting every environment variable; no secrets in git.
- Flyway baseline migration: users, gmail accounts, emails, applications,
  application events, resumes, job postings, fit checks, corrections.
- Session authentication (Redis-backed) with demo login, CSRF protection and
  an ownership-checking authorization service.
- GitHub Actions CI: backend tests + JaCoCo, frontend typecheck/lint/build.

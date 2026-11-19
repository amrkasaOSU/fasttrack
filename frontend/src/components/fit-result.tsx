"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { BAND_LABELS, type FitCheckResult, type Suggestion } from "@/lib/fit";

const BAND_STYLES: Record<string, string> = {
  STRONG: "text-success border-success/40 bg-success/10",
  GOOD: "text-info border-info/40 bg-info/10",
  STRETCH: "text-warning border-warning/40 bg-warning/10",
  LONG_SHOT: "text-danger border-danger/40 bg-danger/10",
};

const SUGGESTION_LABELS: Record<Suggestion["kind"], string> = {
  SURFACE: "Surface it",
  REWORD: "Reword it",
  LEARN: "Gap",
  ELIGIBILITY: "Eligibility",
};

function percent(value: number) {
  return `${Math.round(value * 100)}%`;
}

/** The full breakdown of one fit check - score, components, gaps, advice. */
export function FitResult({ result }: { result: FitCheckResult }) {
  const { score } = result;

  return (
    <div className="space-y-6">
      <Card>
        <CardContent className="flex flex-wrap items-center gap-6 pt-6">
          <div
            className={cn(
              "flex h-28 w-28 shrink-0 flex-col items-center justify-center rounded-full border-4",
              BAND_STYLES[score.band],
            )}
          >
            <span className="text-4xl font-semibold tabular-nums">{score.overall}</span>
            <span className="text-xs">out of 100</span>
          </div>

          <div className="min-w-0 flex-1 space-y-1">
            <p className="text-xl font-semibold">{BAND_LABELS[score.band]}</p>
            {(result.postingTitle || result.postingCompany) && (
              <p className="text-fg-muted">
                {[result.postingTitle, result.postingCompany].filter(Boolean).join(" · ")}
              </p>
            )}
            {score.hardMismatch && (
              <p role="alert" className="text-sm font-medium text-danger">
                A hard requirement is not met, so the score is capped. See eligibility below.
              </p>
            )}
            {score.weightingNote && (
              <p className="text-sm text-fg-muted">{score.weightingNote}</p>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>How the score was reached</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <Component
            label="Required skills"
            weight="50%"
            value={score.requiredCoverage}
            detail={`${score.matchedRequired.length} of ${
              score.matchedRequired.length + score.missingRequired.length
            } matched`}
          />
          <Component
            label="Preferred skills"
            weight="20%"
            value={score.preferredCoverage}
            detail={`${score.matchedPreferred.length} of ${
              score.matchedPreferred.length + score.missingPreferred.length
            } matched`}
          />
          <Component
            label="Experience relevance"
            weight="15%"
            value={score.experienceScore}
            detail={score.experienceReason ?? ""}
          />
          <Component
            label="Eligibility"
            weight="15%"
            value={score.eligibilityScore}
            detail={score.eligibility.length === 0 ? "Nothing stated to check" : ""}
          />
          <div className="border-t border-border pt-3 text-sm text-fg-muted">
            ATS keyword coverage: <strong>{percent(score.atsKeywordCoverage)}</strong> of the
            posting&apos;s distinctive words appear in your resume. This is shown for information
            and is not part of the score.
          </div>
        </CardContent>
      </Card>

      <div className="grid gap-6 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Skills</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <SkillList title="Matched (required)" skills={score.matchedRequired} tone="good" />
            <SkillList title="Missing (required)" skills={score.missingRequired} tone="bad" />
            <SkillList title="Matched (preferred)" skills={score.matchedPreferred} tone="good" />
            <SkillList title="Missing (preferred)" skills={score.missingPreferred} tone="neutral" />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Eligibility</CardTitle>
          </CardHeader>
          <CardContent>
            {score.eligibility.length === 0 ? (
              <p className="text-sm text-fg-muted">
                This posting states no graduation, degree or work-authorisation requirement.
              </p>
            ) : (
              <ul className="space-y-3">
                {score.eligibility.map((check) => (
                  <li key={check.name} className="text-sm">
                    <span
                      className={cn(
                        "font-medium",
                        check.status === "FAIL" && "text-danger",
                        check.status === "PASS" && "text-success",
                        check.status === "UNKNOWN" && "text-warning",
                      )}
                    >
                      {check.name}: {check.status.toLowerCase()}
                      {check.hard && check.status === "FAIL" ? " (hard requirement)" : ""}
                    </span>
                    <p className="text-fg-muted">{check.detail}</p>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>What to do about it</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="mb-3 text-sm text-fg-muted">
            FastTrack only suggests surfacing or rewording things your resume already shows. It
            will never tell you to claim a skill you do not have.
          </p>
          <ul className="space-y-3">
            {score.suggestions.map((suggestion, index) => (
              <li key={`${suggestion.kind}-${index}`} className="text-sm">
                <span className="mr-2 rounded-full border border-border px-2 py-0.5 text-xs text-fg-muted">
                  {SUGGESTION_LABELS[suggestion.kind]}
                </span>
                {suggestion.text}
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}

function Component({
  label,
  weight,
  value,
  detail,
}: {
  label: string;
  weight: string;
  value: number;
  detail: string;
}) {
  return (
    <div className="space-y-1">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="font-medium">
          {label} <span className="text-fg-muted">({weight})</span>
        </span>
        <span className="tabular-nums">{percent(value)}</span>
      </div>
      <div
        className="h-2 w-full overflow-hidden rounded-full bg-surface-muted"
        role="img"
        aria-label={`${label}: ${percent(value)}`}
      >
        <div className="h-full rounded-full bg-brand" style={{ width: `${Math.round(value * 100)}%` }} />
      </div>
      {detail && <p className="text-xs text-fg-muted">{detail}</p>}
    </div>
  );
}

function SkillList({
  title,
  skills,
  tone,
}: {
  title: string;
  skills: string[];
  tone: "good" | "bad" | "neutral";
}) {
  if (skills.length === 0) return null;
  const styles = {
    good: "border-success/40 bg-success/10 text-success",
    bad: "border-danger/40 bg-danger/10 text-danger",
    neutral: "border-border bg-surface-muted text-fg-muted",
  }[tone];

  return (
    <div>
      <p className="mb-1 text-sm font-medium">{title}</p>
      <ul className="flex flex-wrap gap-1.5">
        {skills.map((skill) => (
          <li
            key={skill}
            className={cn("rounded-full border px-2.5 py-0.5 text-xs", styles)}
          >
            {skill}
          </li>
        ))}
      </ul>
    </div>
  );
}

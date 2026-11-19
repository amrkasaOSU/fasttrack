"use client";

import Link from "next/link";
import {
  FunnelChart,
  ResponseRateChart,
  WeeklyApplicationsChart,
} from "@/components/charts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { EmptyState, ErrorState, LoadingRows } from "@/components/ui/states";
import { formatDate, relativeDays } from "@/lib/format";
import { useInsights, type ActionItem } from "@/lib/insights";

function percent(value: number | null) {
  return value === null ? "—" : `${Math.round(value * 100)}%`;
}

const ACTION_LABELS: Record<ActionItem["kind"], string> = {
  ASSESSMENT_DEADLINE: "Assessment due",
  INTERVIEW: "Interview",
  ASSESSMENT_PENDING: "Assessment",
  NO_RESPONSE: "Follow up",
};

export default function DashboardPage() {
  const insights = useInsights();

  if (insights.isLoading) return <LoadingRows rows={6} />;
  if (insights.isError) {
    return (
      <ErrorState description="Could not load your dashboard." onRetry={() => insights.refetch()} />
    );
  }
  if (!insights.data) return null;

  const { totals, timing, actionItems, needsReviewCount } = insights.data;

  if (totals.applications === 0) {
    return (
      <div className="space-y-6">
        <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
        <EmptyState
          title="Nothing to show yet"
          description="Add an application by hand, import your existing tracker as CSV, or connect Gmail and let FastTrack find them."
          action={
            <div className="flex gap-2">
              <Button asChild>
                <Link href="/applications">Add an application</Link>
              </Button>
              <Button variant="secondary" asChild>
                <Link href="/settings">Connect Gmail</Link>
              </Button>
            </div>
          }
        />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
        <p className="text-sm text-fg-muted">
          {totals.applications} applications across {totals.companies} companies.
        </p>
      </div>

      <section aria-label="Totals" className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Applications" value={totals.applications} />
        <Stat label="Responses" value={totals.responses} sub={percent(totals.responseRate)} />
        <Stat label="No reply yet" value={totals.noResponse} />
        <Stat label="Rejections" value={totals.rejections} />
        <Stat label="Assessments" value={totals.assessments} />
        <Stat label="Interviews" value={totals.interviews} sub={percent(totals.interviewRate)} />
        <Stat label="Offers" value={totals.offers} />
        <Stat label="Still active" value={totals.active} />
      </section>

      {(actionItems.length > 0 || needsReviewCount > 0) && (
        <Card>
          <CardHeader>
            <CardTitle>Needs your attention</CardTitle>
          </CardHeader>
          <CardContent className="divide-y divide-border">
            {needsReviewCount > 0 && (
              <Link href="/review" className="flex items-center justify-between py-3">
                <span>
                  <span className="rounded-full border border-warning/40 bg-warning/10 px-2 py-0.5 text-xs text-warning">
                    Review
                  </span>{" "}
                  {needsReviewCount} item{needsReviewCount === 1 ? "" : "s"} the classifier was
                  unsure about
                </span>
                <span className="text-sm text-fg-muted">Open →</span>
              </Link>
            )}
            {actionItems.slice(0, 12).map((item) => (
              <Link
                key={`${item.kind}-${item.applicationId}-${item.detail}`}
                href={`/applications/${item.applicationId}`}
                className="flex flex-wrap items-center justify-between gap-2 py-3"
              >
                <span>
                  <span className="rounded-full border border-border px-2 py-0.5 text-xs text-fg-muted">
                    {ACTION_LABELS[item.kind]}
                  </span>{" "}
                  <strong className="font-medium">{item.company}</strong>
                  <span className="text-fg-muted"> · {item.position}</span>
                </span>
                <span className="text-sm text-fg-muted">
                  {item.detail}
                  {item.dueAt ? ` · ${formatDate(item.dueAt)} (${relativeDays(item.dueAt)})` : ""}
                </span>
              </Link>
            ))}
          </CardContent>
        </Card>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Applications per week</CardTitle>
          </CardHeader>
          <CardContent>
            <WeeklyApplicationsChart data={insights.data.applicationsPerWeek} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Funnel</CardTitle>
          </CardHeader>
          <CardContent>
            <FunnelChart data={insights.data.funnel} />
            <ul className="mt-3 space-y-1 text-sm text-fg-muted">
              {insights.data.funnel.map((stage) => (
                <li key={stage.stage}>
                  {stage.stage}: {stage.count}
                  {stage.shareOfApplied !== null && stage.stage !== "Applied"
                    ? ` (${percent(stage.shareOfApplied)} of applications)`
                    : ""}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Response rate by ATS</CardTitle>
          </CardHeader>
          <CardContent>
            <ResponseRateChart data={insights.data.byPlatform} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Response rate by location type</CardTitle>
          </CardHeader>
          <CardContent>
            <ResponseRateChart data={insights.data.byLocationType} />
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>How long companies take</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            <p>
              Average time to first reply:{" "}
              <strong>
                {timing.averageDaysToFirstResponse === null
                  ? "no replies yet"
                  : `${timing.averageDaysToFirstResponse} days`}
              </strong>
            </p>
            <p>
              Median:{" "}
              <strong>
                {timing.medianDaysToFirstResponse === null
                  ? "—"
                  : `${timing.medianDaysToFirstResponse} days`}
              </strong>
            </p>
            <p>
              Average time to a rejection:{" "}
              <strong>
                {timing.averageDaysToRejection === null
                  ? "—"
                  : `${timing.averageDaysToRejection} days`}
              </strong>
            </p>
            <p className="text-fg-muted">
              Measured over {timing.measuredFrom} application
              {timing.measuredFrom === 1 ? "" : "s"} that actually got a reply.
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Does the fit score predict a reply?</CardTitle>
          </CardHeader>
          <CardContent>
            {insights.data.fitVsOutcome.length === 0 ? (
              <p className="text-sm text-fg-muted">{insights.data.fitVsOutcomeNote}</p>
            ) : (
              <>
                <ul className="space-y-1 text-sm">
                  {insights.data.fitVsOutcome.map((band) => (
                    <li key={band.band} className="flex justify-between">
                      <span>{band.band.replace("_", " ").toLowerCase()}</span>
                      <span className="tabular-nums">
                        {percent(band.responseRate)} of {band.applications}
                      </span>
                    </li>
                  ))}
                </ul>
                <p className="mt-2 text-xs text-fg-muted">{insights.data.fitVsOutcomeNote}</p>
              </>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Stat({ label, value, sub }: { label: string; value: number; sub?: string }) {
  return (
    <Card>
      <CardContent className="pt-5">
        <p className="text-sm text-fg-muted">{label}</p>
        <p className="text-3xl font-semibold tabular-nums">{value}</p>
        {sub && <p className="text-sm text-fg-muted">{sub}</p>}
      </CardContent>
    </Card>
  );
}

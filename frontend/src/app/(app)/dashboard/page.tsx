"use client";

import Link from "next/link";
import { useApplications } from "@/lib/applications";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { StatusBadge } from "@/components/ui/badge";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import { formatDate } from "@/lib/format";

/**
 * Milestone 7 turns this into the full insights dashboard. For now it shows
 * what milestone 2 can honestly compute: counts and the most recent activity.
 */
export default function DashboardPage() {
  const applications = useApplications({ size: 8, sort: "updatedAt" });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
        <p className="text-sm text-fg-muted">
          Where every application stands, updated as email arrives.
        </p>
      </div>

      {applications.isLoading && <LoadingRows rows={4} />}

      {applications.isError && (
        <ErrorState
          description="Could not load your applications."
          onRetry={() => applications.refetch()}
        />
      )}

      {applications.isSuccess && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Total applications</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-4xl font-semibold tabular-nums">
                {applications.data.totalItems}
              </p>
              <Link href="/applications" className="text-sm text-brand underline">
                See all applications
              </Link>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Recent activity</CardTitle>
            </CardHeader>
            <CardContent className="divide-y divide-border">
              {applications.data.items.length === 0 && (
                <p className="py-4 text-sm text-fg-muted">
                  Nothing yet. Add an application or import your tracker.
                </p>
              )}
              {applications.data.items.map((application) => (
                <Link
                  key={application.id}
                  href={`/applications/${application.id}`}
                  className="flex flex-wrap items-center justify-between gap-2 py-3"
                >
                  <span className="font-medium">
                    {application.companyName}
                    <span className="font-normal text-fg-muted">
                      {" "}
                      · {application.positionTitle}
                    </span>
                  </span>
                  <span className="flex items-center gap-3">
                    <span className="text-sm text-fg-muted">
                      {formatDate(application.lastEventAt ?? application.appliedAt)}
                    </span>
                    <StatusBadge status={application.status} />
                  </span>
                </Link>
              ))}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}

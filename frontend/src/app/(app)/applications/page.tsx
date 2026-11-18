"use client";

import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";
import { ApplicationForm } from "@/components/application-form";
import { CsvTools } from "@/components/csv-tools";
import { StatusBadge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label, Select } from "@/components/ui/input";
import { EmptyState, ErrorState, LoadingRows } from "@/components/ui/states";
import { useApplications, useCreateApplication } from "@/lib/applications";
import { formatDate } from "@/lib/format";
import { APPLICATION_STATUSES, type ApplicationStatus } from "@/lib/types";
import { statusLabel } from "@/lib/format";

export default function ApplicationsPage() {
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<ApplicationStatus | "">("");
  const [includeArchived, setIncludeArchived] = useState(false);
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);

  const applications = useApplications({
    search,
    status,
    includeArchived,
    page,
    size: 25,
    sort: "updatedAt",
  });
  const createApplication = useCreateApplication();

  const hasFilters = search !== "" || status !== "" || includeArchived;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Applications</h1>
          <p className="text-sm text-fg-muted">
            {applications.isSuccess
              ? `${applications.data.totalItems} application${applications.data.totalItems === 1 ? "" : "s"}`
              : "Loading…"}
          </p>
        </div>
        <Button onClick={() => setAdding((open) => !open)}>
          {adding ? "Close" : "Add application"}
        </Button>
      </div>

      {adding && (
        <Card>
          <CardHeader>
            <CardTitle>Add an application</CardTitle>
          </CardHeader>
          <CardContent>
            <ApplicationForm
              submitLabel="Add application"
              pending={createApplication.isPending}
              errorMessage={
                createApplication.error instanceof Error
                  ? createApplication.error.message
                  : undefined
              }
              onCancel={() => setAdding(false)}
              onSubmit={(input) =>
                createApplication.mutate(input, {
                  onSuccess: (created) => {
                    toast.success(`Added ${created.companyName}`);
                    setAdding(false);
                  },
                })
              }
            />
          </CardContent>
        </Card>
      )}

      <Card>
        <CardContent className="space-y-4 pt-5">
          <div className="grid gap-3 sm:grid-cols-[1fr_auto_auto]">
            <div className="space-y-1">
              <Label htmlFor="search">Search</Label>
              <Input
                id="search"
                type="search"
                placeholder="Company, role or location"
                value={search}
                onChange={(event) => {
                  setSearch(event.target.value);
                  setPage(0);
                }}
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="status-filter">Status</Label>
              <Select
                id="status-filter"
                value={status}
                onChange={(event) => {
                  setStatus(event.target.value as ApplicationStatus | "");
                  setPage(0);
                }}
              >
                <option value="">All statuses</option>
                {APPLICATION_STATUSES.map((value) => (
                  <option key={value} value={value}>
                    {statusLabel(value)}
                  </option>
                ))}
              </Select>
            </div>
            <div className="flex items-end">
              <label className="flex items-center gap-2 pb-2 text-sm">
                <input
                  type="checkbox"
                  checked={includeArchived}
                  onChange={(event) => setIncludeArchived(event.target.checked)}
                />
                Show archived
              </label>
            </div>
          </div>

          <CsvTools />
        </CardContent>
      </Card>

      {applications.isLoading && <LoadingRows rows={6} />}

      {applications.isError && (
        <ErrorState
          description="Could not load your applications."
          onRetry={() => applications.refetch()}
        />
      )}

      {applications.isSuccess && applications.data.items.length === 0 && (
        <EmptyState
          title={hasFilters ? "No applications match those filters" : "No applications yet"}
          description={
            hasFilters
              ? "Try a different search or clear the status filter."
              : "Add one by hand, import your existing tracker as CSV, or connect Gmail to have them found for you."
          }
          action={
            hasFilters ? (
              <Button
                variant="secondary"
                onClick={() => {
                  setSearch("");
                  setStatus("");
                  setIncludeArchived(false);
                }}
              >
                Clear filters
              </Button>
            ) : (
              <Button onClick={() => setAdding(true)}>Add application</Button>
            )
          }
        />
      )}

      {applications.isSuccess && applications.data.items.length > 0 && (
        <>
          {/* Table on wide screens, cards on phones. */}
          <div className="hidden overflow-x-auto rounded-[var(--radius-card)] border border-border md:block">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Your applications</caption>
              <thead className="bg-surface-muted text-fg-muted">
                <tr>
                  <th scope="col" className="px-4 py-3 font-medium">Company</th>
                  <th scope="col" className="px-4 py-3 font-medium">Position</th>
                  <th scope="col" className="px-4 py-3 font-medium">Applied</th>
                  <th scope="col" className="px-4 py-3 font-medium">Status</th>
                  <th scope="col" className="px-4 py-3 font-medium">Days out</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {applications.data.items.map((application) => (
                  <tr key={application.id} className="hover:bg-surface-muted">
                    <td className="px-4 py-3 font-medium">
                      <Link href={`/applications/${application.id}`} className="hover:underline">
                        {application.companyName}
                      </Link>
                      {application.needsReview && (
                        <span className="ml-2 text-xs text-warning">needs review</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-fg-muted">{application.positionTitle}</td>
                    <td className="px-4 py-3 text-fg-muted">{formatDate(application.appliedAt)}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={application.status} />
                    </td>
                    <td className="px-4 py-3 tabular-nums text-fg-muted">
                      {application.daysSinceApplied ?? "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <ul className="space-y-2 md:hidden">
            {applications.data.items.map((application) => (
              <li key={application.id}>
                <Link
                  href={`/applications/${application.id}`}
                  className="block rounded-[var(--radius-card)] border border-border p-4"
                >
                  <div className="flex items-start justify-between gap-2">
                    <span className="font-medium">{application.companyName}</span>
                    <StatusBadge status={application.status} />
                  </div>
                  <p className="text-sm text-fg-muted">{application.positionTitle}</p>
                  <p className="mt-1 text-xs text-fg-muted">
                    Applied {formatDate(application.appliedAt)}
                  </p>
                </Link>
              </li>
            ))}
          </ul>

          {applications.data.totalPages > 1 && (
            <nav className="flex items-center justify-between" aria-label="Pagination">
              <Button
                variant="secondary"
                size="sm"
                disabled={page === 0}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                Previous
              </Button>
              <span className="text-sm text-fg-muted">
                Page {applications.data.page + 1} of {applications.data.totalPages}
              </span>
              <Button
                variant="secondary"
                size="sm"
                disabled={page + 1 >= applications.data.totalPages}
                onClick={() => setPage((current) => current + 1)}
              >
                Next
              </Button>
            </nav>
          )}
        </>
      )}
    </div>
  );
}

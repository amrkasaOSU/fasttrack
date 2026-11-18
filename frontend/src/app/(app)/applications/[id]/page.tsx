"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { ApplicationForm } from "@/components/application-form";
import { StatusBadge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label, Select } from "@/components/ui/input";
import { ErrorState, LoadingRows } from "@/components/ui/states";
import {
  useAddNote,
  useApplication,
  useArchiveApplication,
  useChangeStatus,
  useDeleteApplication,
  useUpdateApplication,
} from "@/lib/applications";
import { formatDate, formatDateTime, relativeDays, statusLabel } from "@/lib/format";
import { APPLICATION_STATUSES, type ApplicationStatus } from "@/lib/types";

const ACTOR_LABEL: Record<string, string> = {
  SYSTEM: "FastTrack",
  USER: "You",
  EMAIL: "Email",
  IMPORT: "Import",
};

export default function ApplicationDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const router = useRouter();

  const detail = useApplication(id);
  const changeStatus = useChangeStatus(id);
  const addNote = useAddNote(id);
  const updateApplication = useUpdateApplication(id);
  const archive = useArchiveApplication(id);
  const deleteApplication = useDeleteApplication();

  const [editing, setEditing] = useState(false);
  const [note, setNote] = useState("");
  const [nextStatus, setNextStatus] = useState<ApplicationStatus | "">("");

  if (detail.isLoading) return <LoadingRows rows={5} />;

  if (detail.isError) {
    return (
      <ErrorState
        title="Application not found"
        description="It may have been deleted, or it belongs to another account."
        onRetry={() => detail.refetch()}
      />
    );
  }

  if (!detail.data) return null;
  const { application, timeline, notes } = detail.data;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Link href="/applications" className="text-sm text-fg-muted hover:underline">
            ← All applications
          </Link>
          <h1 className="mt-1 text-2xl font-semibold tracking-tight">
            {application.companyName}
          </h1>
          <p className="text-fg-muted">{application.positionTitle}</p>
        </div>
        <div className="flex items-center gap-2">
          <StatusBadge status={application.status} />
          <Button variant="secondary" size="sm" onClick={() => setEditing((open) => !open)}>
            {editing ? "Close" : "Edit"}
          </Button>
        </div>
      </div>

      {application.needsReview && application.needsReviewReason && (
        <div className="rounded-[var(--radius-card)] border border-warning/40 bg-warning/10 p-4 text-sm">
          <strong className="font-medium">Needs review:</strong> {application.needsReviewReason}
        </div>
      )}

      {editing && (
        <Card>
          <CardHeader>
            <CardTitle>Edit application</CardTitle>
          </CardHeader>
          <CardContent>
            <ApplicationForm
              initial={application}
              submitLabel="Save changes"
              pending={updateApplication.isPending}
              onCancel={() => setEditing(false)}
              onSubmit={(input) =>
                updateApplication.mutate(input, {
                  onSuccess: () => {
                    toast.success("Saved");
                    setEditing(false);
                  },
                  onError: (error) =>
                    toast.error(error instanceof Error ? error.message : "Could not save"),
                })
              }
            />
          </CardContent>
        </Card>
      )}

      <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
        <Card>
          <CardHeader>
            <CardTitle>Status timeline</CardTitle>
          </CardHeader>
          <CardContent>
            <ol className="space-y-4">
              {timeline.map((entry) => (
                <li key={entry.id} className="flex gap-3">
                  <span
                    aria-hidden
                    className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-brand"
                  />
                  <div className="min-w-0">
                    <p className="text-sm">
                      {entry.toStatus ? (
                        <>
                          {entry.fromStatus && entry.fromStatus !== entry.toStatus ? (
                            <>
                              {statusLabel(entry.fromStatus)} →{" "}
                              <strong className="font-medium">
                                {statusLabel(entry.toStatus)}
                              </strong>
                            </>
                          ) : (
                            <strong className="font-medium">
                              {statusLabel(entry.toStatus)}
                            </strong>
                          )}
                        </>
                      ) : (
                        <strong className="font-medium">{entry.type.toLowerCase()}</strong>
                      )}
                    </p>
                    {entry.detail && (
                      <p className="text-sm text-fg-muted">{entry.detail}</p>
                    )}
                    <p className="text-xs text-fg-muted">
                      {formatDateTime(entry.occurredAt)} · {ACTOR_LABEL[entry.actor] ?? entry.actor}
                    </p>
                  </div>
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>Details</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              <Detail label="Applied" value={`${formatDate(application.appliedAt)} (${relativeDays(application.appliedAt)})`} />
              <Detail
                label="First response"
                value={
                  application.firstResponseAt
                    ? `${formatDate(application.firstResponseAt)} · ${application.daysToFirstResponse} days`
                    : "No reply yet"
                }
              />
              <Detail label="Location" value={application.location ?? "—"} />
              <Detail label="ATS" value={application.atsPlatform ?? "—"} />
              <Detail label="Requisition" value={application.requisitionId ?? "—"} />
              <Detail label="Source" value={application.source.toLowerCase().replace("_", " ")} />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Update status</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <Label htmlFor="next-status">New status</Label>
              <Select
                id="next-status"
                value={nextStatus}
                onChange={(event) => setNextStatus(event.target.value as ApplicationStatus)}
              >
                <option value="">Choose…</option>
                {APPLICATION_STATUSES.map((value) => (
                  <option key={value} value={value}>
                    {statusLabel(value)}
                  </option>
                ))}
              </Select>
              <Button
                size="sm"
                disabled={!nextStatus || changeStatus.isPending}
                onClick={() =>
                  nextStatus &&
                  changeStatus.mutate(
                    { status: nextStatus },
                    {
                      onSuccess: () => {
                        toast.success("Status updated");
                        setNextStatus("");
                      },
                    },
                  )
                }
              >
                Update
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Notes</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {notes ? (
                <p className="whitespace-pre-line text-sm text-fg-muted">{notes}</p>
              ) : (
                <p className="text-sm text-fg-muted">No notes yet.</p>
              )}
              <Label htmlFor="note" className="sr-only">
                Add a note
              </Label>
              <Input
                id="note"
                value={note}
                placeholder="Add a note"
                onChange={(event) => setNote(event.target.value)}
              />
              <Button
                size="sm"
                variant="secondary"
                disabled={!note.trim() || addNote.isPending}
                onClick={() =>
                  addNote.mutate(note.trim(), {
                    onSuccess: () => setNote(""),
                  })
                }
              >
                Add note
              </Button>
            </CardContent>
          </Card>

          <div className="flex flex-wrap gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={() =>
                archive.mutate(!application.archived, {
                  onSuccess: () =>
                    toast.success(application.archived ? "Restored" : "Archived"),
                })
              }
            >
              {application.archived ? "Restore" : "Archive"}
            </Button>
            <Button
              variant="danger"
              size="sm"
              onClick={() => {
                if (!confirm("Delete this application and its history? This cannot be undone.")) {
                  return;
                }
                deleteApplication.mutate(application.id, {
                  onSuccess: () => {
                    toast.success("Deleted");
                    router.push("/applications");
                  },
                });
              }}
            >
              Delete
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-fg-muted">{label}</dt>
      <dd className="text-right">{value}</dd>
    </div>
  );
}

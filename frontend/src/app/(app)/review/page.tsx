"use client";

import Link from "next/link";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label, Select } from "@/components/ui/input";
import { EmptyState, ErrorState, LoadingRows } from "@/components/ui/states";
import { formatDate } from "@/lib/format";
import {
  useCorrectEmail,
  useLiveAccuracy,
  useResolveApplicationReview,
  useReviewQueue,
  type ReviewEmail,
} from "@/lib/review";
import { EMAIL_CATEGORIES, type EmailCategory } from "@/lib/types";

function categoryLabel(category: EmailCategory) {
  return category.toLowerCase().replace(/_/g, " ");
}

export default function ReviewPage() {
  const queue = useReviewQueue();
  const accuracy = useLiveAccuracy();
  const resolveApplication = useResolveApplicationReview();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Needs review</h1>
        <p className="text-sm text-fg-muted">
          Emails the classifier was unsure about, and applications it attached without being
          certain. Nothing here changed a status silently.
        </p>
      </div>

      {accuracy.isSuccess && accuracy.data.classifications > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Accuracy from your corrections</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            <p className="text-2xl font-semibold tabular-nums">
              {accuracy.data.uncorrectedShare === null
                ? "—"
                : `${(accuracy.data.uncorrectedShare * 100).toFixed(1)}%`}
            </p>
            <p className="text-fg-muted">
              {accuracy.data.categoryCorrections} of {accuracy.data.classifications}{" "}
              classifications corrected
            </p>
            <p className="text-xs text-fg-muted">{accuracy.data.caveat}</p>
          </CardContent>
        </Card>
      )}

      {queue.isLoading && <LoadingRows rows={4} />}
      {queue.isError && (
        <ErrorState description="Could not load the review queue." onRetry={() => queue.refetch()} />
      )}

      {queue.isSuccess && queue.data.total === 0 && (
        <EmptyState
          title="Nothing to review"
          description="Every email has been classified confidently, and every application was matched without ambiguity."
        />
      )}

      {queue.isSuccess && queue.data.uncertainEmails.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-lg font-medium">
            Uncertain emails ({queue.data.uncertainEmails.length})
          </h2>
          {queue.data.uncertainEmails.map((email) => (
            <ReviewEmailCard key={email.emailId} email={email} />
          ))}
        </section>
      )}

      {queue.isSuccess && queue.data.uncertainApplications.length > 0 && (
        <section className="space-y-3">
          <h2 className="text-lg font-medium">
            Uncertain matches ({queue.data.uncertainApplications.length})
          </h2>
          {queue.data.uncertainApplications.map((application) => (
            <Card key={application.applicationId}>
              <CardContent className="flex flex-wrap items-center justify-between gap-3 pt-5">
                <div>
                  <Link
                    href={`/applications/${application.applicationId}`}
                    className="font-medium hover:underline"
                  >
                    {application.companyName} · {application.positionTitle}
                  </Link>
                  <p className="text-sm text-fg-muted">{application.reason}</p>
                </div>
                <div className="flex gap-2">
                  <Button variant="secondary" size="sm" asChild>
                    <Link href={`/applications/${application.applicationId}`}>Open</Link>
                  </Button>
                  <Button
                    size="sm"
                    disabled={resolveApplication.isPending}
                    onClick={() =>
                      resolveApplication.mutate(application.applicationId, {
                        onSuccess: () => toast.success("Marked as reviewed"),
                      })
                    }
                  >
                    Looks right
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </section>
      )}
    </div>
  );
}

function ReviewEmailCard({ email }: { email: ReviewEmail }) {
  const correct = useCorrectEmail();
  const [category, setCategory] = useState<EmailCategory>(email.category);
  const [company, setCompany] = useState(email.company ?? "");
  const [position, setPosition] = useState(email.positionTitle ?? "");

  return (
    <Card>
      <CardContent className="space-y-3 pt-5">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="font-medium">{email.subject}</p>
            <p className="text-sm text-fg-muted">
              {email.senderName ?? email.senderEmail} · {formatDate(email.receivedAt)}
            </p>
          </div>
          <span className="rounded-full border border-warning/40 bg-warning/10 px-2.5 py-0.5 text-xs text-warning">
            {Math.round(email.confidence * 100)}% confident
          </span>
        </div>

        <p className="text-sm text-fg-muted">{email.snippet}</p>

        <p className="text-sm">
          Read as <strong>{categoryLabel(email.category)}</strong>
          {email.reason ? ` — ${email.reason}` : ""}{" "}
          <span className="text-xs text-fg-muted">({email.method.toLowerCase()})</span>
        </p>

        {email.applicationLabel && (
          <p className="text-sm text-fg-muted">
            Attached to{" "}
            <Link href={`/applications/${email.applicationId}`} className="underline">
              {email.applicationLabel}
            </Link>
          </p>
        )}

        <div className="grid gap-3 sm:grid-cols-3">
          <div className="space-y-1">
            <Label htmlFor={`cat-${email.emailId}`}>Category</Label>
            <Select
              id={`cat-${email.emailId}`}
              value={category}
              onChange={(event) => setCategory(event.target.value as EmailCategory)}
            >
              {EMAIL_CATEGORIES.map((value) => (
                <option key={value} value={value}>
                  {categoryLabel(value)}
                </option>
              ))}
            </Select>
          </div>
          <div className="space-y-1">
            <Label htmlFor={`co-${email.emailId}`}>Company</Label>
            <Input
              id={`co-${email.emailId}`}
              value={company}
              onChange={(event) => setCompany(event.target.value)}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor={`pos-${email.emailId}`}>Position</Label>
            <Input
              id={`pos-${email.emailId}`}
              value={position}
              onChange={(event) => setPosition(event.target.value)}
            />
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          <Button
            size="sm"
            disabled={correct.isPending}
            onClick={() =>
              correct.mutate(
                { emailId: email.emailId, category, company, positionTitle: position },
                { onSuccess: () => toast.success("Saved — the application was updated too") },
              )
            }
          >
            Save correction
          </Button>
          <Button
            size="sm"
            variant="secondary"
            disabled={correct.isPending}
            onClick={() =>
              correct.mutate(
                {
                  emailId: email.emailId,
                  category: email.category,
                  company: email.company,
                  positionTitle: email.positionTitle,
                },
                { onSuccess: () => toast.success("Confirmed") },
              )
            }
          >
            It was right
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

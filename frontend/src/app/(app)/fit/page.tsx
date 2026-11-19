"use client";

import { useRef, useState } from "react";
import { toast } from "sonner";
import { FitResult } from "@/components/fit-result";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Label, Textarea } from "@/components/ui/input";
import { ErrorState, LoadingRows, NotConfiguredNotice } from "@/components/ui/states";
import { formatDate } from "@/lib/format";
import {
  BAND_LABELS,
  useFitChecks,
  useResumes,
  useRunFitCheck,
  useScoringExplanation,
  useUploadResume,
  type FitCheckResult,
} from "@/lib/fit";
import { useFeatureConfig } from "@/lib/session";

export default function FitCheckerPage() {
  const resumes = useResumes();
  const upload = useUploadResume();
  const runCheck = useRunFitCheck();
  const history = useFitChecks();
  const explanation = useScoringExplanation();
  const config = useFeatureConfig();

  const fileInput = useRef<HTMLInputElement>(null);
  const [postingText, setPostingText] = useState("");
  const [url, setUrl] = useState("");
  const [result, setResult] = useState<FitCheckResult | null>(null);

  const activeResume = resumes.data?.find((resume) => resume.active);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Fit checker</h1>
        <p className="text-sm text-fg-muted">
          Score your resume against a posting before you spend an hour applying to it.
        </p>
      </div>

      {config.isSuccess && !config.data.llmConfigured && (
        <NotConfiguredNotice
          feature="AI parsing"
          description="No LLM key is configured, so resumes and postings are read by the built-in rule-based parsers and the experience rating is a word-overlap estimate. The skill matching, eligibility checks and arithmetic work exactly the same."
        />
      )}

      <Card>
        <CardHeader>
          <CardTitle>1. Your resume</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {resumes.isLoading && <LoadingRows rows={2} />}
          {resumes.isError && (
            <ErrorState description="Could not load your resumes." onRetry={() => resumes.refetch()} />
          )}

          {resumes.isSuccess && activeResume && (
            <div className="space-y-2 text-sm">
              <p>
                <strong>{activeResume.filename}</strong>{" "}
                <span className="text-fg-muted">
                  · version {activeResume.version} · uploaded {formatDate(activeResume.createdAt)} ·
                  parsed by {activeResume.parseMethod.toLowerCase()}
                </span>
              </p>
              <p className="text-fg-muted">
                {activeResume.profile.languages.length +
                  activeResume.profile.frameworks.length +
                  activeResume.profile.databases.length +
                  activeResume.profile.tools.length +
                  activeResume.profile.cloud.length}{" "}
                skills found
                {activeResume.profile.education?.expectedGraduation
                  ? ` · graduating ${activeResume.profile.education.expectedGraduation}`
                  : " · no graduation date found"}
                {activeResume.profile.workAuthorization
                  ? ` · ${activeResume.profile.workAuthorization}`
                  : ""}
              </p>
              <ul className="flex flex-wrap gap-1.5">
                {[
                  ...activeResume.profile.languages,
                  ...activeResume.profile.frameworks,
                  ...activeResume.profile.databases,
                  ...activeResume.profile.cloud,
                  ...activeResume.profile.tools,
                ].map((skill) => (
                  <li
                    key={skill}
                    className="rounded-full border border-border bg-surface-muted px-2.5 py-0.5 text-xs"
                  >
                    {skill}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {resumes.isSuccess && !activeResume && (
            <p className="text-sm text-fg-muted">
              No resume yet. Upload a PDF (max 2 MB) — the text is read on the server and the
              file itself is not stored.
            </p>
          )}

          <input
            ref={fileInput}
            type="file"
            accept="application/pdf,.pdf"
            className="sr-only"
            aria-label="Choose a PDF resume to upload"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (!file) return;
              upload.mutate(file, {
                onSuccess: (stored) =>
                  toast.success(`Uploaded version ${stored.version}`),
                onError: (error) =>
                  toast.error(error instanceof Error ? error.message : "Upload failed"),
              });
              event.target.value = "";
            }}
          />
          <Button
            variant={activeResume ? "secondary" : "primary"}
            onClick={() => fileInput.current?.click()}
            disabled={upload.isPending}
          >
            {upload.isPending
              ? "Reading your resume…"
              : activeResume
                ? "Upload a new version"
                : "Upload resume (PDF)"}
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>2. The job posting</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-1">
            <Label htmlFor="posting">Paste the posting</Label>
            <Textarea
              id="posting"
              className="min-h-48"
              placeholder="Paste the whole description, including the requirements section."
              value={postingText}
              onChange={(event) => setPostingText(event.target.value)}
            />
            <p className="text-xs text-fg-muted">
              Pasting works every time. A link only works when the page is server-rendered —
              most job boards are not.
            </p>
          </div>

          <div className="space-y-1">
            <Label htmlFor="url">…or a link to it</Label>
            <Input
              id="url"
              type="url"
              placeholder="https://boards.greenhouse.io/..."
              value={url}
              onChange={(event) => setUrl(event.target.value)}
            />
          </div>

          <Button
            disabled={runCheck.isPending || (!postingText.trim() && !url.trim())}
            onClick={() =>
              runCheck.mutate(
                postingText.trim()
                  ? { postingText: postingText.trim() }
                  : { url: url.trim() },
                {
                  onSuccess: (fitResult) => {
                    setResult(fitResult);
                    toast.success(`Scored ${fitResult.score.overall}/100`);
                  },
                  onError: (error) =>
                    toast.error(error instanceof Error ? error.message : "Could not score that"),
                },
              )
            }
          >
            {runCheck.isPending ? "Scoring…" : "Check fit"}
          </Button>

          {explanation.isSuccess && (
            <p className="text-xs text-fg-muted">{explanation.data.weights}</p>
          )}
        </CardContent>
      </Card>

      {result && <FitResult result={result} />}

      {history.isSuccess && history.data.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Recent checks</CardTitle>
          </CardHeader>
          <CardContent className="divide-y divide-border">
            {history.data.map((check) => (
              <button
                key={check.id}
                type="button"
                onClick={() => setResult(check)}
                className="flex w-full flex-wrap items-center justify-between gap-2 py-3 text-left"
              >
                <span>
                  {check.postingTitle ?? "Untitled posting"}
                  {check.postingCompany ? ` · ${check.postingCompany}` : ""}
                  <span className="text-fg-muted"> · {formatDate(check.createdAt)}</span>
                </span>
                <span className="tabular-nums">
                  {check.score.overall} · {BAND_LABELS[check.score.band]}
                </span>
              </button>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { apiFetch, API_BASE_URL, primeCsrf } from "@/lib/api";
import type { CurrentUser, FeatureConfig } from "@/lib/types";

export default function LandingPage() {
  const router = useRouter();

  const config = useQuery({
    queryKey: ["config"],
    queryFn: () => apiFetch<FeatureConfig>("/api/config"),
  });

  const demoLogin = useMutation({
    mutationFn: async () => {
      await primeCsrf();
      return apiFetch<CurrentUser>("/api/auth/demo", { method: "POST" });
    },
    onSuccess: () => router.push("/dashboard"),
  });

  const googleAvailable = config.data?.gmailConfigured ?? false;

  return (
    <main id="main" className="mx-auto flex min-h-dvh max-w-3xl flex-col justify-center gap-10 px-4 py-16">
      <header className="space-y-4">
        <p className="text-sm font-medium uppercase tracking-widest text-fg-muted">FastTrack</p>
        <h1 className="text-4xl font-semibold tracking-tight sm:text-5xl">
          Every internship application, tracked for you.
        </h1>
        <p className="max-w-xl text-lg text-fg-muted">
          FastTrack reads your job-application email (read-only), works out what each
          message means, and keeps every application&apos;s status up to date - then scores
          how well your resume fits a posting before you apply.
        </p>
      </header>

      <div className="flex flex-wrap gap-3">
        <button
          type="button"
          onClick={() => demoLogin.mutate()}
          disabled={demoLogin.isPending || config.data?.demoEnabled === false}
          className="rounded-lg bg-brand px-5 py-3 font-medium text-brand-fg disabled:opacity-50"
        >
          {demoLogin.isPending ? "Starting demo..." : "Try the demo"}
        </button>

        <a
          href={googleAvailable ? `${API_BASE_URL}/oauth2/authorization/google` : undefined}
          aria-disabled={!googleAvailable}
          className={
            googleAvailable
              ? "rounded-lg border border-border px-5 py-3 font-medium"
              : "pointer-events-none rounded-lg border border-border px-5 py-3 font-medium opacity-50"
          }
        >
          Sign in with Google
        </a>
      </div>

      {config.isSuccess && !googleAvailable && (
        <p className="rounded-lg border border-border bg-surface-muted p-4 text-sm text-fg-muted">
          Gmail sign-in is not configured on this deployment, so email sync is turned
          off. Everything else - the demo, manual applications, CSV import and the fit
          scorer - works.
        </p>
      )}

      {config.isError && (
        <p className="rounded-lg border border-danger/40 p-4 text-sm">
          Can&apos;t reach the API at {API_BASE_URL}. Start the backend and reload.
        </p>
      )}

      {demoLogin.isError && (
        <p className="rounded-lg border border-danger/40 p-4 text-sm">
          Could not start the demo session. Please try again.
        </p>
      )}
    </main>
  );
}

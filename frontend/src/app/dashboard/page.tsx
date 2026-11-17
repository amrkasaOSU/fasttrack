"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { apiFetch } from "@/lib/api";
import type { CurrentUser } from "@/lib/types";

export default function DashboardPage() {
  const me = useQuery({
    queryKey: ["me"],
    queryFn: () => apiFetch<CurrentUser>("/api/auth/me"),
  });

  if (me.isLoading) {
    return <main id="main" className="p-8 text-fg-muted">Loading your dashboard...</main>;
  }

  if (!me.data?.authenticated) {
    return (
      <main id="main" className="space-y-4 p-8">
        <h1 className="text-2xl font-semibold">You are signed out</h1>
        <Link href="/" className="text-brand underline">Back to the start page</Link>
      </main>
    );
  }

  return (
    <main id="main" className="space-y-6 p-8">
      <h1 className="text-2xl font-semibold">
        Welcome{me.data.displayName ? `, ${me.data.displayName}` : ""}
      </h1>
      {me.data.demo && (
        <p className="rounded-lg border border-border bg-surface-muted p-4 text-sm text-fg-muted">
          You are in the demo account - the data here is synthetic.
        </p>
      )}
      <p className="text-fg-muted">
        Applications, insights and the fit checker land here in the next milestones.
      </p>
    </main>
  );
}

"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { apiFetch } from "@/lib/api";
import { useCurrentUser } from "@/lib/session";
import { cn } from "@/lib/utils";
import { Button } from "./ui/button";

const NAV = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/applications", label: "Applications" },
  { href: "/fit", label: "Fit checker" },
  { href: "/review", label: "Needs review" },
  { href: "/settings", label: "Settings" },
];

/** Header, navigation and the signed-in user menu. Mobile first: the nav
 * collapses to a horizontal scroller under the header on small screens. */
export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const me = useCurrentUser();
  const [signingOut, setSigningOut] = useState(false);

  async function signOut() {
    setSigningOut(true);
    try {
      await apiFetch("/api/auth/logout", { method: "POST" });
    } finally {
      router.push("/");
      router.refresh();
    }
  }

  return (
    <div className="min-h-dvh">
      <header className="border-b border-border bg-surface">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3">
          <Link href="/dashboard" className="text-lg font-semibold tracking-tight">
            FastTrack
          </Link>

          <div className="flex items-center gap-3">
            {me.data?.demo && (
              <span className="hidden rounded-full border border-border bg-surface-muted px-3 py-1 text-xs text-fg-muted sm:inline">
                Demo account - synthetic data
              </span>
            )}
            {me.data?.authenticated && (
              <>
                <span className="hidden text-sm text-fg-muted md:inline">
                  {me.data.displayName ?? me.data.email}
                </span>
                <Button variant="secondary" size="sm" onClick={signOut} disabled={signingOut}>
                  {signingOut ? "Signing out…" : "Sign out"}
                </Button>
              </>
            )}
          </div>
        </div>

        <nav aria-label="Main" className="mx-auto max-w-6xl px-4">
          <ul className="-mb-px flex gap-1 overflow-x-auto">
            {NAV.map((item) => {
              const active =
                pathname === item.href || pathname.startsWith(`${item.href}/`);
              return (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    className={cn(
                      "inline-block whitespace-nowrap border-b-2 px-3 py-2 text-sm",
                      active
                        ? "border-brand font-medium text-fg"
                        : "border-transparent text-fg-muted hover:text-fg",
                    )}
                  >
                    {item.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
      </header>

      <main id="main" className="mx-auto max-w-6xl px-4 py-6">
        {children}
      </main>
    </div>
  );
}

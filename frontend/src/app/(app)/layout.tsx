"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { AppShell } from "@/components/app-shell";
import { LoadingRows } from "@/components/ui/states";
import { useCurrentUser } from "@/lib/session";

/** Everything inside this group needs a session. */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const me = useCurrentUser();
  const router = useRouter();
  const signedOut = me.isSuccess && !me.data.authenticated;

  useEffect(() => {
    if (signedOut) router.replace("/");
  }, [signedOut, router]);

  if (me.isLoading || signedOut) {
    return (
      <div className="mx-auto max-w-6xl p-6">
        <LoadingRows rows={4} />
      </div>
    );
  }

  return <AppShell>{children}</AppShell>;
}

import { cn } from "@/lib/utils";
import type { ApplicationStatus } from "@/lib/types";
import { statusTone, statusLabel } from "@/lib/format";

export function Badge({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium",
        className,
      )}
    >
      {children}
    </span>
  );
}

const TONES: Record<string, string> = {
  neutral: "border-border bg-surface-muted text-fg-muted",
  progress: "border-info/30 bg-info/10 text-info",
  good: "border-success/30 bg-success/10 text-success",
  warning: "border-warning/40 bg-warning/10 text-warning",
  bad: "border-danger/30 bg-danger/10 text-danger",
};

export function StatusBadge({ status }: { status: ApplicationStatus }) {
  return <Badge className={TONES[statusTone(status)]}>{statusLabel(status)}</Badge>;
}

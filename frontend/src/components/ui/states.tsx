import { cn } from "@/lib/utils";
import { Button } from "./button";

/** The three states every data view needs: loading, empty, failed. */

export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      aria-hidden
      className={cn("animate-pulse rounded-md bg-surface-muted", className)}
    />
  );
}

export function LoadingRows({ rows = 5 }: { rows?: number }) {
  return (
    <div className="space-y-2" role="status" aria-label="Loading">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="h-12 w-full" />
      ))}
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-[var(--radius-card)] border border-dashed border-border px-6 py-12 text-center">
      <h3 className="text-base font-semibold">{title}</h3>
      <p className="max-w-md text-sm text-fg-muted">{description}</p>
      {action}
    </div>
  );
}

export function ErrorState({
  title = "Something went wrong",
  description,
  onRetry,
}: {
  title?: string;
  description: string;
  onRetry?: () => void;
}) {
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-3 rounded-[var(--radius-card)] border border-danger/40 bg-danger/5 p-5"
    >
      <div>
        <h3 className="font-semibold">{title}</h3>
        <p className="text-sm text-fg-muted">{description}</p>
      </div>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

export function NotConfiguredNotice({
  feature,
  description,
}: {
  feature: string;
  description: string;
}) {
  return (
    <div className="rounded-[var(--radius-card)] border border-border bg-surface-muted p-5">
      <h3 className="font-semibold">{feature} is not configured</h3>
      <p className="mt-1 text-sm text-fg-muted">{description}</p>
    </div>
  );
}

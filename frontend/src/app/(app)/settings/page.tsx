"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { CsvTools } from "@/components/csv-tools";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ErrorState, LoadingRows, NotConfiguredNotice } from "@/components/ui/states";
import { API_BASE_URL } from "@/lib/api";
import { formatDateTime, relativeDays } from "@/lib/format";
import { useCurrentUser, useFeatureConfig } from "@/lib/session";
import {
  useDeleteAllData,
  useDisconnectGmail,
  useSettings,
  useSyncNow,
} from "@/lib/settings";

export default function SettingsPage() {
  const me = useCurrentUser();
  const config = useFeatureConfig();
  const settings = useSettings();
  const syncNow = useSyncNow();
  const disconnect = useDisconnectGmail();
  const deleteAll = useDeleteAllData();
  const router = useRouter();

  const [confirmText, setConfirmText] = useState("");
  const isDemo = me.data?.demo ?? false;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="text-sm text-fg-muted">
          Your account, your mailbox connection, and your data.
        </p>
      </div>

      {settings.isLoading && <LoadingRows rows={4} />}
      {settings.isError && (
        <ErrorState description="Could not load your settings." onRetry={() => settings.refetch()} />
      )}

      <Card>
        <CardHeader>
          <CardTitle>Account</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1 text-sm">
          <p>{me.data?.displayName}</p>
          <p className="text-fg-muted">{me.data?.email}</p>
          {isDemo && (
            <p className="text-fg-muted">
              This is the shared demo account. Its data is synthetic, and it cannot connect
              Gmail or delete anything.
            </p>
          )}
        </CardContent>
      </Card>

      {settings.isSuccess && (
        <Card>
          <CardHeader>
            <CardTitle>Gmail</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {!settings.data.gmail.available && (
              <NotConfiguredNotice
                feature="Gmail sync"
                description="This deployment has no Google credentials or no token encryption key, so FastTrack cannot read email. Applications you add by hand or import from CSV work normally. See docs/google-setup.md to enable it."
              />
            )}

            {settings.data.gmail.available && !settings.data.gmail.connected && (
              <div className="space-y-3">
                <p className="text-sm text-fg-muted">
                  FastTrack asks only for <strong>read-only</strong> Gmail access. It cannot
                  send, delete, label or change anything in your mailbox, and message bodies
                  are never stored.
                </p>
                <Button asChild disabled={isDemo}>
                  <a href={`${API_BASE_URL}/oauth2/authorization/google`}>
                    {settings.data.gmail.needsReconnect ? "Reconnect Gmail" : "Connect Gmail"}
                  </a>
                </Button>
              </div>
            )}

            {settings.data.gmail.connected && (
              <div className="space-y-3">
                <dl className="grid gap-2 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="text-fg-muted">Mailbox</dt>
                    <dd>{settings.data.gmail.emailAddress}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">Last sync</dt>
                    <dd>
                      {settings.data.gmail.lastSyncAt
                        ? `${formatDateTime(settings.data.gmail.lastSyncAt)} (${relativeDays(settings.data.gmail.lastSyncAt)})`
                        : "Not yet"}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">State</dt>
                    <dd>{settings.data.gmail.syncStatus?.toLowerCase() ?? "idle"}</dd>
                  </div>
                  <div>
                    <dt className="text-fg-muted">Access</dt>
                    <dd className="truncate" title={settings.data.gmail.scopes ?? ""}>
                      read-only
                    </dd>
                  </div>
                </dl>

                {settings.data.gmail.syncError && (
                  <p role="alert" className="rounded-lg border border-danger/40 bg-danger/5 p-3 text-sm">
                    {settings.data.gmail.syncError}
                  </p>
                )}

                <div className="flex flex-wrap gap-2">
                  <Button
                    onClick={() =>
                      syncNow.mutate(undefined, {
                        onSuccess: (run) =>
                          toast.success(
                            run.status === "SUCCESS"
                              ? `Synced: ${run.messagesFetched} emails, ${run.applicationsCreated} new applications`
                              : `Sync ${run.status.toLowerCase()}`,
                          ),
                        onError: (error) =>
                          toast.error(error instanceof Error ? error.message : "Sync failed"),
                      })
                    }
                    disabled={syncNow.isPending || isDemo}
                  >
                    {syncNow.isPending ? "Syncing…" : "Sync now"}
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={disconnect.isPending || isDemo}
                    onClick={() => {
                      if (!confirm("Disconnect Gmail? Applications already found are kept.")) return;
                      disconnect.mutate(undefined, {
                        onSuccess: () => toast.success("Gmail disconnected and the token revoked"),
                      });
                    }}
                  >
                    Disconnect Gmail
                  </Button>
                </div>
              </div>
            )}

            {settings.data.lastSync && (
              <div className="rounded-lg border border-border bg-surface-muted p-4 text-sm">
                <p className="font-medium">Last sync</p>
                <p className="text-fg-muted">
                  {settings.data.lastSync.messagesFetched} emails fetched ·{" "}
                  {settings.data.lastSync.messagesDropped} filtered out before AI ·{" "}
                  {settings.data.lastSync.messagesClassified} classified ·{" "}
                  {settings.data.lastSync.applicationsCreated} applications added
                </p>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {config.isSuccess && !config.data.llmConfigured && (
        <NotConfiguredNotice
          feature="AI classification"
          description="No LLM key is configured, so FastTrack classifies email with its built-in rule-based classifier instead. See docs/llm-setup.md."
        />
      )}

      <Card>
        <CardHeader>
          <CardTitle>Your data</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {settings.isSuccess && (
            <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
              <Stat label="Applications" value={settings.data.data.applications} />
              <Stat label="Emails stored" value={settings.data.data.emailsStored} />
              <Stat label="Filtered out" value={settings.data.data.emailsDroppedByPrefilter} />
              <Stat label="Classified" value={settings.data.data.classifications} />
            </dl>
          )}
          <CsvTools />
          <p className="text-xs text-fg-muted">
            FastTrack never stores email bodies - only the sender, subject, a short snippet and
            the fields it extracted.
          </p>
        </CardContent>
      </Card>

      <Card className="border-danger/40">
        <CardHeader>
          <CardTitle>Delete everything</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-fg-muted">
            This deletes your account and every application, email record, classification,
            resume and fit check that belongs to it, and revokes FastTrack&apos;s access to your
            Gmail. It cannot be undone.
          </p>
          <label className="block text-sm" htmlFor="confirm-delete">
            Type <strong>DELETE</strong> to confirm
          </label>
          <Input
            id="confirm-delete"
            value={confirmText}
            onChange={(event) => setConfirmText(event.target.value)}
            className="max-w-xs"
            disabled={isDemo}
          />
          <Button
            variant="danger"
            disabled={confirmText !== "DELETE" || deleteAll.isPending || isDemo}
            onClick={() =>
              deleteAll.mutate(undefined, {
                onSuccess: (result) => {
                  toast.success(result.message);
                  router.push("/");
                },
                onError: (error) =>
                  toast.error(error instanceof Error ? error.message : "Could not delete"),
              })
            }
          >
            {deleteAll.isPending ? "Deleting…" : "Delete all my data"}
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-fg-muted">{label}</dt>
      <dd className="text-xl font-semibold tabular-nums">{value}</dd>
    </div>
  );
}

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./api";

export type GmailStatus = {
  available: boolean;
  connected: boolean;
  emailAddress: string | null;
  syncStatus: "IDLE" | "RUNNING" | "ERROR" | "REVOKED" | null;
  syncError: string | null;
  connectedAt: string | null;
  lastSyncAt: string | null;
  needsReconnect: boolean;
  scopes: string | null;
};

export type SyncSummary = {
  id: string;
  trigger: "MANUAL" | "SCHEDULED" | "INITIAL";
  status: "RUNNING" | "SUCCESS" | "FAILED" | "SKIPPED";
  startedAt: string;
  finishedAt: string | null;
  messagesFetched: number;
  messagesDropped: number;
  messagesClassified: number;
  llmCalls: number;
  cacheHits: number;
  applicationsCreated: number;
  applicationsUpdated: number;
  errorMessage: string | null;
};

export type SettingsResponse = {
  gmail: GmailStatus;
  lastSync: SyncSummary | null;
  data: {
    applications: number;
    emailsStored: number;
    emailsDroppedByPrefilter: number;
    classifications: number;
  };
};

export function useSettings() {
  return useQuery({
    queryKey: ["settings"],
    queryFn: () => apiFetch<SettingsResponse>("/api/settings"),
  });
}

function useInvalidateSettings() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ["settings"] });
    void queryClient.invalidateQueries({ queryKey: ["applications"] });
  };
}

export function useSyncNow() {
  const invalidate = useInvalidateSettings();
  return useMutation({
    mutationFn: () => apiFetch<SyncSummary>("/api/settings/gmail/sync", { method: "POST" }),
    onSuccess: invalidate,
  });
}

export function useDisconnectGmail() {
  const invalidate = useInvalidateSettings();
  return useMutation({
    mutationFn: () => apiFetch<GmailStatus>("/api/settings/gmail/disconnect", { method: "POST" }),
    onSuccess: invalidate,
  });
}

export function useDeleteAllData() {
  return useMutation({
    mutationFn: () =>
      apiFetch<{ deleted: boolean; googleGrantRevoked: boolean; message: string }>(
        "/api/settings/data",
        { method: "DELETE" },
      ),
  });
}

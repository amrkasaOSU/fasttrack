import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./api";
import type { ApplicationStatus } from "./types";

export type ApplicationSummary = {
  id: string;
  companyName: string;
  positionTitle: string;
  requisitionId: string | null;
  location: string | null;
  atsPlatform: string | null;
  status: ApplicationStatus;
  source: "MANUAL" | "EMAIL" | "CSV_IMPORT" | "DEMO";
  appliedAt: string | null;
  firstResponseAt: string | null;
  lastEventAt: string | null;
  needsReview: boolean;
  needsReviewReason: string | null;
  archived: boolean;
  daysSinceApplied: number | null;
  daysToFirstResponse: number | null;
  createdAt: string;
  updatedAt: string;
};

export type TimelineEntry = {
  id: string;
  type: string;
  fromStatus: ApplicationStatus | null;
  toStatus: ApplicationStatus | null;
  actor: "SYSTEM" | "USER" | "EMAIL" | "IMPORT";
  emailId: string | null;
  detail: string | null;
  occurredAt: string;
};

export type ApplicationDetail = {
  application: ApplicationSummary;
  timeline: TimelineEntry[];
  notes: string | null;
};

export type ApplicationPage = {
  items: ApplicationSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export type ApplicationFilters = {
  status?: ApplicationStatus | "";
  search?: string;
  includeArchived?: boolean;
  page?: number;
  size?: number;
  sort?: string;
};

export type UpsertApplicationInput = {
  companyName: string;
  positionTitle: string;
  requisitionId?: string | null;
  location?: string | null;
  atsPlatform?: string | null;
  status?: ApplicationStatus | null;
  appliedAt?: string | null;
  notes?: string | null;
};

function toQuery(filters: ApplicationFilters): string {
  const params = new URLSearchParams();
  if (filters.status) params.set("status", filters.status);
  if (filters.search) params.set("search", filters.search);
  if (filters.includeArchived) params.set("includeArchived", "true");
  params.set("page", String(filters.page ?? 0));
  params.set("size", String(filters.size ?? 25));
  if (filters.sort) params.set("sort", filters.sort);
  return params.toString();
}

export function useApplications(filters: ApplicationFilters) {
  return useQuery({
    queryKey: ["applications", filters],
    queryFn: () => apiFetch<ApplicationPage>(`/api/applications?${toQuery(filters)}`),
  });
}

export function useApplication(id: string) {
  return useQuery({
    queryKey: ["application", id],
    queryFn: () => apiFetch<ApplicationDetail>(`/api/applications/${id}`),
    enabled: Boolean(id),
  });
}

/** Invalidates every application view after a write. */
function useInvalidate() {
  const queryClient = useQueryClient();
  return (id?: string) => {
    void queryClient.invalidateQueries({ queryKey: ["applications"] });
    void queryClient.invalidateQueries({ queryKey: ["insights"] });
    if (id) void queryClient.invalidateQueries({ queryKey: ["application", id] });
  };
}

export function useCreateApplication() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (input: UpsertApplicationInput) =>
      apiFetch<ApplicationSummary>("/api/applications", { method: "POST", body: input }),
    onSuccess: () => invalidate(),
  });
}

export function useUpdateApplication(id: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (input: UpsertApplicationInput) =>
      apiFetch<ApplicationSummary>(`/api/applications/${id}`, { method: "PUT", body: input }),
    onSuccess: () => invalidate(id),
  });
}

export function useChangeStatus(id: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (input: { status: ApplicationStatus; note?: string }) =>
      apiFetch<ApplicationSummary>(`/api/applications/${id}/status`, {
        method: "POST",
        body: input,
      }),
    onSuccess: () => invalidate(id),
  });
}

export function useAddNote(id: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (note: string) =>
      apiFetch<ApplicationSummary>(`/api/applications/${id}/notes`, {
        method: "POST",
        body: { note },
      }),
    onSuccess: () => invalidate(id),
  });
}

export function useArchiveApplication(id: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (archived: boolean) =>
      apiFetch<ApplicationSummary>(
        `/api/applications/${id}/${archived ? "archive" : "unarchive"}`,
        { method: "POST" },
      ),
    onSuccess: () => invalidate(id),
  });
}

export function useDeleteApplication() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<void>(`/api/applications/${id}`, { method: "DELETE" }),
    onSuccess: () => invalidate(),
  });
}

export type CsvImportResult = {
  rowsRead: number;
  created: number;
  duplicatesSkipped: number;
  errors: { line: number; company: string | null; message: string }[];
  warnings: { line: number; company: string | null; message: string }[];
};

export function useImportCsv() {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (file: File) => {
      const body = new FormData();
      body.append("file", file);
      return apiFetch<CsvImportResult>("/api/applications/import", {
        method: "POST",
        body,
      });
    },
    onSuccess: () => invalidate(),
  });
}

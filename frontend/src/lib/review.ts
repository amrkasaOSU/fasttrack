import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./api";
import type { EmailCategory } from "./types";

export type ReviewEmail = {
  emailId: string;
  subject: string;
  senderName: string | null;
  senderEmail: string;
  snippet: string;
  receivedAt: string;
  category: EmailCategory;
  confidence: number;
  reason: string | null;
  method: "LLM" | "RULE" | "MANUAL";
  company: string | null;
  positionTitle: string | null;
  applicationId: string | null;
  applicationLabel: string | null;
};

export type ReviewApplication = {
  applicationId: string;
  companyName: string;
  positionTitle: string;
  status: string;
  reason: string | null;
  updatedAt: string;
};

export type ReviewQueue = {
  uncertainEmails: ReviewEmail[];
  uncertainApplications: ReviewApplication[];
  total: number;
};

export type LiveAccuracy = {
  classifications: number;
  corrections: number;
  categoryCorrections: number;
  llmClassifications: number;
  llmCategoryCorrections: number;
  uncorrectedShare: number | null;
  caveat: string;
};

export function useReviewQueue() {
  return useQuery({
    queryKey: ["review"],
    queryFn: () => apiFetch<ReviewQueue>("/api/review"),
  });
}

export function useLiveAccuracy() {
  return useQuery({
    queryKey: ["review", "accuracy"],
    queryFn: () => apiFetch<LiveAccuracy>("/api/review/accuracy"),
  });
}

export function useCorrectEmail() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: {
      emailId: string;
      category: EmailCategory;
      company?: string | null;
      positionTitle?: string | null;
    }) =>
      apiFetch<ReviewEmail>(`/api/review/emails/${input.emailId}/correct`, {
        method: "POST",
        body: {
          category: input.category,
          company: input.company ?? null,
          positionTitle: input.positionTitle ?? null,
        },
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["review"] });
      void queryClient.invalidateQueries({ queryKey: ["applications"] });
    },
  });
}

export function useResolveApplicationReview() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (applicationId: string) =>
      apiFetch(`/api/applications/${applicationId}/resolve-review`, {
        method: "POST",
        body: {},
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["review"] });
      void queryClient.invalidateQueries({ queryKey: ["applications"] });
    },
  });
}

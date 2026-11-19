import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./api";

export type ResumeProfile = {
  languages: string[];
  frameworks: string[];
  databases: string[];
  tools: string[];
  cloud: string[];
  experience: {
    title: string | null;
    organization: string | null;
    period: string | null;
    highlights: string[];
  }[];
  projects: { name: string | null; description: string | null; technologies: string[] }[];
  education: {
    school: string | null;
    degree: string | null;
    field: string | null;
    expectedGraduation: string | null;
  } | null;
  workAuthorization: string | null;
};

export type StoredResume = {
  id: string;
  version: number;
  filename: string;
  sizeBytes: number;
  parseMethod: "PENDING" | "LLM" | "HEURISTIC" | "MANUAL";
  active: boolean;
  profile: ResumeProfile;
  createdAt: string;
};

export type FitBand = "STRONG" | "GOOD" | "STRETCH" | "LONG_SHOT";

export type EligibilityCheck = {
  name: string;
  status: "PASS" | "FAIL" | "UNKNOWN";
  hard: boolean;
  detail: string;
};

export type Suggestion = {
  kind: "REWORD" | "SURFACE" | "LEARN" | "ELIGIBILITY";
  text: string;
};

export type FitScore = {
  overall: number;
  band: FitBand;
  requiredCoverage: number;
  preferredCoverage: number;
  experienceScore: number;
  eligibilityScore: number;
  atsKeywordCoverage: number;
  hardMismatch: boolean;
  matchedRequired: string[];
  missingRequired: string[];
  matchedPreferred: string[];
  missingPreferred: string[];
  matchedKeywords: string[];
  missingKeywords: string[];
  eligibility: EligibilityCheck[];
  suggestions: Suggestion[];
  experienceReason: string | null;
  weightingNote: string | null;
  scoringVersion: string;
};

export type FitCheckResult = {
  id: string;
  resumeId: string;
  jobPostingId: string;
  applicationId: string | null;
  postingTitle: string | null;
  postingCompany: string | null;
  score: FitScore;
  posting: {
    title: string | null;
    company: string | null;
    requiredSkills: string[];
    preferredSkills: string[];
    responsibilities: string[];
    minEducation: string | null;
    graduationWindow: string | null;
    workAuthorizationRequirements: string | null;
    location: string | null;
    internshipSeason: string | null;
  };
  createdAt: string;
};

export type ScoringExplanation = {
  weights: string;
  hardMismatchCap: number;
  bands: { band: FitBand; label: string; minimumScore: number }[];
};

export const BAND_LABELS: Record<FitBand, string> = {
  STRONG: "Strong fit",
  GOOD: "Good fit",
  STRETCH: "Stretch",
  LONG_SHOT: "Long shot",
};

export function useResumes() {
  return useQuery({
    queryKey: ["resumes"],
    queryFn: () => apiFetch<StoredResume[]>("/api/resumes"),
  });
}

export function useUploadResume() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const body = new FormData();
      body.append("file", file);
      return apiFetch<StoredResume>("/api/resumes", { method: "POST", body });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["resumes"] }),
  });
}

export function useSaveResumeProfile(resumeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (profile: ResumeProfile) =>
      apiFetch<StoredResume>(`/api/resumes/${resumeId}/profile`, {
        method: "PUT",
        body: profile,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["resumes"] }),
  });
}

export function useRunFitCheck() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { postingText?: string; url?: string; applicationId?: string }) =>
      apiFetch<FitCheckResult>("/api/fit", { method: "POST", body: input }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["fitChecks"] }),
  });
}

export function useFitChecks() {
  return useQuery({
    queryKey: ["fitChecks"],
    queryFn: () => apiFetch<FitCheckResult[]>("/api/fit"),
  });
}

export function useScoringExplanation() {
  return useQuery({
    queryKey: ["fitExplanation"],
    queryFn: () => apiFetch<ScoringExplanation>("/api/fit/explanation"),
    staleTime: 60 * 60_000,
  });
}

import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./api";

export type Totals = {
  applications: number;
  companies: number;
  responses: number;
  noResponse: number;
  rejections: number;
  assessments: number;
  interviews: number;
  offers: number;
  active: number;
  archived: number;
  responseRate: number | null;
  interviewRate: number | null;
  offerRate: number | null;
};

export type WeeklyPoint = { week: string; weekStart: string; applied: number };
export type FunnelStage = { stage: string; count: number; shareOfApplied: number | null };
export type GroupedRate = {
  group: string;
  applications: number;
  responses: number;
  responseRate: number | null;
};
export type TimingStats = {
  averageDaysToFirstResponse: number | null;
  medianDaysToFirstResponse: number | null;
  averageDaysToRejection: number | null;
  measuredFrom: number;
};
export type ActionItem = {
  kind: "ASSESSMENT_DEADLINE" | "INTERVIEW" | "ASSESSMENT_PENDING" | "NO_RESPONSE";
  applicationId: string;
  company: string;
  position: string;
  detail: string;
  dueAt: string | null;
  daysUntil: number | null;
};
export type FitBandOutcome = {
  band: string;
  applications: number;
  responses: number;
  responseRate: number | null;
};

export type Insights = {
  totals: Totals;
  applicationsPerWeek: WeeklyPoint[];
  funnel: FunnelStage[];
  byPlatform: GroupedRate[];
  byLocationType: GroupedRate[];
  timing: TimingStats;
  actionItems: ActionItem[];
  needsReviewCount: number;
  fitVsOutcome: FitBandOutcome[];
  fitVsOutcomeNote: string;
};

export function useInsights() {
  return useQuery({
    queryKey: ["insights"],
    queryFn: () => apiFetch<Insights>("/api/insights"),
  });
}

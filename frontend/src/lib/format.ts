import type { ApplicationStatus } from "./types";

const STATUS_LABELS: Record<ApplicationStatus, string> = {
  APPLIED: "Applied",
  APPLICATION_RECEIVED: "Application received",
  UNDER_REVIEW: "Under review",
  ASSESSMENT_REQUESTED: "Assessment requested",
  ASSESSMENT_COMPLETED: "Assessment completed",
  RECRUITER_CONTACT: "Recruiter contact",
  INTERVIEW_REQUESTED: "Interview requested",
  INTERVIEW_SCHEDULED: "Interview scheduled",
  INTERVIEW_COMPLETED: "Interview completed",
  FINAL_ROUND: "Final round",
  OFFER_RECEIVED: "Offer received",
  OFFER_ACCEPTED: "Offer accepted",
  OFFER_DECLINED: "Offer declined",
  REJECTED: "Rejected",
  POSITION_CANCELLED: "Position cancelled",
  WITHDRAWN: "Withdrawn",
};

type Tone = "neutral" | "progress" | "good" | "warning" | "bad";

const STATUS_TONES: Record<ApplicationStatus, Tone> = {
  APPLIED: "neutral",
  APPLICATION_RECEIVED: "progress",
  UNDER_REVIEW: "progress",
  ASSESSMENT_REQUESTED: "warning",
  ASSESSMENT_COMPLETED: "progress",
  RECRUITER_CONTACT: "progress",
  INTERVIEW_REQUESTED: "good",
  INTERVIEW_SCHEDULED: "good",
  INTERVIEW_COMPLETED: "good",
  FINAL_ROUND: "good",
  OFFER_RECEIVED: "good",
  OFFER_ACCEPTED: "good",
  OFFER_DECLINED: "neutral",
  REJECTED: "bad",
  POSITION_CANCELLED: "bad",
  WITHDRAWN: "neutral",
};

export function statusLabel(status: ApplicationStatus): string {
  return STATUS_LABELS[status] ?? status;
}

export function statusTone(status: ApplicationStatus): Tone {
  return STATUS_TONES[status] ?? "neutral";
}

/** "14 Sep 2026" - unambiguous for a US/EU mixed audience, no time of day. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "—";
  return date.toLocaleString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** "3 days ago", "today", "in 2 days" - for timelines and follow-up lists. */
export function relativeDays(value: string | null | undefined): string {
  if (!value) return "";
  const then = new Date(value).getTime();
  if (Number.isNaN(then)) return "";
  const days = Math.round((then - Date.now()) / 86_400_000);
  if (days === 0) return "today";
  if (days === -1) return "yesterday";
  if (days === 1) return "tomorrow";
  return days < 0 ? `${Math.abs(days)} days ago` : `in ${days} days`;
}

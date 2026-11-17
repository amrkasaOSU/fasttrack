/** Shapes returned by the backend API. Kept in one file so drift is obvious. */

export type FeatureConfig = {
  gmailConfigured: boolean;
  llmConfigured: boolean;
  tokenEncryptionConfigured: boolean;
  demoEnabled: boolean;
  llmModel: string | null;
  syncInitialDays: number;
};

export type CurrentUser = {
  authenticated: boolean;
  userId: string | null;
  email: string | null;
  displayName: string | null;
  pictureUrl: string | null;
  demo: boolean;
  googleLoginAvailable: boolean;
};

export const APPLICATION_STATUSES = [
  "APPLIED",
  "APPLICATION_RECEIVED",
  "UNDER_REVIEW",
  "ASSESSMENT_REQUESTED",
  "ASSESSMENT_COMPLETED",
  "RECRUITER_CONTACT",
  "INTERVIEW_REQUESTED",
  "INTERVIEW_SCHEDULED",
  "INTERVIEW_COMPLETED",
  "FINAL_ROUND",
  "OFFER_RECEIVED",
  "OFFER_ACCEPTED",
  "OFFER_DECLINED",
  "REJECTED",
  "POSITION_CANCELLED",
  "WITHDRAWN",
] as const;

export type ApplicationStatus = (typeof APPLICATION_STATUSES)[number];

export const EMAIL_CATEGORIES = [
  "APPLICATION_CONFIRMATION",
  "REJECTION",
  "ASSESSMENT_INVITE",
  "INTERVIEW_REQUEST",
  "INTERVIEW_SCHEDULED",
  "OFFER",
  "RECRUITER_OUTREACH",
  "POSITION_CANCELLED",
  "SURVEY_OR_ACCOUNT",
  "NOT_JOB_RELATED",
] as const;

export type EmailCategory = (typeof EMAIL_CATEGORIES)[number];

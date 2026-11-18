import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./api";
import type { CurrentUser, FeatureConfig } from "./types";

export function useCurrentUser() {
  return useQuery({
    queryKey: ["me"],
    queryFn: () => apiFetch<CurrentUser>("/api/auth/me"),
    staleTime: 60_000,
  });
}

export function useFeatureConfig() {
  return useQuery({
    queryKey: ["config"],
    queryFn: () => apiFetch<FeatureConfig>("/api/config"),
    staleTime: 5 * 60_000,
  });
}

/**
 * The single place the frontend talks to the backend.
 *
 * Two things every request needs:
 *  - `credentials: "include"`, because auth is a session cookie, not a token;
 *  - the CSRF token from the readable `XSRF-TOKEN` cookie echoed back in
 *    `X-XSRF-TOKEN` on anything that changes state.
 */

export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors: { field: string; message: string }[] = [],
  ) {
    super(message);
    this.name = "ApiError";
  }

  get isUnauthenticated() {
    return this.status === 401;
  }

  get isNotConfigured() {
    return this.code === "not_configured";
  }
}

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(
    new RegExp("(^|;\\s*)" + name + "=([^;]*)"),
  );
  return match?.[2] ? decodeURIComponent(match[2]) : null;
}

type RequestOptions = Omit<RequestInit, "body"> & { body?: unknown };

export async function apiFetch<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { body, headers, method = "GET", ...rest } = options;
  const isMutation = !["GET", "HEAD", "OPTIONS"].includes(method.toUpperCase());

  const requestHeaders = new Headers(headers);
  if (body !== undefined && !(body instanceof FormData)) {
    requestHeaders.set("Content-Type", "application/json");
  }
  if (isMutation) {
    const csrf = readCookie("XSRF-TOKEN");
    if (csrf) requestHeaders.set("X-XSRF-TOKEN", csrf);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...rest,
    method,
    headers: requestHeaders,
    credentials: "include",
    body:
      body === undefined
        ? undefined
        : body instanceof FormData
          ? body
          : JSON.stringify(body),
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload = text ? safeJson(text) : null;

  if (!response.ok) {
    const error = payload as {
      code?: string;
      message?: string;
      fieldErrors?: { field: string; message: string }[];
    } | null;
    throw new ApiError(
      response.status,
      error?.code ?? "unknown_error",
      error?.message ?? `Request failed with status ${response.status}`,
      error?.fieldErrors ?? [],
    );
  }

  return payload as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/**
 * Primes the CSRF cookie. The backend sets `XSRF-TOKEN` on every response, so
 * any GET works; `/api/config` is the cheapest and is public.
 */
export async function primeCsrf(): Promise<void> {
  try {
    await apiFetch("/api/config");
  } catch {
    // Offline or backend down - the caller surfaces that on its own.
  }
}

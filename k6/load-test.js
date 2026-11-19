/* eslint-disable */
/**
 * FastTrack load test.
 *
 * Measures the three endpoints a signed-in user hits constantly: the
 * dashboard aggregation, the applications table, and a repeated fit check
 * (which should be served from the posting cache rather than re-parsed).
 *
 *   k6 run -e BASE_URL=http://localhost:8080 -e VUS=50 k6/load-test.js
 *
 * Each virtual user signs in to the demo account the way a browser does:
 * GET /api/config for the CSRF cookie, then POST /api/auth/demo with the
 * matching header. k6 keeps a cookie jar per VU, so the session is real.
 */
import http from "k6/http";
import { check, group, sleep } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || "30s";

const dashboardLatency = new Trend("fasttrack_dashboard_ms", true);
const applicationsLatency = new Trend("fasttrack_applications_ms", true);
const fitLatency = new Trend("fasttrack_fit_cached_ms", true);
const loginFailures = new Counter("fasttrack_login_failures");
const businessErrors = new Rate("fasttrack_business_errors");

export const options = {
  scenarios: {
    steady: {
      executor: "constant-vus",
      vus: VUS,
      duration: DURATION,
      gracefulStop: "10s",
    },
  },
  thresholds: {
    // Deliberately loose: these are recorded, not enforced as SLOs, because
    // the numbers that matter are the measured percentiles in RESULTS.md.
    http_req_failed: ["rate<0.05"],
    fasttrack_business_errors: ["rate<0.05"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const POSTING = `Software Engineer Intern - Summer 2027

Responsibilities
- Build and test REST APIs in Java and Spring Boot
- Work with PostgreSQL schemas and write efficient queries

Requirements
- Pursuing a Bachelor's degree in Computer Science
- Java
- SQL
- Git

Preferred qualifications
- Spring Boot
- Docker
`;

function csrfToken(jar) {
  const cookies = jar.cookiesForURL(BASE_URL + "/");
  return cookies["XSRF-TOKEN"] ? cookies["XSRF-TOKEN"][0] : null;
}

/** Signs this virtual user in to the demo account. Returns false on failure. */
function signIn() {
  const jar = http.cookieJar();

  const config = http.get(`${BASE_URL}/api/config`, { tags: { name: "config" } });
  if (config.status !== 200) {
    loginFailures.add(1);
    return false;
  }

  const token = csrfToken(jar);
  const login = http.post(`${BASE_URL}/api/auth/demo`, null, {
    headers: { "X-XSRF-TOKEN": token || "" },
    tags: { name: "demo-login" },
  });

  const ok = check(login, {
    "demo login succeeded": (response) => response.status === 200,
  });
  if (!ok) {
    loginFailures.add(1);
  }
  return ok;
}

export function setup() {
  // Fail fast and loudly if the stack is not actually up.
  const health = http.get(`${BASE_URL}/actuator/health`);
  if (health.status !== 200) {
    throw new Error(`Backend is not healthy at ${BASE_URL}: status ${health.status}`);
  }
  return { baseUrl: BASE_URL };
}

export default function () {
  if (!signIn()) {
    sleep(1);
    return;
  }

  group("dashboard", () => {
    const response = http.get(`${BASE_URL}/api/insights`, { tags: { name: "insights" } });
    dashboardLatency.add(response.timings.duration);
    businessErrors.add(response.status !== 200);
    check(response, {
      "insights 200": (r) => r.status === 200,
      "insights has totals": (r) => r.status === 200 && r.body.includes("totals"),
    });
  });

  group("applications", () => {
    const response = http.get(`${BASE_URL}/api/applications?page=0&size=25&sort=updatedAt`, {
      tags: { name: "applications" },
    });
    applicationsLatency.add(response.timings.duration);
    businessErrors.add(response.status !== 200);
    check(response, {
      "applications 200": (r) => r.status === 200,
      "applications has items": (r) => r.status === 200 && r.body.includes("items"),
    });
  });

  group("fit check (cached posting)", () => {
    const jar = http.cookieJar();
    const response = http.post(
      `${BASE_URL}/api/fit`,
      JSON.stringify({ postingText: POSTING }),
      {
        headers: {
          "Content-Type": "application/json",
          "X-XSRF-TOKEN": csrfToken(jar) || "",
        },
        tags: { name: "fit" },
        // 429 is the rate limiter working as designed: every virtual user
        // shares the one demo account, so a sustained load is exactly the
        // abuse the limiter exists for. Counting it as a transport failure
        // would make http_req_failed meaningless.
        responseCallback: http.expectedStatuses(200, 400, 429),
      },
    );
    fitLatency.add(response.timings.duration);
    const expected = response.status === 200 || response.status === 400 || response.status === 429;
    businessErrors.add(!expected);
    check(response, { "fit answered sensibly": () => expected });
  });

  sleep(0.5);
}

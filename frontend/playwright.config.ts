import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests run against a running stack - the real frontend, the real
 * API, a real database. They are deliberately few and cover the paths a user
 * actually walks, not every branch: branches are covered by the unit and
 * integration suites, which are far cheaper.
 *
 *   docker compose up -d --build
 *   E2E_BASE_URL=http://localhost:3000 npm run test:e2e
 */
const baseURL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
  ],
});

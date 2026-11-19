#!/usr/bin/env node
/**
 * Captures the screenshots used in the README, from the demo account.
 *
 *   cd frontend && npm run screenshots
 *   cd frontend && BASE_URL=http://localhost:3222 npm run screenshots
 *
 * Everything here comes from the seeded demo data, so the images show the
 * real product with realistic-but-synthetic content - no mock-ups, and no
 * real person's job search.
 */
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const BASE_URL = process.env.BASE_URL ?? "http://localhost:3000";
const OUT_DIR = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "docs",
  "screenshots",
);

const POSTING = `Software Engineer Intern - Summer 2027
Location: Columbus, OH (hybrid)

Responsibilities
- Build and test REST APIs in Java and Spring Boot
- Work with PostgreSQL schemas and write efficient queries
- Deploy services with Docker through our CI/CD pipeline

Requirements
- Pursuing a Bachelor's degree in Computer Science or a related field
- Java
- SQL
- Git

Preferred qualifications
- Spring Boot
- Docker
- Kubernetes
- Experience writing automated tests with JUnit

Graduating December 2026 - June 2027.
`;

async function main() {
  await mkdir(OUT_DIR, { recursive: true });

  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 2,
    colorScheme: "dark",
  });
  const page = await context.newPage();

  const shot = async (name, options = {}) => {
    await page.waitForTimeout(600);
    await page.screenshot({
      path: path.join(OUT_DIR, `${name}.png`),
      fullPage: options.fullPage ?? false,
    });
    console.log(`captured ${name}.png`);
  };

  // 1. Landing, signed out.
  await page.goto(`${BASE_URL}/`, { waitUntil: "networkidle" });
  await shot("landing");

  // 2. Dashboard.
  await page.getByRole("button", { name: "Try the demo" }).click();
  await page.waitForURL("**/dashboard");
  await page.waitForTimeout(1200);
  await shot("dashboard", { fullPage: true });

  // 3. Applications table.
  await page.goto(`${BASE_URL}/applications`, { waitUntil: "networkidle" });
  await shot("applications");

  // 4. Application detail with its timeline - pick one with real history.
  await page.getByRole("table").getByRole("link").nth(2).click();
  await page.waitForURL(/\/applications\/[0-9a-f-]{36}/);
  await shot("application-detail", { fullPage: true });

  // 5. Fit checker, with a real score on screen.
  await page.goto(`${BASE_URL}/fit`, { waitUntil: "networkidle" });
  await page.getByLabel("Paste the posting").fill(POSTING);
  await page.getByRole("button", { name: "Check fit" }).click();
  await page.waitForTimeout(2500);
  await shot("fit-checker", { fullPage: true });

  // 6. Needs review queue.
  await page.goto(`${BASE_URL}/review`, { waitUntil: "networkidle" });
  await shot("needs-review");

  // 7. Settings.
  await page.goto(`${BASE_URL}/settings`, { waitUntil: "networkidle" });
  await shot("settings", { fullPage: true });

  // 8. Mobile view of the dashboard, to show the layout really is responsive.
  const mobile = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    colorScheme: "dark",
  });
  const mobilePage = await mobile.newPage();
  await mobilePage.goto(`${BASE_URL}/`, { waitUntil: "networkidle" });
  await mobilePage.getByRole("button", { name: "Try the demo" }).click();
  await mobilePage.waitForURL("**/dashboard");
  await mobilePage.waitForTimeout(1200);
  await mobilePage.screenshot({ path: path.join(OUT_DIR, "dashboard-mobile.png") });
  console.log("captured dashboard-mobile.png");

  await browser.close();
  console.log(`\nScreenshots written to ${OUT_DIR}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

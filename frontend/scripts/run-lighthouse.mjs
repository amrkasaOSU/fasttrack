#!/usr/bin/env node
/**
 * Lighthouse scores for the pages that matter, including the ones behind a
 * session.
 *
 *   cd frontend && npm run lighthouse
 *   cd frontend && BASE_URL=http://localhost:3222 npm run lighthouse
 *
 * The dashboard, applications list and fit checker all require a session, and
 * Lighthouse cannot log in by itself. So this script drives a real Chromium
 * with Playwright, clicks "Try the demo", and then points Lighthouse at that
 * same browser over the DevTools protocol - the audited page is the real
 * signed-in page, not a redirect to the landing screen.
 */
import { writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";
import lighthouse from "lighthouse";

const BASE_URL = process.env.BASE_URL ?? "http://localhost:3000";
const PORT = Number(process.env.CDP_PORT ?? 9222);
const OUT_DIR = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "lighthouse",
);

const PAGES = [
  { name: "landing", url: "/", requiresSession: false },
  { name: "dashboard", url: "/dashboard", requiresSession: true },
  { name: "applications", url: "/applications", requiresSession: true },
  { name: "fit-checker", url: "/fit", requiresSession: true },
];

/** Desktop settings: this is a tool people use on a laptop. */
const CONFIG = {
  extends: "lighthouse:default",
  settings: {
    formFactor: "desktop",
    screenEmulation: { mobile: false, width: 1440, height: 900, deviceScaleFactor: 1, disabled: false },
    throttling: {
      rttMs: 40,
      throughputKbps: 10 * 1024,
      cpuSlowdownMultiplier: 1,
      requestLatencyMs: 0,
      downloadThroughputKbps: 0,
      uploadThroughputKbps: 0,
    },
    onlyCategories: ["performance", "accessibility", "best-practices", "seo"],
  },
};

function pct(score) {
  return score === null || score === undefined ? "n/a" : Math.round(score * 100);
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });

  const browser = await chromium.launch({
    args: [`--remote-debugging-port=${PORT}`],
  });
  const page = await browser.newPage();

  // Sign in once; the session cookie then applies to every audited page.
  await page.goto(`${BASE_URL}/`, { waitUntil: "load" });
  await page.getByRole("button", { name: "Try the demo" }).click();
  await page.waitForURL("**/dashboard");
  console.log("Signed in to the demo account.\n");

  const results = [];

  for (const target of PAGES) {
    const url = `${BASE_URL}${target.url}`;
    await page.goto(url, { waitUntil: "networkidle" });

    const runnerResult = await lighthouse(url, { port: PORT, output: ["json", "html"] }, CONFIG);
    const { categories } = runnerResult.lhr;

    const row = {
      page: target.name,
      url: target.url,
      performance: pct(categories.performance?.score),
      accessibility: pct(categories.accessibility?.score),
      bestPractices: pct(categories["best-practices"]?.score),
      seo: pct(categories.seo?.score),
      firstContentfulPaint: runnerResult.lhr.audits["first-contentful-paint"]?.displayValue,
      largestContentfulPaint: runnerResult.lhr.audits["largest-contentful-paint"]?.displayValue,
      totalBlockingTime: runnerResult.lhr.audits["total-blocking-time"]?.displayValue,
      cumulativeLayoutShift: runnerResult.lhr.audits["cumulative-layout-shift"]?.displayValue,
    };
    results.push(row);

    await writeFile(path.join(OUT_DIR, `${target.name}.json`), runnerResult.report[0]);
    await writeFile(path.join(OUT_DIR, `${target.name}.html`), runnerResult.report[1]);

    console.log(
      `${target.name.padEnd(14)} perf ${String(row.performance).padStart(3)}  ` +
        `a11y ${String(row.accessibility).padStart(3)}  ` +
        `best-practices ${String(row.bestPractices).padStart(3)}  ` +
        `seo ${String(row.seo).padStart(3)}  ` +
        `FCP ${row.firstContentfulPaint}  LCP ${row.largestContentfulPaint}`,
    );

    // Accessibility failures are the ones worth printing in full.
    const a11yFailures = Object.values(runnerResult.lhr.audits).filter(
      (audit) =>
        audit.score !== null &&
        audit.score < 1 &&
        categories.accessibility?.auditRefs?.some((ref) => ref.id === audit.id),
    );
    for (const failure of a11yFailures) {
      console.log(`                 a11y issue: ${failure.id} - ${failure.title}`);
    }
  }

  await writeFile(
    path.join(OUT_DIR, "summary.json"),
    JSON.stringify({ baseUrl: BASE_URL, generatedAt: new Date().toISOString(), results }, null, 2),
  );

  await browser.close();
  console.log(`\nReports written to ${OUT_DIR}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

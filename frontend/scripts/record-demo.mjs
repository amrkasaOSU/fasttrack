#!/usr/bin/env node
/**
 * Records the short demo loop used at the top of the README.
 *
 *   cd frontend && BASE_URL=http://localhost:3222 npm run demo-gif
 *
 * Playwright records the run as video; ffmpeg turns it into a GIF with a
 * generated palette, which keeps the file small enough for a README without
 * the colour banding a naive conversion produces.
 */
import { mkdir, rm, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { chromium } from "@playwright/test";

const run = promisify(execFile);
const BASE_URL = process.env.BASE_URL ?? "http://localhost:3000";
const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const VIDEO_DIR = path.join(ROOT, "docs", "screenshots", ".video");
const OUT = path.join(ROOT, "docs", "screenshots", "demo.gif");

async function main() {
  await rm(VIDEO_DIR, { recursive: true, force: true });
  await mkdir(VIDEO_DIR, { recursive: true });

  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1280, height: 760 },
    colorScheme: "dark",
    recordVideo: { dir: VIDEO_DIR, size: { width: 1280, height: 760 } },
  });
  const page = await context.newPage();
  const beat = (ms = 900) => page.waitForTimeout(ms);

  // 1. Landing -> demo.
  await page.goto(`${BASE_URL}/`, { waitUntil: "networkidle" });
  await beat(1400);
  await page.getByRole("button", { name: "Try the demo" }).click();
  await page.waitForURL("**/dashboard");
  await beat(1600);

  // 2. Dashboard: totals, then scroll to the charts.
  await page.mouse.wheel(0, 700);
  await beat(1200);
  await page.mouse.wheel(0, 700);
  await beat(1400);

  // 3. Applications table, with a search.
  await page.getByRole("link", { name: "Applications" }).click();
  await page.waitForURL("**/applications");
  await beat(1200);
  await page.getByLabel("Search").type("capital", { delay: 90 });
  await beat(1400);

  // 4. One application's timeline - the thing the product is really about.
  await page.getByRole("table").getByRole("link").first().click();
  await page.waitForURL(/\/applications\/[0-9a-f-]{36}/);
  await beat(2200);

  // 5. Needs review: the confidence threshold doing its job.
  await page.getByRole("link", { name: "Needs review" }).click();
  await page.waitForURL("**/review");
  await beat(2000);

  await context.close();
  await browser.close();

  const files = (await readdir(VIDEO_DIR)).filter((f) => f.endsWith(".webm"));
  if (files.length === 0) {
    throw new Error("Playwright produced no video");
  }
  const video = path.join(VIDEO_DIR, files[0]);

  // Two passes: build a palette from the whole clip, then map onto it.
  const palette = path.join(VIDEO_DIR, "palette.png");
  await run("ffmpeg", ["-y", "-i", video, "-vf", "fps=10,scale=1000:-1:flags=lanczos,palettegen=stats_mode=diff", palette]);
  await run("ffmpeg", ["-y", "-i", video, "-i", palette, "-lavfi",
    "fps=10,scale=1000:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3", OUT]);

  await rm(VIDEO_DIR, { recursive: true, force: true });
  console.log(`wrote ${OUT}`);
}

main().catch((e) => { console.error(e); process.exit(1); });

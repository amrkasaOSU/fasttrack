import { expect, test } from "@playwright/test";

/**
 * The five journeys the product is judged on, end to end against a running
 * stack: signing in to the demo, reading the dashboard, adding an
 * application, correcting a classification, and running a fit check.
 *
 * Each test signs in fresh rather than sharing state, so one failure cannot
 * cascade into the rest.
 */

async function startDemo(page: import("@playwright/test").Page) {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "Every internship application",
  );
  await page.getByRole("button", { name: "Try the demo" }).click();
  await page.waitForURL("**/dashboard");
}

test.describe("FastTrack demo", () => {
  test("a visitor can start the demo and see a populated dashboard", async ({ page }) => {
    await startDemo(page);

    await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
    await expect(page.getByText("Demo account - synthetic data")).toBeVisible();

    // The seeded account has 60 applications. Earlier tests may have added a
    // couple, so assert a floor rather than an exact count - a test that
    // depends on another test's side effects is a flaky test.
    const totals = page.locator("section[aria-label='Totals']");
    await expect(totals.getByText("Applications")).toBeVisible();
    const applicationCount = Number(
      (await totals.textContent())?.match(/Applications(\d+)/)?.[1] ?? "0",
    );
    expect(applicationCount).toBeGreaterThanOrEqual(60);

    // Charts and their text equivalents both render.
    await expect(page.getByText("Applications per week")).toBeVisible();
    await expect(page.getByText("Funnel")).toBeVisible();
    await expect(page.getByText(/Applied: \d+/)).toBeVisible();
    await expect(page.getByText(/Responded: \d+/)).toBeVisible();
  });

  test("the applications table lists, filters and opens an application", async ({ page }) => {
    await startDemo(page);
    await page.getByRole("link", { name: "Applications" }).click();
    await page.waitForURL("**/applications");

    await expect(page.getByRole("heading", { name: "Applications" })).toBeVisible();
    await expect(page.getByText(/\d+ applications/)).toBeVisible();

    // Filtering by search narrows the table.
    await page.getByLabel("Search").fill("stripe");
    await expect(page.getByRole("table")).toContainText("Stripe", { timeout: 10_000 });

    await page.getByLabel("Search").fill("");
    await page.getByRole("table").getByRole("link").first().click();
    await page.waitForURL(/\/applications\/[0-9a-f-]{36}/);
    await expect(page.getByText("Status timeline")).toBeVisible();
  });

  test("a user can add an application by hand and see it in the table", async ({ page }) => {
    await startDemo(page);
    await page.goto("/applications");

    const company = `E2E Test Co ${Date.now()}`;
    await page.getByRole("button", { name: "Add application" }).click();
    await page.getByLabel("Company").fill(company);
    await page.getByLabel("Position").fill("Software Engineer Intern - Summer 2027");
    await page.getByLabel("Location").fill("Columbus, OH");
    await page.getByRole("button", { name: "Add application" }).last().click();

    await expect(page.getByText(`Added ${company}`)).toBeVisible();

    await page.getByLabel("Search").fill(company);
    await expect(page.getByRole("table")).toContainText(company, { timeout: 10_000 });
  });

  test("a user can correct a classification and the queue shrinks", async ({ page }) => {
    await startDemo(page);
    await page.getByRole("link", { name: "Needs review" }).click();
    await page.waitForURL("**/review");

    await expect(page.getByRole("heading", { name: "Needs review" })).toBeVisible();

    const heading = page.getByRole("heading", { name: /Uncertain emails \(\d+\)/ });
    await expect(heading).toBeVisible();
    const before = Number(
      (await heading.textContent())?.match(/\((\d+)\)/)?.[1] ?? "0",
    );
    expect(before).toBeGreaterThan(0);

    // Confirm the first one as correct - the simplest honest correction.
    await page.getByRole("button", { name: "It was right" }).first().click();
    await expect(page.getByText("Confirmed")).toBeVisible();

    await expect
      .poll(async () => {
        const text = await page
          .getByRole("heading", { name: /Uncertain emails \(\d+\)/ })
          .textContent()
          .catch(() => "(0)");
        return Number(text?.match(/\((\d+)\)/)?.[1] ?? "0");
      }, { timeout: 15_000 })
      .toBeLessThan(before);
  });

  test("the fit checker explains itself and refuses to score without a resume", async ({ page }) => {
    await startDemo(page);
    await page.getByRole("link", { name: "Fit checker" }).click();
    await page.waitForURL("**/fit");

    await expect(page.getByRole("heading", { name: "Fit checker" })).toBeVisible();
    // The scoring legend is always shown, so the number is never a black box.
    await expect(page.getByText(/50% required skills/)).toBeVisible();

    await page
      .getByLabel("Paste the posting")
      .fill(
        "Software Engineer Intern - Summer 2027\n\nRequirements\n- Java\n- SQL\n- Pursuing a Bachelor's degree in Computer Science\n\nPreferred qualifications\n- Spring Boot\n- Docker\n",
      );
    await page.getByRole("button", { name: "Check fit" }).click();

    // The demo account has no resume, so the product must say so plainly.
    await expect(page.getByText(/Upload a resume first/)).toBeVisible();
  });

  test("settings reports what this deployment can and cannot do", async ({ page }) => {
    await startDemo(page);
    await page.getByRole("link", { name: "Settings" }).click();
    await page.waitForURL("**/settings");

    await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
    await expect(page.getByText(/Gmail/).first()).toBeVisible();
    await expect(page.getByText(/shared demo account/)).toBeVisible();
  });

  test("signing out ends the session", async ({ page }) => {
    await startDemo(page);
    await page.getByRole("button", { name: "Sign out" }).click();
    await page.waitForURL("/");

    // The protected area must bounce back to the landing page.
    await page.goto("/dashboard");
    await page.waitForURL("/");
    await expect(page.getByRole("button", { name: "Try the demo" })).toBeVisible();
  });
});

import { expect, test } from "@playwright/test";
import {
  REPO,
  SETTINGS,
  expectNoA11yViolations,
  json,
  mockSignedInApi,
} from "./fixtures";

test.beforeEach(async ({ page }) => {
  await mockSignedInApi(page);
});

test("dashboard lists repositories and is accessible", async ({ page }) => {
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();
  await expect(page.getByText(REPO.fullName).first()).toBeVisible();
  await expectNoA11yViolations(page);
});

test("repository page shows the finding summary", async ({ page }) => {
  await page.goto(`/repo/${REPO.id}`);
  const summary = page.getByRole("region", { name: "Findings by rule" });
  await expect(summary.getByRole("row", { name: /Hardcoded Secret/ })).toContainText("25%");
  await page.getByRole("group", { name: "Summary period" }).getByRole("button", { name: "90d" }).click();
  await expect(page.getByText("in the last 90 days", { exact: false })).toBeVisible();
  await expectNoA11yViolations(page);
});

test.describe("repository settings", () => {
  test("validates locally, then saves", async ({ page }) => {
    const puts: unknown[] = [];
    await page.route(`**/api/repos/${REPO.id}/settings`, async (route, request) => {
      if (request.method() === "PUT") {
        const body = request.postDataJSON();
        puts.push(body);
        return json(route, 200, body);
      }
      return json(route, 200, SETTINGS);
    });

    await page.goto(`/repo/${REPO.id}/settings`);
    await expect(page.getByRole("heading", { level: 1, name: "Review settings" })).toBeVisible();
    await expectNoA11yViolations(page);

    const confidence = page.getByLabel("Minimum confidence");
    await confidence.fill("1.5");
    await page.getByRole("button", { name: "Save settings" }).click();
    await expect(confidence).toHaveAttribute("aria-invalid", "true");
    await expect(confidence).toBeFocused();
    await expect(page.getByText("Enter a number from 0 to 1")).toBeVisible();
    expect(puts).toHaveLength(0);

    await confidence.fill("0.7");
    await page.getByLabel("All rules (including rules added later)").uncheck();
    await page.getByRole("checkbox", { name: /SQL Injection/ }).check();
    await page.getByLabel("Minimum severity").selectOption("major");
    await page.getByRole("checkbox", { name: "Java", exact: true }).uncheck();
    await page.getByRole("button", { name: "Save settings" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Settings saved." })).toBeVisible();
    expect(puts).toEqual([
      {
        enabledRules: ["SECURITY_SQL_INJECTION"],
        minSeverity: "major",
        minConfidence: 0.7,
        postComments: true,
        inlineComments: false,
        languages: ["python", "javascript", "typescript"],
      },
    ]);
  });

  test("shows the API's validation error", async ({ page }) => {
    await page.route(`**/api/repos/${REPO.id}/settings`, (route, request) =>
      request.method() === "PUT"
        ? json(route, 400, {
            error: "VALIDATION_FAILED",
            message: "languages: must not be empty",
            status: 400,
          })
        : json(route, 200, SETTINGS),
    );
    await page.goto(`/repo/${REPO.id}/settings`);
    await page.getByRole("button", { name: "Save settings" }).click();
    const alert = page.getByRole("alert").filter({ hasText: "Settings were not saved" });
    await expect(alert).toContainText("languages: must not be empty");
    await expectNoA11yViolations(page);
  });
});

test("diagnostics explains a degraded state", async ({ page }) => {
  await page.goto("/diagnostics");
  const banner = page.getByTestId("diagnostics-banner");
  await expect(banner).toContainText("Reviews may be delayed");
  await expect(banner).toContainText("ML worker");
  await expect(page.getByTestId("component-mlWorker")).toContainText("Degraded");
  await expect(page.getByTestId("component-mlWorker")).toContainText("fallback");
  await expect(page.getByText("Dead-lettered webhooks")).toBeVisible();
  await expectNoA11yViolations(page);
});

test("diagnostics shows an error when the endpoint fails", async ({ page }) => {
  await page.route("**/api/diagnostics", (route) =>
    json(route, 503, { error: "SERVICE_UNAVAILABLE", message: "Diagnostics unavailable" }),
  );
  await page.goto("/diagnostics");
  await expect(
    page.getByRole("alert").filter({ hasText: "Couldn’t load diagnostics" }),
  ).toContainText("Diagnostics unavailable");
});

test("settings page and taxonomy page are accessible", async ({ page }) => {
  await page.goto("/settings");
  await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();
  await expect(page.getByRole("link", { name: `Review settings for ${REPO.fullName}` })).toBeVisible();
  await expectNoA11yViolations(page);

  await page.goto("/taxonomy");
  await expect(page.getByRole("cell", { name: "SECURITY_SQL_INJECTION" })).toBeVisible();
  await expectNoA11yViolations(page);
});

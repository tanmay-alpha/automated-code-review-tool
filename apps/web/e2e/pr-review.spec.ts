import { expect, test } from "@playwright/test";
import { PR_ID, expectNoA11yViolations, json, mockSignedInApi } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await mockSignedInApi(page);
});

test("shows the review with explanation fields and run history", async ({ page }) => {
  await page.goto(`/pr/${PR_ID}`);

  await expect(page.getByRole("heading", { level: 1 })).toContainText("Add user export");
  await expect(page.getByTestId("head-sha")).toHaveText("9fceb02");
  await expect(page.getByTestId("analysis-status")).toHaveText("Reviewed");

  const secret = page.getByRole("article", { name: "Hardcoded Secret" });
  await expect(secret.getByTestId("finding-location")).toHaveText("app/settings.py:12");
  await expect(secret).toContainText("95% confidence");
  await expect(secret).toContainText('API_KEY = "[REDACTED]"');
  await expect(secret).toContainText("Read the key from an environment variable");
  await expect(secret).toContainText("Test fixtures with fake keys also match");

  const runs = page.getByRole("region", { name: "Review runs", exact: true });
  await expect(runs.getByTestId("review-run")).toHaveCount(2);
  await expect(runs.getByRole("link", { name: /View comment/ })).toHaveAttribute(
    "href",
    "https://github.com/octocat/hello-world/pull/7#issuecomment-99",
  );
  await expect(runs).toContainText("ML worker timed out");

  await expectNoA11yViolations(page);
});

test("marks a false positive with a required reason, using the keyboard, then reopens", async ({ page }) => {
  const actions: unknown[] = [];
  await page.route("**/api/scan/action", async (route, request) => {
    const body = request.postDataJSON() as { findingId: string; action: string; reason?: string };
    actions.push(body);
    const status = body.action === "reopen" ? "open" : body.action === "false_positive" ? "false_positive" : body.action;
    return json(route, 200, {
      findingId: body.findingId,
      status,
      reason: body.reason ?? null,
      triagedAt: body.action === "reopen" ? null : new Date().toISOString(),
      triagedBy: "octocat",
    });
  });

  await page.goto(`/pr/${PR_ID}`);
  const card = page.getByRole("article", { name: "Hardcoded Secret" });
  const fpButton = card.getByRole("button", { name: "False positive…" });

  await fpButton.focus();
  await expect(fpButton).toBeFocused();
  await page.keyboard.press("Enter");

  const dialog = page.getByRole("dialog", { name: "Mark as false positive" });
  await expect(dialog).toBeVisible();
  const reason = dialog.getByLabel(/Reason/);
  await expect(reason).toBeFocused();
  await expectNoA11yViolations(page);

  // Escape cancels without a request and returns focus to the trigger.
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(fpButton).toBeFocused();
  expect(actions).toHaveLength(0);

  await page.keyboard.press("Enter");
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "Mark false positive" }).click();
  await expect(dialog.getByText("A reason is required.")).toBeVisible();
  expect(actions).toHaveLength(0);

  await reason.fill("Fake key used only by the unit-test fixture");
  await reason.press("Tab");
  await page.keyboard.press("Tab"); // Cancel → submit button
  await expect(dialog.getByRole("button", { name: "Mark false positive" })).toBeFocused();
  await page.keyboard.press("Enter");

  await expect(dialog).toBeHidden();
  expect(actions).toEqual([
    {
      findingId: "f-secret",
      action: "false_positive",
      reason: "Fake key used only by the unit-test fixture",
    },
  ]);
  await expect(card.getByTestId("finding-status")).toHaveText("False positive");
  await expect(card.getByTestId("finding-triage")).toContainText("Fake key used only by the unit-test fixture");
  const reopen = card.getByRole("button", { name: "Reopen" });
  await expect(reopen).toBeFocused();

  // The triaged filter now includes it.
  await page.getByRole("button", { name: "Triaged (2)" }).click();
  await expect(page.getByRole("article")).toHaveCount(2);
  await page.getByRole("button", { name: "All (2)" }).click();

  await reopen.click();
  await expect(card.getByTestId("finding-status")).toHaveText("Open");
  expect(actions[1]).toEqual({ findingId: "f-secret", action: "reopen" });
});

test("shows a failed triage request without changing the finding", async ({ page }) => {
  await page.route("**/api/scan/action", (route) =>
    json(route, 403, { error: "CSRF_ORIGIN_REJECTED", message: "Origin not allowed" }),
  );
  await page.goto(`/pr/${PR_ID}`);
  const card = page.getByRole("article", { name: "Hardcoded Secret" });
  await card.getByRole("button", { name: "Accept" }).click();
  await expect(card.getByRole("alert")).toContainText("rejected");
  await expect(card.getByTestId("finding-status")).toHaveText("Open");
});

test("shows a not-found state for a pull request outside your repositories", async ({ page }) => {
  await page.route(`**/api/reviews/${PR_ID}`, (route) => json(route, 404, { error: "NOT_FOUND", message: "x" }));
  await page.route(`**/api/reviews/${PR_ID}/runs`, (route) => json(route, 404, { error: "NOT_FOUND", message: "x" }));
  await page.goto(`/pr/${PR_ID}`);
  await expect(
    page.getByRole("alert").filter({ hasText: "not in one of your repositories" }),
  ).toContainText("Couldn’t load review");
  await expect(page.getByRole("article")).toHaveCount(0);
});

import { devices, expect, test, type Page } from "@playwright/test";
import { PR_ID, REPO, expectNoA11yViolations, mockSignedInApi } from "./fixtures";

const { viewport, userAgent, deviceScaleFactor, isMobile, hasTouch } = devices["Pixel 7"];
test.use({ viewport, userAgent, deviceScaleFactor, isMobile, hasTouch });

test.beforeEach(async ({ page }) => {
  await mockSignedInApi(page);
});

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

test("uses the compact navigation and fits the viewport", async ({ page }) => {
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();

  const nav = page.getByRole("navigation", { name: "Main" });
  await expect(nav).toBeVisible();
  await expect(nav.getByRole("link", { name: "Dashboard" })).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("button", { name: "Log out" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expectNoA11yViolations(page);

  await nav.getByRole("link", { name: "Diagnostics" }).click();
  await expect(page).toHaveURL(/\/diagnostics$/);
  await expectNoHorizontalOverflow(page);
});

test("PR review and repository settings fit a phone screen", async ({ page }) => {
  await page.goto(`/pr/${PR_ID}`);
  await expect(page.getByRole("article", { name: "Hardcoded Secret" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await expectNoA11yViolations(page);

  await page.goto(`/repo/${REPO.id}/settings`);
  await expect(page.getByRole("button", { name: "Save settings" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
});

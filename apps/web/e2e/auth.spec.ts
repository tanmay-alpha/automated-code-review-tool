import { expect, test } from "@playwright/test";
import { USER, expectNoA11yViolations, json, mockSignedInApi } from "./fixtures";

test("redirects to the landing page when there is no session", async ({ page }) => {
  let refreshes = 0;
  await page.route("**/api/auth/me", (route) => json(route, 401, { error: "UNAUTHORIZED" }));
  await page.route("**/api/auth/refresh", (route) => {
    refreshes += 1;
    return json(route, 401, { error: "UNAUTHORIZED" });
  });

  await page.goto("/dashboard");

  await expect(page).toHaveURL(/\/$/);
  const login = page.getByRole("link", { name: "Login with GitHub" });
  await expect(login).toBeVisible();
  // Same-origin, so the OAuth state cookie lands on the dashboard's origin.
  await expect(login).toHaveAttribute("href", "/api/auth/github");
  expect(refreshes).toBe(1);
  await expectNoA11yViolations(page);
});

test("refreshes an expired access token once and stays signed in", async ({ page }) => {
  await mockSignedInApi(page);
  let meCalls = 0;
  let refreshes = 0;
  await page.route("**/api/auth/me", (route) => {
    meCalls += 1;
    return meCalls === 1 ? json(route, 401, { error: "UNAUTHORIZED" }) : json(route, 200, USER);
  });
  await page.route("**/api/auth/refresh", (route, request) => {
    refreshes += 1;
    expect(request.method()).toBe("POST");
    return route.fulfill({
      status: 200,
      headers: { "set-cookie": "accessToken=new; Path=/; HttpOnly; SameSite=Lax" },
      body: "",
    });
  });

  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();
  await expect(page).toHaveURL(/\/dashboard$/);
  expect(refreshes).toBe(1);
  expect(meCalls).toBe(2);
});

test("explains OAuth failures on the landing page", async ({ page }) => {
  await page.goto("/?authError=oauth_state_invalid");
  await expect(page.getByRole("alert").filter({ hasText: "Couldn’t sign you in" })).toContainText(
    "sign-in link expired",
  );
  await expectNoA11yViolations(page);
});

test("unmocked API calls reach the same-origin proxy and fail closed with JSON", async ({ request }) => {
  // playwright.config.ts points API_INTERNAL_URL at a closed port.
  const res = await request.get("/api/auth/me", { headers: { cookie: "accessToken=x" } });
  expect(res.status()).toBe(502);
  expect(await res.json()).toMatchObject({ error: "UPSTREAM_UNAVAILABLE" });
  expect(await res.text()).not.toContain("127.0.0.1");
});

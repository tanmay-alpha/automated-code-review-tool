import { defineConfig, devices } from "@playwright/test";

const PORT = Number(process.env.E2E_PORT ?? 3100);
const BASE_URL = `http://127.0.0.1:${PORT}`;

/**
 * End-to-end tests against the production build (`npm run build` first).
 * Browser calls to `/api/*` are answered by `page.route` mocks; anything left
 * unmocked reaches the real same-origin proxy, which points at a closed port
 * so it fails fast with the proxy's 502 instead of touching a real API.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: BASE_URL,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npx next start --hostname 127.0.0.1 --port ${PORT}`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: {
      API_INTERNAL_URL: "http://127.0.0.1:1",
      NEXT_TELEMETRY_DISABLED: "1",
    },
  },
});

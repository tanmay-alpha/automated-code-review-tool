import AxeBuilder from "@axe-core/playwright";
import { expect, type Page, type Request, type Route } from "@playwright/test";
import type {
  DiagnosticsResponse,
  FindingSummaryResponse,
  PaginatedPRResponse,
  PullRequestSummary,
  QualityTrendResponse,
  RepoResponse,
  RepoSettings,
  ReviewDetailResponse,
  ReviewRun,
  UserResponse,
} from "../src/lib/types";

export const USER: UserResponse = {
  id: "00000000-0000-0000-0000-000000000001",
  githubUsername: "octocat",
  avatarUrl: null,
  createdAt: "2026-01-01T00:00:00Z",
};

export const REPO: RepoResponse = {
  id: "11111111-1111-1111-1111-111111111111",
  fullName: "octocat/hello-world",
  githubId: 42,
  active: true,
  createdAt: "2026-10-01T00:00:00Z",
  totalPrsReviewed: 3,
  lastReviewedAt: "2026-11-09T12:00:00Z",
  latestQualityScore: 72,
};

export const PR_ID = "22222222-2222-2222-2222-222222222222";
export const HEAD_SHA = "9fceb02d0ae598e95dc970b74767f19372d61af8";

export function review(): ReviewDetailResponse {
  return {
    id: PR_ID,
    githubPrNumber: 7,
    title: "Add user export",
    authorGithub: "monalisa",
    status: "reviewed",
    qualityScore: 70,
    createdAt: "2026-11-09T11:00:00Z",
    reviewedAt: "2026-11-09T12:00:00Z",
    errorMessage: null,
    headSha: HEAD_SHA,
    githubPrUrl: "https://github.com/octocat/hello-world/pull/7",
    repoId: REPO.id,
    repoFullName: REPO.fullName,
    repoOwnerLogin: "octocat",
    repoOwnerAvatar: null,
    findings: [
      {
        id: "f-secret",
        filePath: "app/settings.py",
        lineStart: 12,
        lineEnd: 12,
        antiPattern: "SECURITY_HARDCODED_SECRET",
        category: "SECURITY",
        severity: "critical",
        confidence: 0.95,
        explanation: "A credential is assigned to a string literal.",
        codeSnippet: null,
        status: "open",
        triageReason: null,
        triagedAt: null,
        evidence: 'API_KEY = "[REDACTED]"',
        remediation: "Read the key from an environment variable or a secrets manager.",
        caveat: "Test fixtures with fake keys also match this rule.",
      },
      {
        id: "f-print",
        filePath: "app/export.py",
        lineStart: 30,
        lineEnd: 31,
        antiPattern: "MAINTAINABILITY_PRINT_STATEMENT",
        category: "MAINTAINABILITY",
        severity: "minor",
        confidence: 0.6,
        explanation: "print() left in application code.",
        codeSnippet: null,
        status: "accepted",
        triageReason: null,
        triagedAt: "2026-11-09T13:00:00Z",
        evidence: "print(rows)",
        remediation: "Use the logging module.",
        caveat: null,
      },
    ],
  };
}

export const RUNS: ReviewRun[] = [
  {
    deliveryId: "aaaaaaaa-0000-0000-0000-000000000002",
    headSha: HEAD_SHA,
    action: "synchronize",
    status: "completed",
    attemptCount: 1,
    lastError: null,
    receivedAt: "2026-11-09T11:59:00Z",
    completedAt: "2026-11-09T12:00:00Z",
    publication: {
      status: "published",
      commentUrl: "https://github.com/octocat/hello-world/pull/7#issuecomment-99",
      publishedHeadSha: HEAD_SHA,
    },
  },
  {
    deliveryId: "aaaaaaaa-0000-0000-0000-000000000001",
    headSha: "1111111111111111111111111111111111111111",
    action: "opened",
    status: "superseded",
    attemptCount: 2,
    lastError: "ML worker timed out",
    receivedAt: "2026-11-09T11:00:00Z",
    completedAt: "2026-11-09T11:05:00Z",
    publication: null,
  },
];

export const SETTINGS: RepoSettings = {
  enabledRules: [],
  minSeverity: "minor",
  minConfidence: 0,
  postComments: true,
  inlineComments: false,
  languages: ["python", "javascript", "typescript", "java"],
};

export const DIAGNOSTICS_DEGRADED: DiagnosticsResponse = {
  status: "degraded",
  checkedAt: new Date().toISOString(),
  components: {
    database: { status: "up", detail: null },
    redis: { status: "up", detail: null },
    mlWorker: { status: "degraded", engine: "fallback", detail: "Model checkpoint not loaded; rule engine only." },
    github: { status: "up", detail: null },
  },
  queue: {
    webhooksPending: 4,
    webhooksRetrying: 1,
    webhooksDeadLetter: 2,
    oldestPendingSeconds: 340,
    outboxPending: 0,
    outboxFailed: 0,
  },
};

const PRS: PaginatedPRResponse<PullRequestSummary> = {
  content: [
    {
      id: PR_ID,
      githubPrNumber: 7,
      title: "Add user export",
      authorGithub: "monalisa",
      headSha: HEAD_SHA,
      githubPrUrl: "https://github.com/octocat/hello-world/pull/7",
      status: "reviewed",
      qualityScore: 70,
      githubCommentId: 99,
      errorMessage: null,
      reviewedAt: "2026-11-09T12:00:00Z",
      createdAt: "2026-11-09T11:00:00Z",
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  first: true,
  last: true,
};

function trend(days: number): QualityTrendResponse {
  return {
    repoId: REPO.id,
    fullName: REPO.fullName,
    topAntiPattern: "SECURITY_HARDCODED_SECRET",
    trend: Array.from({ length: Math.min(days, 7) }, (_, i) => ({
      date: `2026-11-0${i + 1}`,
      avgQuality: 60 + i * 2,
      prsReviewed: 1,
      criticalCount: i % 2,
      majorCount: 1,
      minorCount: 2,
    })),
  };
}

const SUMMARY: FindingSummaryResponse = {
  repoId: REPO.id,
  days: 30,
  byRule: [
    { antiPattern: "SECURITY_HARDCODED_SECRET", total: 6, open: 2, accepted: 1, dismissed: 0, fixed: 2, falsePositive: 1 },
    { antiPattern: "MAINTAINABILITY_PRINT_STATEMENT", total: 3, open: 1, accepted: 2, dismissed: 0, fixed: 0, falsePositive: 0 },
  ],
  byEngine: [{ engine: "rules", total: 9 }],
};

export function json(route: Route, status: number, body?: unknown, headers: Record<string, string> = {}) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers,
    body: body === undefined ? "" : JSON.stringify(body),
  });
}

/**
 * Installs a signed-in API. Specific tests add their own `page.route` calls
 * afterwards; Playwright matches the most recently registered route first.
 */
export async function mockSignedInApi(page: Page): Promise<{ unhandled: string[] }> {
  const unhandled: string[] = [];
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route: Route, request: Request) => {
      const url = new URL(request.url());
      const p = url.pathname;
      const method = request.method();
      if (p === "/api/auth/me") return json(route, 200, USER);
      if (p === "/api/repos" && method === "GET") return json(route, 200, [REPO]);
      if (p === `/api/repos/${REPO.id}` && method === "GET") return json(route, 200, REPO);
      if (p === `/api/repos/${REPO.id}/prs`) return json(route, 200, PRS);
      if (p === `/api/repos/${REPO.id}/settings` && method === "GET") return json(route, 200, SETTINGS);
      if (p === "/api/metrics/quality-trend") return json(route, 200, trend(Number(url.searchParams.get("days") ?? 30)));
      if (p === "/api/metrics/finding-summary") {
        return json(route, 200, { ...SUMMARY, days: Number(url.searchParams.get("days") ?? 30) });
      }
      if (p === `/api/reviews/${PR_ID}`) return json(route, 200, review());
      if (p === `/api/reviews/${PR_ID}/runs`) return json(route, 200, RUNS);
      if (p === "/api/diagnostics") return json(route, 200, DIAGNOSTICS_DEGRADED);
      if (p === "/api/auth/api-keys") return json(route, 200, []);
      unhandled.push(`${method} ${p}`);
      return json(route, 404, { error: "NOT_FOUND", message: `Unmocked ${method} ${p}` });
    },
  );
  return { unhandled };
}

/** Fails with a readable list when axe finds WCAG 2.1 A/AA violations. */
export async function expectNoA11yViolations(page: Page, include?: string) {
  // After a client-side redirect Next.js streams <title> in with the RSC
  // payload; scanning before it lands reports a missing title that users
  // never see. A non-empty title proves the navigation has settled.
  await expect(page).toHaveTitle(/\S/);
  let builder = new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]);
  if (include) builder = builder.include(include);
  const { violations } = await builder.analyze();
  const summary = violations.map(
    (v) => `${v.id} (${v.impact}): ${v.help}\n  ${v.nodes.map((n) => n.target.join(" ")).join("\n  ")}`,
  );
  expect(summary, summary.join("\n")).toEqual([]);
}

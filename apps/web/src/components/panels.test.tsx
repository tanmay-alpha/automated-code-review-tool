import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SWRConfig } from "swr";
import type { ReactNode } from "react";
import { DiagnosticsView, formatAge } from "@/components/DiagnosticsView";
import {
  FindingSummaryPanel,
  falsePositiveRate,
  summaryTotals,
} from "@/components/FindingSummaryPanel";
import { ReviewRunsPanel } from "@/components/ReviewRunsPanel";
import { getFindingSummary, getReviewRuns } from "@/lib/api";
import type * as Api from "@/lib/api";
import type { DiagnosticsResponse, FindingSummaryResponse, ReviewRun } from "@/lib/types";

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof Api>();
  return { ...actual, getReviewRuns: vi.fn(), getFindingSummary: vi.fn() };
});

const runsMock = vi.mocked(getReviewRuns);
const summaryMock = vi.mocked(getFindingSummary);

afterEach(() => {
  runsMock.mockReset();
  summaryMock.mockReset();
});

/** Fresh SWR cache per test so responses do not leak between cases. */
function withSWR(ui: ReactNode) {
  return render(<SWRConfig value={{ provider: () => new Map(), dedupingInterval: 0 }}>{ui}</SWRConfig>);
}

const RUN: ReviewRun = {
  deliveryId: "11111111-2222-3333-4444-555555555555",
  headSha: "abcdef0123456789",
  action: "synchronize",
  status: "completed",
  attemptCount: 1,
  lastError: null,
  receivedAt: "2026-11-10T10:00:00Z",
  completedAt: "2026-11-10T10:00:05Z",
  publication: {
    status: "published",
    commentUrl: "https://github.com/o/r/pull/7#issuecomment-1",
    publishedHeadSha: "abcdef0123456789",
  },
};

describe("ReviewRunsPanel", () => {
  it("lists runs with head SHA, status, attempts, error and comment link", async () => {
    runsMock.mockResolvedValue([
      RUN,
      {
        ...RUN,
        deliveryId: "99999999-0000-0000-0000-000000000000",
        status: "dead_letter",
        attemptCount: 5,
        lastError: "ML worker timed out",
        completedAt: null,
        publication: null,
      },
    ]);
    withSWR(<ReviewRunsPanel prId="pr-1" />);

    const rows = await screen.findAllByTestId("review-run");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("abcdef0")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Completed")).toBeInTheDocument();
    expect(within(rows[0]).getByRole("link", { name: /View comment/ })).toHaveAttribute(
      "href",
      "https://github.com/o/r/pull/7#issuecomment-1",
    );
    expect(within(rows[1]).getByText("Dead Letter")).toBeInTheDocument();
    expect(within(rows[1]).getByText("ML worker timed out")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Not published")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Review runs (2)" })).toBeInTheDocument();
    expect(runsMock).toHaveBeenCalledWith("pr-1");
  });

  it("never links an untrusted comment URL", async () => {
    runsMock.mockResolvedValue([
      { ...RUN, publication: { status: "published", commentUrl: "javascript:alert(1)", publishedHeadSha: null } },
    ]);
    withSWR(<ReviewRunsPanel prId="pr-1" />);
    await screen.findByTestId("review-run");
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("shows an empty state", async () => {
    runsMock.mockResolvedValue([]);
    withSWR(<ReviewRunsPanel prId="pr-1" />);
    expect(await screen.findByText(/No webhook deliveries recorded/)).toBeInTheDocument();
  });

  it("shows an error state", async () => {
    runsMock.mockRejectedValue(new Error("boom"));
    withSWR(<ReviewRunsPanel prId="pr-1" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("boom");
  });
});

const SUMMARY: FindingSummaryResponse = {
  repoId: "r-1",
  days: 30,
  byRule: [
    { antiPattern: "READABILITY_MAGIC_NUMBER", total: 2, open: 2, accepted: 0, dismissed: 0, fixed: 0, falsePositive: 0 },
    { antiPattern: "SECURITY_HARDCODED_SECRET", total: 10, open: 2, accepted: 3, dismissed: 1, fixed: 2, falsePositive: 2 },
  ],
  byEngine: [{ engine: "rules", total: 12 }],
};

describe("FindingSummaryPanel", () => {
  it("computes the false-positive rate over triaged findings only", () => {
    expect(falsePositiveRate(SUMMARY.byRule[1])).toBe(25);
    expect(falsePositiveRate(SUMMARY.byRule[0])).toBeNull();
    expect(summaryTotals(SUMMARY.byRule)).toMatchObject({ total: 12, open: 4, falsePositive: 2 });
  });

  it("renders rules sorted by volume with totals and engines, and switches period", async () => {
    const user = userEvent.setup();
    summaryMock.mockResolvedValue(SUMMARY);
    withSWR(<FindingSummaryPanel repoId="r-1" />);

    const rows = await screen.findAllByTestId("summary-row");
    expect(within(rows[0]).getByText("Hardcoded Secret")).toBeInTheDocument();
    expect(within(rows[0]).getByText("25%")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Magic Number")).toBeInTheDocument();
    expect(screen.getByText("Rules:", { exact: false })).toBeInTheDocument();
    expect(summaryMock).toHaveBeenLastCalledWith("r-1", 30);

    const ninety = screen.getByRole("button", { name: "90d" });
    await user.click(ninety);
    expect(ninety).toHaveAttribute("aria-pressed", "true");
    expect(summaryMock).toHaveBeenLastCalledWith("r-1", 90);
  });

  it("shows an empty state", async () => {
    summaryMock.mockResolvedValue({ ...SUMMARY, byRule: [], byEngine: [] });
    withSWR(<FindingSummaryPanel repoId="r-1" />);
    expect(await screen.findByText("No findings in the last 30 days.")).toBeInTheDocument();
  });
});

const DIAG: DiagnosticsResponse = {
  status: "degraded",
  checkedAt: "2026-11-10T10:00:00Z",
  components: {
    database: { status: "up", detail: null },
    redis: { status: "up", detail: "PONG" },
    mlWorker: { status: "degraded", engine: "fallback", detail: "Model not loaded; rule engine only" },
    github: { status: "up", detail: null },
  },
  queue: {
    webhooksPending: 3,
    webhooksRetrying: 1,
    webhooksDeadLetter: 2,
    oldestPendingSeconds: 125,
    outboxPending: 0,
    outboxFailed: 0,
  },
};

describe("DiagnosticsView", () => {
  it("explains a degraded state and names the affected component", () => {
    render(<DiagnosticsView data={DIAG} />);
    const banner = screen.getByTestId("diagnostics-banner");
    expect(banner).toHaveTextContent("Reviews may be delayed");
    expect(banner).toHaveTextContent("Affected: ML worker");
    const ml = screen.getByTestId("component-mlWorker");
    expect(within(ml).getByText("Degraded")).toBeInTheDocument();
    expect(within(ml).getByText("fallback")).toBeInTheDocument();
    expect(within(ml).getByText("Model not loaded; rule engine only")).toBeInTheDocument();
    expect(screen.getByText("Dead-lettered webhooks").nextSibling).toHaveTextContent("2");
    expect(screen.getByText("Oldest pending").nextSibling).toHaveTextContent("2m");
  });

  it("reports an outage", () => {
    render(
      <DiagnosticsView
        data={{ ...DIAG, status: "down", components: { ...DIAG.components, database: { status: "down", detail: "Connection refused" } } }}
      />,
    );
    expect(screen.getByTestId("diagnostics-banner")).toHaveTextContent("Reviews are not running");
    expect(screen.getByTestId("diagnostics-banner")).toHaveTextContent("Database, ML worker");
  });

  it("formats queue ages", () => {
    expect(formatAge(null)).toBe("—");
    expect(formatAge(30)).toBe("30s");
    expect(formatAge(7200)).toBe("2h");
    expect(formatAge(3 * 86_400)).toBe("3d");
  });
});

import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { DiffViewer } from "@/components/DiffViewer";
import type { FindingDto } from "@/lib/types";

// react-diff-viewer-continued does a fair amount of layout work and
// pulls in some browser-only APIs (ResizeObserver is used by its gutter).
// For unit tests we mock it with a tiny component that renders a
// deterministic markup we can assert against.
vi.mock("react-diff-viewer-continued", () => {
  return {
    default: function MockDiff(props: {
      oldValue: string;
      newValue: string;
    }) {
      // Render one <div data-testid="diff-row" data-line="N"> per non-empty
      // line. This mirrors the contract our component relies on (each row
      // is keyed by its line number so we can attach findings to it).
      const lines = (props.newValue ?? "").split("\n").filter(Boolean);
      return (
        <div data-testid="mock-diff">
          {lines.map((line, i) => (
            <div key={i} data-testid="diff-row" data-line={String(i + 1)}>
              <td>{i + 1}</td>
              <span>{line}</span>
            </div>
          ))}
        </div>
      );
    },
  };
});

const SAMPLE_DIFF = `line one
line two
line three
line four
line five`;

const CRITICAL: FindingDto = {
  id: "f-1",
  filePath: "src/api/users.py",
  lineStart: 2,
  lineEnd: 2,
  antiPattern: "PERF_N_PLUS_1",
  category: "Performance",
  severity: "critical",
  confidence: 0.95,
  explanation: "N+1 query pattern detected inside a loop.",
  codeSnippet: null,
};

const MINOR: FindingDto = {
  id: "f-2",
  filePath: "src/api/users.py",
  lineStart: 4,
  lineEnd: 4,
  antiPattern: "MAINTAINABILITY_DUPLICATE_CODE",
  category: "Maintainability",
  severity: "minor",
  confidence: 0.6,
  explanation: "Duplicated block.",
  codeSnippet: null,
};

const FILE_LEVEL: FindingDto = {
  id: "f-3",
  filePath: "src/api/users.py",
  lineStart: null,
  lineEnd: null,
  antiPattern: "RELIABILITY_SWALLOWED_EXCEPTION",
  category: "Reliability",
  severity: "major",
  confidence: 0.8,
  explanation: "File-level finding — applies to the whole file.",
  codeSnippet: null,
};

beforeEach(() => {
  // Make sure the DOM is clean between tests; jsdom accumulates state.
  document.body.innerHTML = "";
});

describe("DiffViewer", () => {
  it("renders the diff via the wrapped library", () => {
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[]} />);
    expect(screen.getByTestId("mock-diff")).toBeInTheDocument();
    // Five rows for five lines.
    expect(screen.getAllByTestId("diff-row")).toHaveLength(5);
  });

  it("shows a friendly empty state when diff is null", () => {
    render(<DiffViewer diff={null} findings={[]} />);
    expect(screen.getByText(/no diff available/i)).toBeInTheDocument();
  });

  it("applies a critical-finding highlight class to the matching line", async () => {
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[CRITICAL]} />);
    // Critical → red. Use `waitFor` because the highlight is applied in a
    // useEffect that runs after the initial render.
    await waitFor(() => {
      const row = screen
        .getAllByTestId("diff-row")
        .find((r) => r.getAttribute("data-line") === "2");
      expect(row).toBeDefined();
      expect(row!.className).toContain("codelens-finding-critical");
    });
  });

  it("applies a minor-finding highlight class to the matching line", async () => {
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[MINOR]} />);
    await waitFor(() => {
      const row = screen
        .getAllByTestId("diff-row")
        .find((r) => r.getAttribute("data-line") === "4");
      expect(row).toBeDefined();
      expect(row!.className).toContain("codelens-finding-minor");
    });
  });

  it("does NOT apply an inline highlight when the finding has no lineStart", async () => {
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[FILE_LEVEL]} />);
    // Allow the useEffect to run; then assert no row has any class.
    await waitFor(() => {
      const rows = screen.getAllByTestId("diff-row");
      const anyHighlighted = rows.some((r) =>
        r.className.includes("codelens-finding-"),
      );
      expect(anyHighlighted).toBe(false);
    });
  });

  it("attaches a tooltip carrying the anti-pattern name and severity", async () => {
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[CRITICAL]} />);
    await waitFor(() => {
      const row = screen
        .getAllByTestId("diff-row")
        .find((r) => r.getAttribute("data-line") === "2");
      expect(row).toBeDefined();
      const tooltip = row!.getAttribute("title") ?? "";
      // `formatAntiPattern("PERF_N_PLUS_1")` → "N+1 Query Performance"
      expect(tooltip).toMatch(/N\+1/i);
      expect(tooltip).toMatch(/CRITICAL/i);
    });
  });

  it("highlights every line in a multi-line finding range", async () => {
    const range: FindingDto = { ...CRITICAL, lineStart: 2, lineEnd: 4 };
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[range]} />);
    await waitFor(() => {
      for (const ln of [2, 3, 4]) {
        const row = screen
          .getAllByTestId("diff-row")
          .find((r) => r.getAttribute("data-line") === String(ln));
        expect(row).toBeDefined();
        expect(row!.className).toContain("codelens-finding-critical");
      }
    });
  });

  it("picks the most severe class when multiple findings target one line", async () => {
    const minorOnLine2: FindingDto = { ...MINOR, lineStart: 2, lineEnd: 2 };
    render(
      <DiffViewer
        diff={SAMPLE_DIFF}
        findings={[minorOnLine2, CRITICAL]} // both hit line 2
      />,
    );
    await waitFor(() => {
      const row = screen
        .getAllByTestId("diff-row")
        .find((r) => r.getAttribute("data-line") === "2");
      expect(row).toBeDefined();
      // Critical wins over minor.
      expect(row!.className).toContain("codelens-finding-critical");
      expect(row!.className).not.toContain("codelens-finding-minor");
    });
  });

  it("scrolls to the requested line when scrollToLine changes", async () => {
    const scrollIntoView = vi.fn();
    // Patch the prototype so any HTMLElement.scrollIntoView hits our spy.
    const original = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    try {
      const { rerender } = render(
        <DiffViewer
          diff={SAMPLE_DIFF}
          findings={[CRITICAL]}
          scrollToLine={null}
        />,
      );
      rerender(
        <DiffViewer
          diff={SAMPLE_DIFF}
          findings={[CRITICAL]}
          scrollToLine={2}
        />,
      );
      await waitFor(() => {
        expect(scrollIntoView).toHaveBeenCalled();
      });
    } finally {
      HTMLElement.prototype.scrollIntoView = original;
    }
  });

  it("tolerates a finding whose line range is outside the rendered diff", async () => {
    const offByOne: FindingDto = { ...CRITICAL, lineStart: 999, lineEnd: 999 };
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[offByOne]} />);
    // No row should be highlighted because line 999 doesn't exist.
    await waitFor(() => {
      const rows = screen.getAllByTestId("diff-row");
      const anyHighlighted = rows.some((r) =>
        r.className.includes("codelens-finding-"),
      );
      expect(anyHighlighted).toBe(false);
    });
  });

  // Sanity check that the component does not throw when a finding's
  // explanation contains characters that, in HTML, would be special.
  it("does not inject HTML when the finding explanation contains < or &", async () => {
    const evil: FindingDto = {
      ...CRITICAL,
      explanation: "<script>alert(1)</script> & friends",
    };
    render(<DiffViewer diff={SAMPLE_DIFF} findings={[evil]} />);
    await waitFor(() => {
      const row = screen
        .getAllByTestId("diff-row")
        .find((r) => r.getAttribute("data-line") === "2");
      expect(row).toBeDefined();
      // The tooltip is the title attribute, set as plain text — there must
      // be no nested script element rendered.
      expect(row!.querySelector("script")).toBeNull();
      // The raw string should still be in the title attribute, intact.
      expect(row!.getAttribute("title")).toContain("<script>");
    });
    // Re-rendering through fireEvent just to ensure no late effects crash.
    fireEvent.click(screen.getByTestId("mock-diff"));
  });
});

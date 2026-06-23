"use client";

import { useEffect, useMemo, useRef } from "react";
import ReactDiffViewer, {
  ReactDiffViewerStylesOverride,
} from "react-diff-viewer-continued";
import {
  formatAntiPattern,
  severityClasses,
} from "@/lib/utils";
import type { FindingDto } from "@/lib/types";

export interface DiffViewerProps {
  /** Unified diff string. If `null` we render a friendly empty state. */
  diff: string | null;
  findings: FindingDto[];
  /** Optional: scroll target set by FindingCard clicks. */
  scrollToLine?: number | null;
  /** Optional: split view (true) or unified (false). Defaults to split. */
  splitView?: boolean;
}

/**
 * Wraps react-diff-viewer-continued to add inline finding annotations.
 *
 * Strategy:
 *   1. After the diff renders, walk every <pre> row the library emits and
 *      tag it with `data-line="<n>"` so we can target it via CSS / JS.
 *   2. For each finding with a lineStart, find every row whose
 *      `data-line` falls in [lineStart, lineEnd] and apply a severity
 *      class + a `title` tooltip carrying the anti-pattern name.
 *   3. Findings with no lineStart are skipped (they show only in the
 *      right-panel FindingCard list).
 */
export function DiffViewer({
  diff,
  findings,
  scrollToLine,
  splitView = true,
}: DiffViewerProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Map line-number → finding(s) for that line.
  const findingsByLine = useMemo(() => {
    const m = new Map<number, FindingDto[]>();
    for (const f of findings) {
      if (f.lineStart == null) continue;
      const start = f.lineStart;
      const end = f.lineEnd ?? start;
      for (let l = start; l <= end; l++) {
        const arr = m.get(l) ?? [];
        arr.push(f);
        m.set(l, arr);
      }
    }
    return m;
  }, [findings]);

  // Apply inline highlights after the diff mounts/updates.
  useEffect(() => {
    const root = containerRef.current;
    if (!root) return;
    // react-diff-viewer-continued renders each side as a <pre> block
    // containing one row per <div> with a gutter <td>.
    // We tag every row with its line number (extracted from the gutter)
    // so the CSS rules can target it via [data-line="N"].
    const preBlocks = root.querySelectorAll("pre");
    preBlocks.forEach((pre) => {
      const rows = pre.querySelectorAll(":scope > div");
      rows.forEach((row) => {
        const gutter = row.querySelector("td");
        const lineText = gutter?.textContent?.trim() ?? "";
        const lineNum = Number.parseInt(lineText, 10);
        if (!Number.isFinite(lineNum)) return;
        (row as HTMLElement).dataset.line = String(lineNum);

        const lineFindings = findingsByLine.get(lineNum);
        if (!lineFindings || lineFindings.length === 0) return;

        // Most-severe finding wins for the class.
        const order = { minor: 0, major: 1, critical: 2 } as const;
        const top = [...lineFindings].sort(
          (a, b) =>
            (order[(b.severity as keyof typeof order) ?? "minor"] ?? 0) -
            (order[(a.severity as keyof typeof order) ?? "minor"] ?? 0),
        )[0];
        const sev = (top.severity ?? "").toLowerCase();
        const cls =
          sev === "critical"
            ? "codelens-finding-critical"
            : sev === "major"
            ? "codelens-finding-major"
            : "codelens-finding-minor";
        row.classList.add(cls);

        // Tooltip: anti-pattern name + first-line explanation.
        const tooltip =
          `${formatAntiPattern(top.antiPattern)} (${top.severity?.toUpperCase()})` +
          (top.explanation ? ` — ${top.explanation.split("\n")[0]}` : "");
        (row as HTMLElement).title = tooltip;
      });
    });
  }, [diff, findings, findingsByLine]);

  // Scroll to a specific line when triggered by a FindingCard click.
  useEffect(() => {
    if (scrollToLine == null) return;
    const root = containerRef.current;
    if (!root) return;
    const el = root.querySelector<HTMLElement>(
      `[data-line="${scrollToLine}"]`,
    );
    if (el) el.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [scrollToLine]);

  if (!diff) {
    return (
      <div className="flex h-64 items-center justify-center rounded-lg border bg-muted text-sm text-muted-foreground">
        No diff available for this PR.
      </div>
    );
  }

  // Style overrides so the diff fits the dashboard's neutral palette.
  const styles: ReactDiffViewerStylesOverride = {
    variables: {
      light: {
        diffViewerBackground: "hsl(var(--background))",
        diffViewerColor: "hsl(var(--foreground))",
        addedBackground: "rgba(34,197,94,0.10)",
        addedGutterBackground: "rgba(34,197,94,0.20)",
        removedBackground: "rgba(239,68,68,0.10)",
        removedGutterBackground: "rgba(239,68,68,0.20)",
        wordAddedBackground: "rgba(34,197,94,0.30)",
        wordRemovedBackground: "rgba(239,68,68,0.30)",
        emptyLineBackground: "hsl(var(--muted))",
        gutterBackground: "hsl(var(--muted))",
        gutterBackgroundDark: "hsl(var(--muted))",
        highlightBackground: "hsl(var(--accent))",
        highlightGutterBackground: "hsl(var(--accent))",
        codeFoldGutterBackground: "hsl(var(--muted))",
        codeFoldBackground: "hsl(var(--muted))",
      },
    },
  };

  // The diff library expects a string for old/new; for a unified diff
  // we pass the same string on both sides, which produces a side-by-side
  // view of the patch.
  return (
    <div
      ref={containerRef}
      className="overflow-hidden rounded-lg border bg-card"
      data-testid="diff-viewer"
    >
      <ReactDiffViewer
        oldValue={diff}
        newValue={diff}
        splitView={splitView}
        useDarkTheme={false}
        styles={styles}
        hideLineNumbers={false}
        showDiffOnly={false}
        compareMethod={"CHARS" as never}
      />
    </div>
  );
}

// re-export for tests
export { severityClasses };

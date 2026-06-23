"use client";

import { FileCode } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import {
  formatAntiPattern,
  severityClasses,
} from "@/lib/utils";
import type { FindingDto } from "@/lib/types";

export interface FindingCardProps {
  finding: FindingDto;
  /** When true, the card is interactive — clicking scrolls the diff to that line. */
  scrollToLine?: (line: number | null) => void;
  active?: boolean;
}

/**
 * Right-panel card for a single finding. Renders severity badge, formatted
 * anti-pattern name, confidence percentage, file path and explanation.
 * The left border is color-coded by severity.
 */
export function FindingCard({
  finding,
  scrollToLine,
  active,
}: FindingCardProps) {
  const interactive = !!scrollToLine;
  const sev = (finding.severity ?? "").toLowerCase();
  const confPct = Math.round(finding.confidence * 100);
  const hasLine = finding.lineStart != null;

  return (
    <Card
      onClick={() => {
        if (interactive && hasLine) {
          scrollToLine?.(finding.lineStart);
        }
      }}
      className={`overflow-hidden border-l-4 ${
        sev === "critical"
          ? "border-l-red-500"
          : sev === "major"
          ? "border-l-orange-500"
          : sev === "minor"
          ? "border-l-yellow-500"
          : "border-l-gray-400"
      } ${interactive ? "cursor-pointer transition-colors hover:bg-accent/40" : ""} ${
        active ? "ring-2 ring-primary" : ""
      }`}
      data-severity={sev}
    >
      <CardContent className="space-y-2 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge
            variant="outline"
            className={`border ${severityClasses(finding.severity)}`}
          >
            {finding.severity?.toUpperCase()}
          </Badge>
          <span className="text-sm font-semibold">
            {formatAntiPattern(finding.antiPattern)}
          </span>
          <span className="ml-auto text-xs font-mono text-muted-foreground">
            {confPct}%
          </span>
        </div>
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <FileCode className="h-3 w-3 shrink-0" />
          <span className="truncate font-mono">{finding.filePath}</span>
          {hasLine ? (
            <span className="shrink-0">
              · L{finding.lineStart}
              {finding.lineEnd != null && finding.lineEnd > finding.lineStart
                ? `–${finding.lineEnd}`
                : ""}
            </span>
          ) : (
            <span className="shrink-0 italic">· file-level</span>
          )}
        </div>
        {finding.explanation ? (
          <p className="text-sm text-foreground/90">
            {finding.explanation}
          </p>
        ) : null}
        {finding.codeSnippet ? (
          <pre className="overflow-x-auto rounded-md bg-muted px-3 py-2 text-xs">
            <code>{finding.codeSnippet}</code>
          </pre>
        ) : null}
      </CardContent>
    </Card>
  );
}

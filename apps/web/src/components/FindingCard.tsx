"use client";

import { useState } from "react";
import { FileCode } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ApiError, recordFindingAction } from "@/lib/api";
import { severityClasses, titleCase } from "@/lib/utils";
import type {
  FindingAction,
  FindingDisposition,
  FindingDto,
} from "@/lib/types";

export interface FindingCardProps {
  finding: FindingDto;
}

const ACTIONS: { action: FindingAction; label: string; status: FindingDisposition }[] = [
  { action: "accept", label: "Accept", status: "accepted" },
  { action: "dismiss", label: "Dismiss", status: "dismissed" },
  { action: "fix", label: "Mark fixed", status: "fixed" },
];

function actionErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return "Finding not found or not in one of your repositories.";
    if (err.status === 401) return "Your session expired — sign in again.";
    return `Could not record the action (HTTP ${err.status}).`;
  }
  return "Could not record the action — check your connection.";
}

/**
 * Card for a single finding. Renders severity badge, formatted
 * anti-pattern name, confidence percentage, file path and explanation.
 * The left border is color-coded by severity.
 *
 * Accept / Dismiss / Mark fixed call POST /api/scan/action. The card starts
 * from the finding's saved status, updates optimistically on click, takes the
 * status the API confirms, and rolls back if the request fails.
 */
export function FindingCard({ finding }: FindingCardProps) {
  const [disposition, setDisposition] = useState<FindingDisposition | null>(
    finding.status === "open" ? null : finding.status,
  );
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const sev = (finding.severity ?? "").toLowerCase();
  const confPct = Math.round(finding.confidence * 100);
  const lineStart = finding.lineStart;
  const lineEnd = finding.lineEnd;

  async function apply(action: FindingAction, optimistic: FindingDisposition) {
    const previous = disposition;
    setDisposition(optimistic);
    setPending(true);
    setError(null);
    try {
      const res = await recordFindingAction(finding.id, action);
      setDisposition(res.status);
    } catch (err) {
      setDisposition(previous);
      setError(actionErrorMessage(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <Card
      className={`overflow-hidden border-l-4 ${
        sev === "critical"
          ? "border-l-red-500"
          : sev === "major"
          ? "border-l-orange-500"
          : sev === "minor"
          ? "border-l-yellow-500"
          : "border-l-gray-400"
      }`}
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
            {titleCase(finding.antiPattern)}
          </span>
          {disposition ? (
            <Badge variant="secondary" data-testid="finding-disposition">
              {titleCase(disposition)}
            </Badge>
          ) : null}
          <span className="ml-auto text-xs font-mono text-muted-foreground">
            {confPct}%
          </span>
        </div>
        <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <FileCode className="h-3 w-3 shrink-0" />
          <span className="truncate font-mono">{finding.filePath}</span>
          {lineStart != null ? (
            <span className="shrink-0">
              · L{lineStart}
              {lineEnd != null && lineEnd > lineStart
                ? `–${lineEnd}`
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
        <div className="flex flex-wrap items-center gap-2 pt-1">
          {ACTIONS.map(({ action, label, status }) => (
            <Button
              key={action}
              type="button"
              size="sm"
              variant={disposition === status ? "secondary" : "outline"}
              aria-pressed={disposition === status}
              disabled={pending || disposition === status}
              onClick={() => void apply(action, status)}
            >
              {label}
            </Button>
          ))}
          {error ? (
            <span role="alert" className="text-xs text-destructive">
              {error}
            </span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

"use client";

import { useId, useRef, useState } from "react";
import { AlertTriangle, FileCode, Wrench } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { TriageReasonDialog } from "@/components/TriageReasonDialog";
import { ApiError, recordFindingAction } from "@/lib/api";
import { ruleDisplayName } from "@/lib/taxonomy";
import { formatDateTime, severityClasses, timeAgo, titleCase } from "@/lib/utils";
import type { FindingAction, FindingDto, FindingStatus } from "@/lib/types";

export const STATUS_LABEL: Record<FindingStatus, string> = {
  open: "Open",
  accepted: "Accepted",
  dismissed: "Dismissed",
  fixed: "Fixed",
  false_positive: "False positive",
};

interface ActionSpec {
  action: FindingAction;
  label: string;
  /** Status the API stores for this action. */
  result: FindingStatus;
  needsReason: boolean;
}

const ACTIONS: readonly ActionSpec[] = [
  { action: "accept", label: "Accept", result: "accepted", needsReason: false },
  { action: "fix", label: "Mark fixed", result: "fixed", needsReason: false },
  { action: "dismiss", label: "Dismiss…", result: "dismissed", needsReason: true },
  { action: "false_positive", label: "False positive…", result: "false_positive", needsReason: true },
];

const REOPEN: ActionSpec = { action: "reopen", label: "Reopen", result: "open", needsReason: false };

const REASON_COPY: Partial<Record<FindingAction, { title: string; description: string; confirm: string }>> = {
  dismiss: {
    title: "Dismiss finding",
    description: "Explain why this finding will not be addressed (for example, accepted risk or out of scope).",
    confirm: "Dismiss finding",
  },
  false_positive: {
    title: "Mark as false positive",
    description: "Explain why the rule fired incorrectly. False-positive reasons are used to tune the rules.",
    confirm: "Mark false positive",
  },
};

function actionErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return "Finding not found or not in one of your repositories.";
    if (err.status === 401) return "Your session expired — sign in again.";
    if (err.status === 400) return err.message;
    if (err.status === 403) return "The request was rejected. Reload the page and try again.";
    return `Could not record the action (HTTP ${err.status}).`;
  }
  return "Could not record the action — check your connection.";
}

function severityBorder(sev: string): string {
  switch (sev) {
    case "critical":
      return "border-l-red-500";
    case "major":
      return "border-l-orange-500";
    case "minor":
      return "border-l-yellow-500";
    default:
      return "border-l-gray-400";
  }
}

export function locationLabel(finding: Pick<FindingDto, "filePath" | "lineStart" | "lineEnd">): string {
  if (finding.lineStart == null) return `${finding.filePath} (file-level)`;
  const range =
    finding.lineEnd != null && finding.lineEnd > finding.lineStart
      ? `${finding.lineStart}–${finding.lineEnd}`
      : `${finding.lineStart}`;
  return `${finding.filePath}:${range}`;
}

export interface FindingCardProps {
  finding: FindingDto;
  /** Called with the finding as saved by the API after a successful action. */
  onTriaged?: (updated: FindingDto) => void;
}

/**
 * One finding: severity, rule, confidence, location, explanation, evidence,
 * remediation and caveat, plus triage actions. Accept / Mark fixed / Reopen
 * apply optimistically and roll back on failure; Dismiss and False positive
 * open a dialog that requires a reason before anything is sent.
 */
export function FindingCard({ finding, onTriaged }: FindingCardProps) {
  const headingId = useId();
  const actionsRef = useRef<HTMLDivElement>(null);
  const openerRef = useRef<FindingAction | null>(null);
  const [state, setState] = useState({
    status: finding.status,
    triageReason: finding.triageReason,
    triagedAt: finding.triagedAt,
  });
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reasonFor, setReasonFor] = useState<ActionSpec | null>(null);

  const sev = (finding.severity ?? "").toLowerCase();
  const confPct = Math.round(finding.confidence * 100);
  const actions = state.status === "open" ? ACTIONS : [REOPEN, ...ACTIONS];

  async function send(spec: ActionSpec, reason?: string): Promise<void> {
    const res = await recordFindingAction(finding.id, spec.action, reason);
    const next = { status: res.status, triageReason: res.reason, triagedAt: res.triagedAt };
    setState(next);
    onTriaged?.({ ...finding, ...next });
  }

  async function applyOptimistic(spec: ActionSpec) {
    const previous = state;
    setState({ status: spec.result, triageReason: null, triagedAt: previous.triagedAt });
    setPending(true);
    setError(null);
    try {
      await send(spec);
    } catch (err) {
      setState(previous);
      setError(actionErrorMessage(err));
    } finally {
      setPending(false);
    }
  }

  async function applyWithReason(spec: ActionSpec, reason: string) {
    setPending(true);
    setError(null);
    try {
      await send(spec, reason);
    } catch (err) {
      throw new Error(actionErrorMessage(err));
    } finally {
      setPending(false);
    }
  }

  const reasonCopy = reasonFor ? REASON_COPY[reasonFor.action] : undefined;

  return (
    <Card
      className={`overflow-hidden border-l-4 ${severityBorder(sev)}`}
      data-testid="finding-card"
    >
      <article aria-labelledby={headingId}>
        <CardContent className="space-y-3 p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline" className={`border ${severityClasses(finding.severity)}`}>
              {finding.severity?.toUpperCase()}
            </Badge>
            <h3 id={headingId} className="text-sm font-semibold">
              {ruleDisplayName(finding.antiPattern)}
            </h3>
            <span className="text-xs text-muted-foreground">{titleCase(finding.category)}</span>
            <Badge
              variant={state.status === "open" ? "outline" : "secondary"}
              data-testid="finding-status"
            >
              {STATUS_LABEL[state.status] ?? titleCase(state.status)}
            </Badge>
            <span
              className="ml-auto font-mono text-xs text-muted-foreground"
              title="Detector confidence"
            >
              {confPct}% confidence
            </span>
          </div>

          <div className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
            <FileCode className="h-3 w-3 shrink-0" aria-hidden="true" />
            <span className="break-all font-mono" data-testid="finding-location">
              {locationLabel(finding)}
            </span>
          </div>

          {finding.explanation ? (
            <p className="text-sm text-foreground/90">{finding.explanation}</p>
          ) : null}

          {finding.evidence || finding.codeSnippet ? (
            <div>
              <div className="mb-1 text-xs font-medium text-muted-foreground">
                {finding.evidence ? "Evidence" : "Code"}
              </div>
              <pre className="overflow-x-auto rounded-md bg-muted px-3 py-2 text-xs" tabIndex={0}>
                <code>{finding.evidence ?? finding.codeSnippet}</code>
              </pre>
            </div>
          ) : null}

          {finding.remediation ? (
            <div className="flex gap-2 rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-900">
              <Wrench className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <p>
                <span className="font-medium">How to fix: </span>
                {finding.remediation}
              </p>
            </div>
          ) : null}

          {finding.caveat ? (
            <div className="flex gap-2 rounded-md border border-yellow-300 bg-yellow-50 px-3 py-2 text-sm text-yellow-900">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <p>
                <span className="font-medium">Caveat: </span>
                {finding.caveat}
              </p>
            </div>
          ) : null}

          {state.status !== "open" && (state.triageReason || state.triagedAt) ? (
            <p className="text-xs text-muted-foreground" data-testid="finding-triage">
              {STATUS_LABEL[state.status]}
              {state.triagedAt ? (
                <>
                  {" "}
                  <time dateTime={state.triagedAt} title={formatDateTime(state.triagedAt)}>
                    {timeAgo(state.triagedAt)}
                  </time>
                </>
              ) : null}
              {state.triageReason ? <> — “{state.triageReason}”</> : null}
            </p>
          ) : null}

          <div
            ref={actionsRef}
            className="flex flex-wrap items-center gap-2 pt-1"
            role="group"
            aria-label={`Triage ${ruleDisplayName(finding.antiPattern)} at ${locationLabel(finding)}`}
          >
            {actions.map((spec) => (
              <Button
                key={spec.action}
                type="button"
                size="sm"
                data-action={spec.action}
                variant={spec.action === "reopen" ? "secondary" : "outline"}
                disabled={pending || state.status === spec.result}
                onClick={() => {
                  if (spec.needsReason) {
                    openerRef.current = spec.action;
                    setReasonFor(spec);
                  } else {
                    void applyOptimistic(spec);
                  }
                }}
              >
                {spec.label}
              </Button>
            ))}
            {error ? (
              <span role="alert" className="text-xs text-destructive">
                {error}
              </span>
            ) : null}
          </div>
        </CardContent>
      </article>

      <TriageReasonDialog
        open={reasonFor !== null}
        title={reasonCopy?.title ?? ""}
        description={reasonCopy?.description ?? ""}
        confirmLabel={reasonCopy?.confirm ?? "Save"}
        onOpenChange={(open) => {
          if (!open) setReasonFor(null);
        }}
        onConfirm={(reason) => (reasonFor ? applyWithReason(reasonFor, reason) : Promise.resolve())}
        onRestoreFocus={() => {
          // Back to the button that opened the dialog; if the action made it
          // inapplicable (now disabled), to the first available action.
          const group = actionsRef.current;
          const opener = group?.querySelector<HTMLButtonElement>(
            `button[data-action="${openerRef.current}"]:not([disabled])`,
          );
          (opener ?? group?.querySelector<HTMLButtonElement>("button:not([disabled])"))?.focus();
        }}
      />
    </Card>
  );
}

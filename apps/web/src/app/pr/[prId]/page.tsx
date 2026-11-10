"use client";

import { useState } from "react";
import useSWR from "swr";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  AlertCircle,
  Calendar,
  ChevronLeft,
  ExternalLink,
  GitCommit,
  User,
} from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { FindingCard } from "@/components/FindingCard";
import { ReviewRunsPanel } from "@/components/ReviewRunsPanel";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, getPRReview } from "@/lib/api";
import {
  formatDateTime,
  githubPullRequestUrl,
  qualityScoreColor,
  severityClasses,
  shortSha,
  timeAgo,
  titleCase,
} from "@/lib/utils";
import type { FindingDto, ReviewDetailResponse } from "@/lib/types";

type Filter = "all" | "open" | "triaged";

const FILTERS: { id: Filter; label: string }[] = [
  { id: "all", label: "All" },
  { id: "open", label: "Open" },
  { id: "triaged", label: "Triaged" },
];

const SEVERITIES = ["critical", "major", "minor"] as const;

const ANALYSIS_STATUS: Record<string, { label: string; className: string; hint: string }> = {
  processing: {
    label: "Analysing",
    className: "bg-blue-100 text-blue-800 border-blue-300",
    hint: "The review is running; findings appear when it finishes.",
  },
  reviewed: {
    label: "Reviewed",
    className: "bg-green-100 text-green-800 border-green-300",
    hint: "Analysis finished for the head commit shown.",
  },
  failed: {
    label: "Failed",
    className: "bg-red-100 text-red-800 border-red-300",
    hint: "The review did not complete. See review runs below for the error.",
  },
};

function AnalysisStatus({ status }: { status: string }) {
  const key = status.toLowerCase();
  const spec = Object.hasOwn(ANALYSIS_STATUS, key) ? ANALYSIS_STATUS[key] : null;
  return (
    <Badge
      variant="outline"
      className={`border ${spec?.className ?? ""}`}
      title={spec?.hint}
      data-testid="analysis-status"
    >
      {spec?.label ?? titleCase(status)}
    </Badge>
  );
}

function matches(filter: Filter, f: FindingDto): boolean {
  if (filter === "open") return f.status === "open";
  if (filter === "triaged") return f.status !== "open";
  return true;
}

function PRReviewContent() {
  const { prId } = useParams<{ prId: string }>();
  const [filter, setFilter] = useState<Filter>("all");
  const { data, error, isLoading, mutate } = useSWR<ReviewDetailResponse>(
    prId ? ["pr-review", prId] : null,
    () => getPRReview(prId),
    { revalidateOnFocus: false },
  );

  const findings = data?.findings ?? [];
  const counts: Record<Filter, number> = {
    all: findings.length,
    open: findings.filter((f) => f.status === "open").length,
    triaged: findings.filter((f) => f.status !== "open").length,
  };
  const visible = findings.filter((f) => matches(filter, f));
  const githubUrl = githubPullRequestUrl(data?.githubPrUrl);

  function onTriaged(updated: FindingDto) {
    void mutate(
      (current) =>
        current && {
          ...current,
          findings: current.findings.map((f) => (f.id === updated.id ? updated : f)),
        },
      { revalidate: false },
    );
  }

  return (
    <div className="mx-auto max-w-7xl space-y-6 p-4 md:p-8">
      <div>
        {data?.repoId ? (
          <Link
            href={`/repo/${data.repoId}`}
            className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
          >
            <ChevronLeft className="mr-1 h-4 w-4" aria-hidden="true" />
            {data.repoFullName ?? "Repository"}
          </Link>
        ) : null}
      </div>

      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0 space-y-2">
          {isLoading && !data ? (
            <Skeleton className="h-8 w-72 max-w-full" />
          ) : (
            <h1 className="break-words text-2xl font-bold tracking-tight">
              {data?.title ?? "Pull request"}{" "}
              <span className="text-muted-foreground">#{data?.githubPrNumber ?? "—"}</span>
            </h1>
          )}
          {data ? (
            <dl className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-muted-foreground">
              {data.repoFullName ? (
                <div className="flex items-center gap-1">
                  <dt className="sr-only">Repository</dt>
                  <dd className="font-medium text-foreground">{data.repoFullName}</dd>
                </div>
              ) : null}
              <div className="flex items-center gap-1">
                <dt className="flex items-center gap-1">
                  <GitCommit className="h-3.5 w-3.5" aria-hidden="true" />
                  Head
                </dt>
                <dd>
                  <code className="rounded bg-muted px-1.5 py-0.5 text-xs" title={data.headSha ?? undefined} data-testid="head-sha">
                    {shortSha(data.headSha)}
                  </code>
                </dd>
              </div>
              {data.authorGithub ? (
                <div className="flex items-center gap-1">
                  <dt>
                    <User className="h-3.5 w-3.5" aria-hidden="true" />
                    <span className="sr-only">Author</span>
                  </dt>
                  <dd>{data.authorGithub}</dd>
                </div>
              ) : null}
              {data.reviewedAt ? (
                <div className="flex items-center gap-1">
                  <dt>
                    <Calendar className="h-3.5 w-3.5" aria-hidden="true" />
                    <span className="sr-only">Reviewed</span>
                  </dt>
                  <dd>
                    Reviewed{" "}
                    <time dateTime={data.reviewedAt} title={formatDateTime(data.reviewedAt)}>
                      {timeAgo(data.reviewedAt)}
                    </time>
                  </dd>
                </div>
              ) : null}
              {githubUrl ? (
                <div>
                  <dt className="sr-only">GitHub</dt>
                  <dd>
                    <a
                      href={githubUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="inline-flex items-center gap-1 underline underline-offset-2"
                    >
                      Open on GitHub
                      <ExternalLink className="h-3 w-3" aria-hidden="true" />
                      <span className="sr-only">(opens in a new tab)</span>
                    </a>
                  </dd>
                </div>
              ) : null}
            </dl>
          ) : null}
        </div>
        {data ? (
          <div className="flex items-center gap-3">
            <AnalysisStatus status={data.status} />
            {data.qualityScore != null ? (
              <div className="flex items-center gap-2 rounded-lg border bg-card px-4 py-2">
                <div
                  className={`flex h-12 w-12 items-center justify-center rounded-full border-2 ${qualityScoreColor(data.qualityScore)}`}
                >
                  <span className="text-lg font-bold">{data.qualityScore.toFixed(0)}</span>
                </div>
                <div className="text-sm">
                  <div className="font-medium">Quality score</div>
                  <div className="text-xs text-muted-foreground">
                    {counts.open} open of {findings.length} finding{findings.length === 1 ? "" : "s"}
                  </div>
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
      </header>

      {error ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn’t load review</AlertTitle>
          <AlertDescription>
            {error instanceof ApiError && error.status === 404
              ? "This pull request was not found or is not in one of your repositories."
              : (error as Error).message}
          </AlertDescription>
        </Alert>
      ) : null}

      {data?.errorMessage ? (
        <Alert variant="warning">
          <AlertCircle className="h-4 w-4" aria-hidden="true" />
          <AlertTitle>Review incomplete</AlertTitle>
          <AlertDescription>{data.errorMessage}</AlertDescription>
        </Alert>
      ) : null}

      <Separator />

      <section aria-labelledby="findings-heading" className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2
            id="findings-heading"
            className="text-sm font-semibold uppercase tracking-wider text-muted-foreground"
          >
            Findings ({findings.length})
          </h2>
          {findings.length > 0 ? (
            <div role="group" aria-label="Filter findings" className="flex gap-1">
              {FILTERS.map(({ id, label }) => (
                <Button
                  key={id}
                  type="button"
                  size="sm"
                  variant={filter === id ? "secondary" : "outline"}
                  aria-pressed={filter === id}
                  onClick={() => setFilter(id)}
                >
                  {label} ({counts[id]})
                </Button>
              ))}
            </div>
          ) : null}
        </div>

        {isLoading ? (
          <div className="space-y-3" role="status" aria-label="Loading findings">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-24 w-full" />
            ))}
          </div>
        ) : !data ? null : findings.length === 0 ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">No findings</CardTitle>
            </CardHeader>
            <CardContent className="text-sm text-muted-foreground">
              {data.status === "reviewed"
                ? "The review didn’t flag anything on this pull request."
                : `No findings recorded yet (status: ${data.status}).`}
            </CardContent>
          </Card>
        ) : visible.length === 0 ? (
          <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground">
            {filter === "open" ? "Every finding has been triaged." : "No findings have been triaged yet."}
          </p>
        ) : (
          <div className="space-y-6">
            {SEVERITIES.map((sev) => {
              const list = visible.filter((f) => f.severity === sev);
              if (list.length === 0) return null;
              return (
                <div key={sev} className="space-y-2">
                  <div className="flex items-center gap-2">
                    <Badge variant="outline" className={`border ${severityClasses(sev)}`}>
                      {sev.toUpperCase()}
                    </Badge>
                    <span className="text-xs text-muted-foreground">{list.length}</span>
                  </div>
                  {list.map((f) => (
                    <FindingCard key={f.id} finding={f} onTriaged={onTriaged} />
                  ))}
                </div>
              );
            })}
            {visible.some((f) => !SEVERITIES.some((s) => s === f.severity)) ? (
              <div className="space-y-2">
                <Badge variant="outline">OTHER</Badge>
                {visible
                  .filter((f) => !SEVERITIES.some((s) => s === f.severity))
                  .map((f) => (
                    <FindingCard key={f.id} finding={f} onTriaged={onTriaged} />
                  ))}
              </div>
            ) : null}
          </div>
        )}
      </section>

      <Separator />

      {prId ? <ReviewRunsPanel prId={prId} /> : null}
    </div>
  );
}

export default function PRReviewPage() {
  return (
    <AuthShell>
      <PRReviewContent />
    </AuthShell>
  );
}

"use client";

import { useState } from "react";
import useSWR from "swr";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  ChevronLeft,
  ExternalLink,
  Calendar,
  User,
  AlertCircle,
} from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { DiffViewer } from "@/components/DiffViewer";
import { FindingCard } from "@/components/FindingCard";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Separator } from "@/components/ui/separator";
import { getPRReview } from "@/lib/api";
import {
  qualityScoreColor,
  severityClasses,
  timeAgo,
  titleCase,
} from "@/lib/utils";
import type { ReviewDetailResponse } from "@/lib/types";

function PRReviewContent() {
  const params = useParams<{ prId: string }>();
  const prId = params.prId;
  const { data, error, isLoading } = useSWR<ReviewDetailResponse>(
    prId ? ["pr-review", prId] : null,
    () => getPRReview(prId),
    { revalidateOnFocus: false },
  );
  const [activeLine, setActiveLine] = useState<number | null>(null);

  const findings = data?.findings ?? [];
  const grouped = groupBySeverity(findings);

  return (
    <div className="mx-auto max-w-7xl p-6">
      <div className="mb-2">
        {data?.repoId ? (
          <Link
            href={`/repo/${data.repoId}`}
            className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
          >
            <ChevronLeft className="mr-1 h-4 w-4" />
            {data.repoFullName}
          </Link>
        ) : null}
      </div>

      {/* Header */}
      <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">
            {data?.title ?? "Pull request"}{" "}
            <span className="text-muted-foreground">
              #{data?.githubPrNumber ?? "—"}
            </span>
          </h1>
          <div className="mt-2 flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
            {data?.authorGithub ? (
              <span className="flex items-center gap-1">
                <User className="h-3.5 w-3.5" />
                {data.authorGithub}
              </span>
            ) : null}
            {data?.reviewedAt ? (
              <span className="flex items-center gap-1">
                <Calendar className="h-3.5 w-3.5" />
                Reviewed {timeAgo(data.reviewedAt)}
              </span>
            ) : null}
            {data?.githubPrUrl ? (
              <a
                href={data.githubPrUrl}
                target="_blank"
                rel="noreferrer"
                className="flex items-center gap-1 underline"
              >
                Open on GitHub
                <ExternalLink className="h-3 w-3" />
              </a>
            ) : null}
          </div>
        </div>
        <div className="flex items-center gap-3">
          {data ? <StatusBadge status={data.status} /> : null}
          {data?.qualityScore != null ? (
            <div className="flex items-center gap-2 rounded-lg border bg-card px-4 py-2">
              <div
                className={`flex h-12 w-12 items-center justify-center rounded-full border-2 ${qualityScoreColor(data.qualityScore)}`}
              >
                <span className="text-lg font-bold">
                  {data.qualityScore.toFixed(0)}
                </span>
              </div>
              <div className="text-sm">
                <div className="font-medium">Quality score</div>
                <div className="text-xs text-muted-foreground">
                  {findings.length} finding
                  {findings.length === 1 ? "" : "s"}
                </div>
              </div>
            </div>
          ) : null}
        </div>
      </header>

      {error ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn’t load review</AlertTitle>
          <AlertDescription>{(error as Error).message}</AlertDescription>
        </Alert>
      ) : null}

      {data?.errorMessage ? (
        <Alert variant="warning" className="mb-4">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Review incomplete</AlertTitle>
          <AlertDescription>{data.errorMessage}</AlertDescription>
        </Alert>
      ) : null}

      <Separator />

      {/* Split layout: 60% diff | 40% findings */}
      <div className="mt-6 grid gap-6 lg:grid-cols-5">
        <section className="lg:col-span-3">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
            Diff
          </h2>
          {isLoading || !data ? (
            <Skeleton className="h-96 w-full" />
          ) : (
            <DiffViewer
              diff={(data as { diff?: string }).diff ?? null}
              findings={findings}
              scrollToLine={activeLine}
            />
          )}
        </section>

        <aside className="lg:col-span-2">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
            Findings ({findings.length})
          </h2>
          {isLoading ? (
            <div className="space-y-3">
              {Array.from({ length: 3 }).map((_, i) => (
                <Skeleton key={i} className="h-24 w-full" />
              ))}
            </div>
          ) : findings.length === 0 ? (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">No findings</CardTitle>
              </CardHeader>
              <CardContent className="text-sm text-muted-foreground">
                CodeLens didn’t flag anything on this PR. Nice work.
              </CardContent>
            </Card>
          ) : (
            <div className="space-y-3">
              {(["critical", "major", "minor"] as const).map((sev) => {
                const list = grouped[sev] ?? [];
                if (list.length === 0) return null;
                return (
                  <div key={sev}>
                    <div className="mb-2 flex items-center gap-2">
                      <Badge
                        variant="outline"
                        className={`border ${severityClasses(sev)}`}
                      >
                        {sev.toUpperCase()}
                      </Badge>
                      <span className="text-xs text-muted-foreground">
                        {list.length}
                      </span>
                    </div>
                    <div className="space-y-2">
                      {list.map((f) => (
                        <FindingCard
                          key={f.id}
                          finding={f}
                          scrollToLine={setActiveLine}
                          active={
                            f.lineStart != null && f.lineStart === activeLine
                          }
                        />
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  switch (status.toLowerCase()) {
    case "reviewed":
      return (
        <Badge variant="secondary" className="bg-green-100 text-green-800">
          {titleCase(status)}
        </Badge>
      );
    case "failed":
      return <Badge variant="destructive">{titleCase(status)}</Badge>;
    default:
      return <Badge variant="outline">{titleCase(status)}</Badge>;
  }
}

function groupBySeverity(findings: ReviewDetailResponse["findings"]) {
  return {
    critical: findings.filter((f) => f.severity === "critical"),
    major: findings.filter((f) => f.severity === "major"),
    minor: findings.filter((f) => f.severity === "minor"),
  };
}

export default function PRReviewPage() {
  return (
    <AuthShell>
      <PRReviewContent />
    </AuthShell>
  );
}

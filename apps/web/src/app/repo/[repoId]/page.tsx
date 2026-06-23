"use client";

import { useState } from "react";
import useSWR from "swr";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ChevronLeft, ChevronRight, ExternalLink, AlertTriangle } from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { QualityTrendChart } from "@/components/QualityTrendChart";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Separator } from "@/components/ui/separator";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  getRepoDetail,
  getRepoPRs,
} from "@/lib/api";
import {
  qualityScoreColor,
  timeAgo,
  titleCase,
} from "@/lib/utils";
import type {
  PaginatedPRResponse,
  PullRequestSummary,
  RepoDetailResponse,
} from "@/lib/types";

function RepoDetailContent() {
  const params = useParams<{ repoId: string }>();
  const repoId = params.repoId;

  const [page, setPage] = useState(0);
  const pageSize = 20;

  const { data: repo, error: repoError } = useSWR<RepoDetailResponse>(
    repoId ? ["repo", repoId] : null,
    () => getRepoDetail(repoId),
  );
  const {
    data: prs,
    error: prsError,
    isLoading: prsLoading,
  } = useSWR<PaginatedPRResponse<PullRequestSummary>>(
    repoId ? ["repo-prs", repoId, page, pageSize] : null,
    () => getRepoPRs(repoId, page, pageSize),
    { keepPreviousData: true },
  );

  return (
    <div className="mx-auto max-w-7xl p-8">
      <div className="mb-2">
        <Link
          href="/dashboard"
          className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
        >
          <ChevronLeft className="mr-1 h-4 w-4" />
          Back to dashboard
        </Link>
      </div>
      <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">
            {repo?.fullName ?? "Repository"}
          </h1>
          {repo ? (
            <p className="mt-1 text-muted-foreground">
              Connected {timeAgo(repo.createdAt)} ·{" "}
              {repo.active ? "active" : "inactive"}
            </p>
          ) : null}
        </div>
        {repo?.latestQualityScore != null ? (
          <Badge
            className={`border ${qualityScoreColor(repo.latestQualityScore)}`}
            variant="outline"
          >
            {repo.latestQualityScore.toFixed(0)} · latest quality
          </Badge>
        ) : null}
      </header>

      <Separator />

      {/* Two-column body: PR list (left, 2/3) + trend chart (right, 1/3) */}
      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <section className="lg:col-span-2">
          <h2 className="mb-3 text-xl font-semibold tracking-tight">
            Pull requests
          </h2>
          {prsError ? (
            <Alert variant="destructive">
              <AlertTitle>Couldn’t load PRs</AlertTitle>
              <AlertDescription>{(prsError as Error).message}</AlertDescription>
            </Alert>
          ) : null}
          {prsLoading && !prs ? (
            <Skeleton className="h-64 w-full" />
          ) : prs && prs.content.length === 0 ? (
            <div className="rounded-lg border p-8 text-center text-sm text-muted-foreground">
              No PRs reviewed yet.
            </div>
          ) : prs ? (
            <>
              <div className="rounded-lg border">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-16">#</TableHead>
                      <TableHead>Title</TableHead>
                      <TableHead>Author</TableHead>
                      <TableHead>Score</TableHead>
                      <TableHead>Status</TableHead>
                      <TableHead>Reviewed</TableHead>
                      <TableHead className="w-12" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {prs.content.map((pr) => (
                      <TableRow
                        key={pr.id}
                        className="cursor-pointer"
                      >
                        <TableCell className="font-mono text-xs text-muted-foreground">
                          #{pr.githubPrNumber}
                        </TableCell>
                        <TableCell className="max-w-[24ch] truncate">
                          <Link
                            href={`/pr/${pr.id}`}
                            className="hover:underline"
                          >
                            {pr.title ?? "(untitled)"}
                          </Link>
                        </TableCell>
                        <TableCell className="text-muted-foreground">
                          {pr.authorGithub ?? "—"}
                        </TableCell>
                        <TableCell>
                          {pr.qualityScore != null ? (
                            <Badge
                              className={`border ${qualityScoreColor(pr.qualityScore)}`}
                              variant="outline"
                            >
                              {pr.qualityScore.toFixed(0)}
                            </Badge>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </TableCell>
                        <TableCell>
                          <StatusBadge status={pr.status} />
                        </TableCell>
                        <TableCell className="text-muted-foreground">
                          {timeAgo(pr.reviewedAt)}
                        </TableCell>
                        <TableCell>
                          {pr.githubPrUrl ? (
                            <a
                              href={pr.githubPrUrl}
                              target="_blank"
                              rel="noreferrer"
                              className="text-muted-foreground hover:text-foreground"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <ExternalLink className="h-3.5 w-3.5" />
                            </a>
                          ) : null}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              {/* Pagination */}
              <div className="mt-3 flex items-center justify-between text-sm">
                <span className="text-muted-foreground">
                  Page {prs.number + 1} of {Math.max(prs.totalPages, 1)} ·{" "}
                  {prs.totalElements} PR{prs.totalElements === 1 ? "" : "s"}
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={prs.first}
                  >
                    <ChevronLeft className="h-4 w-4" />
                    Prev
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((p) => p + 1)}
                    disabled={prs.last}
                  >
                    Next
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </>
          ) : null}
          {repoError ? (
            <Alert variant="destructive" className="mt-4">
              <AlertTitle>Couldn’t load repo</AlertTitle>
              <AlertDescription>
                {(repoError as Error).message}
              </AlertDescription>
            </Alert>
          ) : null}
        </section>

        <aside className="lg:col-span-1">
          <QualityTrendChart repoId={repoId} days={30} compact />
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
      return (
        <Badge variant="destructive" className="gap-1">
          <AlertTriangle className="h-3 w-3" />
          Failed
        </Badge>
      );
    case "pending":
    default:
      return <Badge variant="outline">{titleCase(status)}</Badge>;
  }
}

export default function RepoDetailPage() {
  return (
    <AuthShell>
      <RepoDetailContent />
    </AuthShell>
  );
}

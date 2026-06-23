"use client";

import Link from "next/link";
import { GitBranch, GitPullRequest, Clock, ChevronRight } from "lucide-react";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { QualitySparkline } from "@/components/QualitySparkline";
import { qualityScoreColor, timeAgo } from "@/lib/utils";
import type {
  QualityTrendDataPoint,
  RepoSummaryResponse,
} from "@/lib/types";

export interface RepoCardProps {
  repo: RepoSummaryResponse;
  /**
   * Optional 7-day sparkline data. When present, a tiny area chart
   * is rendered between the stats and the CTA.
   */
  sparklineData?: QualityTrendDataPoint[];
}

/**
 * Dashboard tile for a single connected repository. Shows the repo name,
 * a colored quality badge (green ≥80, yellow ≥60, red <60), the number of
 * PRs reviewed, when the last review happened, an optional 7-day
 * sparkline (Issue #18), and a "View Details" link to the per-repo page.
 */
export function RepoCard({ repo, sparklineData }: RepoCardProps) {
  const score = repo.latestQualityScore;
  return (
    <Card className="flex flex-col">
      <CardHeader>
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 flex-1">
            <CardTitle className="flex items-center gap-2 text-lg">
              <GitBranch className="h-4 w-4 shrink-0 text-muted-foreground" />
              <span className="truncate">{repo.fullName}</span>
            </CardTitle>
            <CardDescription className="mt-1">
              {repo.active ? "Active" : "Inactive"} · connected{" "}
              {timeAgo(repo.createdAt)}
            </CardDescription>
          </div>
          <Badge
            className={`shrink-0 border ${qualityScoreColor(score)}`}
            variant="outline"
          >
            {score == null ? "—" : score.toFixed(0)}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="flex-1 space-y-3">
        <dl className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <dt className="text-muted-foreground">PRs reviewed</dt>
            <dd className="mt-1 flex items-center gap-1 font-medium">
              <GitPullRequest className="h-3.5 w-3.5" />
              {repo.totalPrsReviewed}
            </dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Last reviewed</dt>
            <dd className="mt-1 flex items-center gap-1 font-medium">
              <Clock className="h-3.5 w-3.5" />
              {timeAgo(repo.lastReviewedAt)}
            </dd>
          </div>
        </dl>
        {sparklineData && sparklineData.length > 0 ? (
          <div className="-mx-1">
            <QualitySparkline data={sparklineData} height={36} />
            <div className="mt-1 text-center text-[10px] uppercase tracking-wider text-muted-foreground">
              7-day trend
            </div>
          </div>
        ) : null}
      </CardContent>
      <CardFooter>
        <Button asChild variant="outline" className="w-full">
          <Link href={`/repo/${repo.id}`}>
            View Details
            <ChevronRight className="ml-2 h-4 w-4" />
          </Link>
        </Button>
      </CardFooter>
    </Card>
  );
}

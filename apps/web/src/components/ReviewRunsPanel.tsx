"use client";

import useSWR from "swr";
import { ExternalLink } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getReviewRuns } from "@/lib/api";
import { formatDateTime, githubCommentUrl, shortSha, timeAgo, titleCase } from "@/lib/utils";
import type { ReviewRun } from "@/lib/types";

/** `processed_webhooks.status` values (V16): received, processing, completed, failed, dead_letter, superseded. */
const RUN_STATUS_CLASS: Record<string, string> = {
  completed: "bg-green-100 text-green-800 border-green-300",
  failed: "bg-yellow-100 text-yellow-800 border-yellow-300",
  dead_letter: "bg-red-100 text-red-800 border-red-300",
  superseded: "bg-gray-100 text-gray-700 border-gray-300",
};

function runStatusClass(status: string): string {
  const key = status.toLowerCase();
  return Object.hasOwn(RUN_STATUS_CLASS, key) ? RUN_STATUS_CLASS[key] : "";
}

function Publication({ publication }: { publication: ReviewRun["publication"] }) {
  if (!publication) return <span className="text-muted-foreground">Not published</span>;
  const href = githubCommentUrl(publication.commentUrl);
  return (
    <div className="space-y-0.5">
      <div>{titleCase(publication.status)}</div>
      {publication.publishedHeadSha ? (
        <div className="font-mono text-xs text-muted-foreground">
          at {shortSha(publication.publishedHeadSha)}
        </div>
      ) : null}
      {href ? (
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 text-xs underline underline-offset-2"
        >
          View comment
          <ExternalLink className="h-3 w-3" aria-hidden="true" />
          <span className="sr-only">(opens GitHub in a new tab)</span>
        </a>
      ) : null}
    </div>
  );
}

/**
 * History of webhook deliveries (review runs) for one pull request: when each
 * arrived, which head SHA it reviewed, attempts, the last error, and whether
 * the summary comment was published.
 */
export function ReviewRunsPanel({ prId }: { prId: string }) {
  const { data, error, isLoading } = useSWR<ReviewRun[]>(
    ["review-runs", prId],
    () => getReviewRuns(prId),
    { revalidateOnFocus: false },
  );

  return (
    <section aria-labelledby="review-runs-heading" className="space-y-3">
      <h2
        id="review-runs-heading"
        className="text-sm font-semibold uppercase tracking-wider text-muted-foreground"
      >
        Review runs{data ? ` (${data.length})` : ""}
      </h2>
      {error ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn’t load review runs</AlertTitle>
          <AlertDescription>{(error as Error).message}</AlertDescription>
        </Alert>
      ) : isLoading || !data ? (
        <Skeleton className="h-24 w-full" data-testid="review-runs-loading" />
      ) : data.length === 0 ? (
        <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground">
          No webhook deliveries recorded for this pull request yet.
        </p>
      ) : (
        <div
          className="rounded-lg border"
        >
          <Table scrollLabel="Review runs">
            <TableHeader>
              <TableRow>
                <TableHead>Received</TableHead>
                <TableHead>Event</TableHead>
                <TableHead>Head SHA</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Attempts</TableHead>
                <TableHead>Completed</TableHead>
                <TableHead>Comment</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((run) => (
                <TableRow key={run.deliveryId} data-testid="review-run">
                  <TableCell className="whitespace-nowrap">
                    <time dateTime={run.receivedAt} title={formatDateTime(run.receivedAt)}>
                      {timeAgo(run.receivedAt)}
                    </time>
                    <div className="font-mono text-xs text-muted-foreground" title={run.deliveryId}>
                      {run.deliveryId.slice(0, 8)}
                    </div>
                  </TableCell>
                  <TableCell>{run.action ? titleCase(run.action) : "—"}</TableCell>
                  <TableCell className="font-mono text-xs" title={run.headSha ?? undefined}>
                    {shortSha(run.headSha)}
                  </TableCell>
                  <TableCell className="max-w-[28ch]">
                    <Badge variant="outline" className={`border ${runStatusClass(run.status)}`}>
                      {titleCase(run.status)}
                    </Badge>
                    {run.lastError ? (
                      <p className="mt-1 break-words text-xs text-destructive">{run.lastError}</p>
                    ) : null}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{run.attemptCount}</TableCell>
                  <TableCell className="whitespace-nowrap">
                    {run.completedAt ? (
                      <time dateTime={run.completedAt} title={formatDateTime(run.completedAt)}>
                        {timeAgo(run.completedAt)}
                      </time>
                    ) : (
                      <span className="text-muted-foreground">—</span>
                    )}
                  </TableCell>
                  <TableCell className="text-sm">
                    <Publication publication={run.publication} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </section>
  );
}

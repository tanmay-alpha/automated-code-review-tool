"use client";

import { useId, useState } from "react";
import useSWR from "swr";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getFindingSummary } from "@/lib/api";
import { ruleDisplayName } from "@/lib/taxonomy";
import { titleCase } from "@/lib/utils";
import type { FindingSummaryByRule, FindingSummaryResponse } from "@/lib/types";

const RANGES = [7, 30, 90] as const;

/**
 * Share of triaged findings marked false positive, as a whole percentage;
 * null when nothing has been triaged yet (open findings carry no signal).
 */
export function falsePositiveRate(row: FindingSummaryByRule): number | null {
  const triaged = row.total - row.open;
  return triaged > 0 ? Math.round((row.falsePositive / triaged) * 100) : null;
}

export function summaryTotals(rows: FindingSummaryByRule[]): FindingSummaryByRule {
  const totals: FindingSummaryByRule = {
    antiPattern: "Total",
    total: 0,
    open: 0,
    accepted: 0,
    dismissed: 0,
    fixed: 0,
    falsePositive: 0,
  };
  for (const r of rows) {
    totals.total += r.total;
    totals.open += r.open;
    totals.accepted += r.accepted;
    totals.dismissed += r.dismissed;
    totals.fixed += r.fixed;
    totals.falsePositive += r.falsePositive;
  }
  return totals;
}

function RateCell({ row }: { row: FindingSummaryByRule }) {
  const rate = falsePositiveRate(row);
  return (
    <TableCell className="text-right tabular-nums">
      {rate == null ? <span className="text-muted-foreground">—</span> : `${rate}%`}
    </TableCell>
  );
}

/**
 * Per-rule finding counts and triage outcomes for a repository over the last
 * 7/30/90 days (contract: GET /api/metrics/finding-summary), plus which
 * detection engine produced the findings.
 */
export function FindingSummaryPanel({ repoId }: { repoId: string }) {
  const [days, setDays] = useState<(typeof RANGES)[number]>(30);
  const captionId = useId();
  const { data, error, isLoading } = useSWR<FindingSummaryResponse>(
    ["finding-summary", repoId, days],
    () => getFindingSummary(repoId, days),
    { revalidateOnFocus: false, keepPreviousData: true },
  );

  const rows = [...(data?.byRule ?? [])].sort(
    (a, b) => b.total - a.total || a.antiPattern.localeCompare(b.antiPattern),
  );
  const totals = summaryTotals(rows);

  return (
    <Card>
      <CardHeader className="flex flex-col gap-3 space-y-0 sm:flex-row sm:items-start sm:justify-between">
        <div className="space-y-1.5">
          <CardTitle className="text-lg">
            <h2>Findings by rule</h2>
          </CardTitle>
          <CardDescription id={captionId}>
            Findings and how they were triaged in the last {days} days. The
            false-positive rate counts triaged findings only.
          </CardDescription>
        </div>
        <div role="group" aria-label="Summary period" className="flex gap-1">
          {RANGES.map((d) => (
            <Button
              key={d}
              type="button"
              size="sm"
              variant={days === d ? "secondary" : "outline"}
              aria-pressed={days === d}
              onClick={() => setDays(d)}
            >
              {d}d
            </Button>
          ))}
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {error ? (
          <Alert variant="destructive">
            <AlertTitle>Couldn’t load the finding summary</AlertTitle>
            <AlertDescription>{(error as Error).message}</AlertDescription>
          </Alert>
        ) : isLoading && !data ? (
          <Skeleton className="h-40 w-full" />
        ) : rows.length === 0 ? (
          <p className="rounded-lg border p-6 text-center text-sm text-muted-foreground">
            No findings in the last {days} days.
          </p>
        ) : (
          <>
            <div
              className="rounded-lg border"
            >
              <Table aria-describedby={captionId} scrollLabel="Findings by rule">
                <TableHeader>
                  <TableRow>
                    <TableHead>Rule</TableHead>
                    <TableHead className="text-right">Total</TableHead>
                    <TableHead className="text-right">Open</TableHead>
                    <TableHead className="text-right">Accepted</TableHead>
                    <TableHead className="text-right">Fixed</TableHead>
                    <TableHead className="text-right">Dismissed</TableHead>
                    <TableHead className="text-right">False positive</TableHead>
                    <TableHead className="text-right">FP rate</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((r) => (
                    <TableRow key={r.antiPattern} data-testid="summary-row">
                      <TableCell className="font-medium">
                        <span title={r.antiPattern}>{ruleDisplayName(r.antiPattern)}</span>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{r.total}</TableCell>
                      <TableCell className="text-right tabular-nums">{r.open}</TableCell>
                      <TableCell className="text-right tabular-nums">{r.accepted}</TableCell>
                      <TableCell className="text-right tabular-nums">{r.fixed}</TableCell>
                      <TableCell className="text-right tabular-nums">{r.dismissed}</TableCell>
                      <TableCell className="text-right tabular-nums">{r.falsePositive}</TableCell>
                      <RateCell row={r} />
                    </TableRow>
                  ))}
                  <TableRow className="font-semibold">
                    <TableCell>Total</TableCell>
                    <TableCell className="text-right tabular-nums">{totals.total}</TableCell>
                    <TableCell className="text-right tabular-nums">{totals.open}</TableCell>
                    <TableCell className="text-right tabular-nums">{totals.accepted}</TableCell>
                    <TableCell className="text-right tabular-nums">{totals.fixed}</TableCell>
                    <TableCell className="text-right tabular-nums">{totals.dismissed}</TableCell>
                    <TableCell className="text-right tabular-nums">{totals.falsePositive}</TableCell>
                    <RateCell row={totals} />
                  </TableRow>
                </TableBody>
              </Table>
            </div>
            {data && data.byEngine.length > 0 ? (
              <div className="text-sm">
                <h3 className="mb-2 font-medium">By detection engine</h3>
                <ul className="flex flex-wrap gap-2">
                  {data.byEngine.map((e) => (
                    <li key={e.engine} className="rounded-md border px-3 py-1">
                      {titleCase(e.engine)}: <span className="tabular-nums">{e.total}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}

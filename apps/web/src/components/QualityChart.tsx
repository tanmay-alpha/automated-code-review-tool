"use client";

import { useState } from "react";
import useSWR from "swr";
import {
  ResponsiveContainer,
  Line,
  Area,
  ComposedChart,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
} from "recharts";
import { AlertTriangle } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { cn, titleCase } from "@/lib/utils";
import { getQualityTrend } from "@/lib/api";
import type { QualityTrendPoint } from "@/lib/types";

const PERIODS = [
  { label: "7d", days: 7 },
  { label: "30d", days: 30 },
  { label: "90d", days: 90 },
] as const;

export interface QualityChartProps {
  /**
   * Either a `repoId` (the chart fetches /api/metrics/quality-trend and
   * offers a 7d/30d/90d toggle) or pre-computed `trend` data (rendered
   * as-is, no toggle — e.g. the dashboard's cross-repo aggregate).
   */
  repoId?: string;
  trend?: QualityTrendPoint[];
  /** Hides the legend (used in the narrow right-rail layout). */
  compact?: boolean;
  /** Title shown above the chart. Defaults to "Quality trend". */
  title?: string;
}

type ChartRow = QualityTrendPoint & { displayDate: string };

/**
 * Quality trend chart:
 *   - Line: average quality score per day (0–100, left Y axis).
 *   - Red area: critical findings per day (right Y axis).
 *   - Tooltip: date, score, PRs reviewed, critical/major/minor counts.
 */
export function QualityChart({
  repoId,
  trend,
  compact = false,
  title = "Quality trend",
}: QualityChartProps) {
  const [days, setDays] = useState<number>(30);

  const shouldFetch = !!repoId && trend === undefined;
  const { data, error, isLoading } = useSWR(
    shouldFetch ? ["quality-trend", repoId, days] : null,
    () => getQualityTrend(repoId as string, days),
    { keepPreviousData: true, revalidateOnFocus: false },
  );

  const chartData: ChartRow[] = (trend ?? data?.trend ?? []).map((p) => ({
    ...p,
    displayDate: formatDate(p.date),
  }));
  const topAntiPattern = shouldFetch ? data?.topAntiPattern : null;

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className={compact ? "text-base" : "text-lg"}>
          {title}
        </CardTitle>
        {shouldFetch ? (
          <div className="flex items-center gap-1 rounded-md border p-0.5">
            {PERIODS.map((p) => (
              <Button
                key={p.days}
                variant={p.days === days ? "default" : "ghost"}
                size="sm"
                className={cn(
                  "h-7 px-2 text-xs",
                  p.days === days && "shadow-sm",
                )}
                onClick={() => setDays(p.days)}
              >
                {p.label}
              </Button>
            ))}
          </div>
        ) : null}
      </CardHeader>
      <CardContent className={compact ? "p-4 pt-0" : undefined}>
        {error ? (
          <Alert variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertTitle>Couldn’t load trend</AlertTitle>
            <AlertDescription>{(error as Error).message}</AlertDescription>
          </Alert>
        ) : isLoading && chartData.length === 0 ? (
          <Skeleton className="h-64 w-full" />
        ) : chartData.length === 0 ? (
          <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">
            No reviewed PRs in this period.
          </div>
        ) : (
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart
                data={chartData}
                margin={{ top: 10, right: 8, bottom: 0, left: 0 }}
              >
                <defs>
                  <linearGradient
                    id="critical-area-fill"
                    x1="0"
                    y1="0"
                    x2="0"
                    y2="1"
                  >
                    <stop
                      offset="0%"
                      stopColor="rgb(239, 68, 68)"
                      stopOpacity={0.35}
                    />
                    <stop
                      offset="100%"
                      stopColor="rgb(239, 68, 68)"
                      stopOpacity={0.05}
                    />
                  </linearGradient>
                </defs>
                <CartesianGrid
                  strokeDasharray="3 3"
                  stroke="hsl(var(--border))"
                />
                <XAxis
                  dataKey="displayDate"
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                />
                <YAxis
                  yAxisId="left"
                  domain={[0, 100]}
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                  width={36}
                />
                <YAxis
                  yAxisId="right"
                  orientation="right"
                  allowDecimals={false}
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                  width={32}
                />
                <Tooltip
                  content={<QualityTooltip />}
                  wrapperStyle={{ outline: "none" }}
                />
                {!compact ? (
                  <Legend
                    verticalAlign="top"
                    height={24}
                    iconType="line"
                    wrapperStyle={{ fontSize: 12 }}
                  />
                ) : null}
                <Area
                  yAxisId="right"
                  type="monotone"
                  dataKey="criticalCount"
                  name="Critical findings"
                  stroke="rgb(239, 68, 68)"
                  strokeWidth={1.5}
                  fill="url(#critical-area-fill)"
                  isAnimationActive={false}
                />
                <Line
                  yAxisId="left"
                  type="monotone"
                  dataKey="avgQuality"
                  name="Quality score"
                  stroke="hsl(var(--primary))"
                  strokeWidth={2}
                  dot={{ r: 2 }}
                  connectNulls
                  isAnimationActive={false}
                />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        )}

        {topAntiPattern ? (
          <p className="mt-3 text-xs text-muted-foreground">
            Most frequent anti-pattern in this window:{" "}
            <span className="font-medium">{titleCase(topAntiPattern)}</span>
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** Tooltip body: recharts passes the hovered row in `payload[0].payload`. */
function QualityTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: Array<{ payload?: ChartRow }>;
}) {
  if (!active || !payload || payload.length === 0) return null;
  const row = payload[0]?.payload;
  if (!row) return null;
  return (
    <div className="rounded-md border bg-popover px-3 py-2 text-xs shadow-sm">
      <div className="mb-1 font-medium">{row.displayDate}</div>
      <div className="grid grid-cols-2 gap-x-3 gap-y-0.5">
        <span className="text-muted-foreground">Score</span>
        <span className="text-right font-mono">
          {row.avgQuality == null ? "—" : row.avgQuality.toFixed(1)}
        </span>
        <span className="text-muted-foreground">PRs reviewed</span>
        <span className="text-right font-mono">{row.prsReviewed}</span>
        <span className="text-red-600">Critical</span>
        <span className="text-right font-mono">{row.criticalCount}</span>
        <span className="text-orange-600">Major</span>
        <span className="text-right font-mono">{row.majorCount}</span>
        <span className="text-yellow-700">Minor</span>
        <span className="text-right font-mono">{row.minorCount}</span>
      </div>
    </div>
  );
}

/** "YYYY-MM-DD" → "Jun 19"; returns the input unchanged if it isn't a valid date. */
function formatDate(iso: string): string {
  const d = new Date(iso + "T00:00:00Z");
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

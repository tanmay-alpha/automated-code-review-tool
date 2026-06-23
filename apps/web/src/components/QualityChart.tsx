"use client";

import { useState } from "react";
import useSWR from "swr";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  Area,
  AreaChart,
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
import { cn } from "@/lib/utils";
import { getQualityTrend } from "@/lib/api";
import type { QualityTrendDataPoint } from "@/lib/types";

export type QualityPeriod = "7d" | "30d" | "90d";

const PERIODS: ReadonlyArray<{ id: QualityPeriod; label: string; days: number }> = [
  { id: "7d", label: "7d", days: 7 },
  { id: "30d", label: "30d", days: 30 },
  { id: "90d", label: "90d", days: 90 },
] as const;

export interface QualityChartProps {
  /**
   * Either a `repoId` (the chart will fetch /api/metrics/quality-trend
   * for it) or pre-computed `trend` data. Pass `trend` directly when
   * the data is already available (e.g. the global dashboard chart
   * which aggregates across repos client-side).
   */
  repoId?: string;
  trend?: QualityTrendDataPoint[];
  topAntiPattern?: string | null;
  /** Initial period — the chart is controlled by the toggle. */
  initialPeriod?: QualityPeriod;
  /** Compact hides axis labels & legend (used in tight right-rail layouts). */
  compact?: boolean;
  /** Title shown above the chart. Defaults to "Quality trend". */
  title?: string;
}

/**
 * Quality trend visualisation (Issue #18).
 *
 *   - Blue line: avg quality score, 0–100, Y axis left.
 *   - Red translucent area: critical findings per day, Y axis right.
 *   - X axis: date (formatted "Jun 19").
 *   - Tooltip: date, score, PRs reviewed, critical/major/minor counts.
 *   - Period toggle (7d / 30d / 90d) drives the SWR key so the
 *     component reuses the request body cache and animates in place.
 *
 * Uses ComposedChart so we can overlay <Line> on top of <Area> with two
 * independent yAxisId values. ResponsiveContainer makes it adapt to its
 * parent width.
 */
export function QualityChart({
  repoId,
  trend,
  topAntiPattern,
  initialPeriod = "30d",
  compact = false,
  title = "Quality trend",
}: QualityChartProps) {
  const [period, setPeriod] = useState<QualityPeriod>(initialPeriod);
  const days = PERIODS.find((p) => p.id === period)?.days ?? 30;

  // Fetch only when we have a repoId. If `trend` is provided the parent
  // already has the data — we just use it.
  const shouldFetch = !!repoId && trend === undefined;
  const { data, error, isLoading } = useSWR(
    shouldFetch ? ["quality-trend", repoId, days] : null,
    () => getQualityTrend(repoId as string, days),
    { keepPreviousData: true, revalidateOnFocus: false },
  );

  const sourceTrend: QualityTrendDataPoint[] =
    trend ?? data?.trend ?? [];
  const sourceTopPattern: string | null | undefined =
    topAntiPattern ?? data?.topAntiPattern;

  // Render-only shape: date as "Jun 19" so the X axis stays compact.
  const chartData: Array<
    QualityTrendDataPoint & { label: string; displayDate: string }
  > = sourceTrend.map((p) => ({
    ...p,
    label: p.date.slice(5), // "MM-DD"
    displayDate: formatDate(p.date),
  }));

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className={compact ? "text-base" : "text-lg"}>
          {title}
        </CardTitle>
        <div className="flex items-center gap-1 rounded-md border p-0.5">
          {PERIODS.map((p) => (
            <Button
              key={p.id}
              variant={p.id === period ? "default" : "ghost"}
              size="sm"
              className={cn(
                "h-7 px-2 text-xs",
                p.id === period && "shadow-sm",
              )}
              onClick={() => setPeriod(p.id)}
            >
              {p.label}
            </Button>
          ))}
        </div>
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
            No data in the last {days} days. Connect a repo and review a PR
            to start tracking.
          </div>
        ) : null}

        {sourceTopPattern ? null : (
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart
                data={chartData}
                margin={{ top: 10, right: 8, bottom: 0, left: 0 }}
              >
                <defs>
                  <linearGradient
                    id="codelens-critical-area"
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
                  dataKey="label"
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                  tickFormatter={(v) =>
                    typeof v === "string" ? formatShortDate(v) : String(v)
                  }
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
                  fill="url(#codelens-critical-area)"
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

        {sourceTopPattern ? (
          <p className="mt-3 text-xs text-muted-foreground">
            Most frequent anti-pattern in this window:{" "}
            <span className="font-medium">
              {sourceTopPattern
                .toLowerCase()
                .split("_")
                .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
                .join(" ")}
            </span>
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

/**
 * Custom tooltip — recharts passes a `payload` array; we render a small
 * grid with the day, score, PRs reviewed, and a breakdown by severity.
 */
function QualityTooltip({
  active,
  payload,
  label,
}: {
  active?: boolean;
  // recharts types are loose here; we narrow in the body.
  payload?: Array<{ payload?: ChartRow; dataKey?: string; value?: number | string }>;
  label?: string;
}) {
  if (!active || !payload || payload.length === 0) return null;
  const row = payload[0]?.payload as ChartRow | undefined;
  if (!row) return null;
  return (
    <div className="rounded-md border bg-popover px-3 py-2 text-xs shadow-sm">
      <div className="mb-1 font-medium">{row.displayDate ?? label}</div>
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

type ChartRow = QualityTrendDataPoint & {
  label: string;
  displayDate: string;
};

/**
 * "YYYY-MM-DD" → "Jun 19". Falls back to the original string if the
 * input isn't a valid ISO date.
 */
function formatDate(iso: string): string {
  const d = new Date(iso + "T00:00:00Z");
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

function formatShortDate(mmdd: string): string {
  // "MM-DD" → "Jun 19" using the current year (good enough for sparkline
  // tick labels; the tooltip uses the full year via formatDate).
  const [m, d] = mmdd.split("-").map((n) => Number.parseInt(n, 10));
  if (!m || !d) return mmdd;
  const year = new Date().getFullYear();
  const dt = new Date(Date.UTC(year, m - 1, d));
  if (Number.isNaN(dt.getTime())) return mmdd;
  return dt.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

// keep recharts components tree-shake-friendly
export { LineChart, AreaChart };

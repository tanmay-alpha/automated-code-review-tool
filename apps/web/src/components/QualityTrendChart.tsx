"use client";

import useSWR from "swr";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
} from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { getQualityTrend } from "@/lib/api";

export interface QualityTrendChartProps {
  repoId: string;
  days?: number;
  /** Compact mode is used in the right rail of /repo/[id]. */
  compact?: boolean;
}

/**
 * Mini line chart for /repo/[id]. One data point per day from
 * /api/metrics/quality-trend. In `compact` mode we drop axes and
 * simplify the tooltip.
 */
export function QualityTrendChart({
  repoId,
  days = 30,
  compact = false,
}: QualityTrendChartProps) {
  const { data, error, isLoading } = useSWR(
    ["quality-trend", repoId, days],
    () => getQualityTrend(repoId, days),
    { revalidateOnFocus: false },
  );

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Couldn’t load trend</AlertTitle>
        <AlertDescription>{(error as Error).message}</AlertDescription>
      </Alert>
    );
  }

  return (
    <Card>
      <CardHeader className={compact ? "p-4" : undefined}>
        <CardTitle className={compact ? "text-base" : "text-lg"}>
          Quality trend · last {days} days
        </CardTitle>
      </CardHeader>
      <CardContent className={compact ? "p-4 pt-0" : undefined}>
        {isLoading || !data ? (
          <Skeleton className="h-40 w-full" />
        ) : data.trend.length === 0 ? (
          <div className="flex h-40 items-center justify-center text-sm text-muted-foreground">
            No data yet — connect reviews to start tracking.
          </div>
        ) : (
          <div className="h-40 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart
                data={data.trend.map((p) => ({
                  ...p,
                  label: p.date.slice(5), // "MM-DD"
                }))}
                margin={{ top: 4, right: 8, bottom: 4, left: 0 }}
              >
                <CartesianGrid
                  strokeDasharray="3 3"
                  stroke="hsl(var(--border))"
                />
                <XAxis
                  dataKey="label"
                  hide={compact}
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                />
                <YAxis
                  domain={[0, 100]}
                  hide={compact}
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                />
                <Tooltip
                  contentStyle={{
                    background: "hsl(var(--popover))",
                    border: "1px solid hsl(var(--border))",
                    fontSize: 12,
                  }}
                  formatter={(value: number | null) =>
                    value == null ? "—" : value.toFixed(1)
                  }
                  labelFormatter={(l) => `Day: ${l}`}
                />
                <Line
                  type="monotone"
                  dataKey="avgQuality"
                  stroke="hsl(var(--primary))"
                  strokeWidth={2}
                  dot={{ r: 2 }}
                  connectNulls
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
        {data?.topAntiPattern ? (
          <p className="mt-2 text-xs text-muted-foreground">
            Most frequent anti-pattern this window:{" "}
            <span className="font-medium">
              {data.topAntiPattern
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

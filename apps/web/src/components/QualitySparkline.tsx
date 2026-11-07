"use client";

import { useId } from "react";
import { ResponsiveContainer, AreaChart, Area, YAxis } from "recharts";
import type { QualityTrendPoint } from "@/lib/types";

export interface QualitySparklineProps {
  /** Daily quality samples — the last 7 days is typical. */
  data: QualityTrendPoint[];
  /** Pixel height of the sparkline. Default 32. */
  height?: number;
}

/**
 * Tiny area chart used in RepoCard. No axes, no tooltip. The stroke colour
 * follows the most recent score: green ≥ 80, yellow ≥ 60, red below.
 */
export function QualitySparkline({ data, height = 32 }: QualitySparklineProps) {
  const gradientId = useId();
  if (data.length === 0) {
    return <div style={{ height }} aria-hidden />;
  }

  const latest =
    [...data].reverse().find((d) => d.avgQuality != null)?.avgQuality ?? null;
  const stroke =
    latest == null
      ? "hsl(var(--muted-foreground))"
      : latest >= 80
      ? "rgb(22, 163, 74)"
      : latest >= 60
      ? "rgb(202, 138, 4)"
      : "rgb(220, 38, 38)";

  return (
    <div style={{ height, lineHeight: 0 }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 2, right: 0, bottom: 2, left: 0 }}>
          <defs>
            <linearGradient id={`spark-${gradientId}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={stroke} stopOpacity={0.4} />
              <stop offset="100%" stopColor={stroke} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <YAxis hide domain={[0, 100]} />
          <Area
            type="monotone"
            dataKey="avgQuality"
            stroke={stroke}
            strokeWidth={1.5}
            fill={`url(#spark-${gradientId})`}
            isAnimationActive={false}
            connectNulls
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

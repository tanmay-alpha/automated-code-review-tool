"use client";

import {
  ResponsiveContainer,
  AreaChart,
  Area,
  YAxis,
} from "recharts";
import { qualityScoreColor } from "@/lib/utils";
import type { QualityTrendDataPoint } from "@/lib/types";

export interface QualitySparklineProps {
  /** Daily quality samples — the last 7 days is typical. */
  data: QualityTrendDataPoint[];
  /** Pixel height of the sparkline. Default 32. */
  height?: number;
  /** Optional className for the wrapper. */
  className?: string;
}

/**
 * Tiny area chart used in RepoCard to give a glanceable "how is this
 * repo trending?" signal. No axes, no tooltip — purely visual.
 *
 * The line colour is derived from the latest data point's score, so
 * a green sparkline = a healthy repo, red = needs attention.
 */
export function QualitySparkline({
  data,
  height = 32,
  className,
}: QualitySparklineProps) {
  if (!data || data.length === 0) {
    return (
      <div
        className={className}
        style={{ height }}
        aria-hidden
      />
    );
  }

  // The most recent non-null score drives the colour.
  const latest =
    [...data].reverse().find((d) => d.avgQuality != null)?.avgQuality ?? null;
  const colourClass = qualityScoreColor(latest);

  // Map the colourClass tail (red/green/yellow) to a concrete stroke.
  const stroke =
    latest == null
      ? "hsl(var(--muted-foreground))"
      : latest >= 80
      ? "rgb(22, 163, 74)"
      : latest >= 60
      ? "rgb(202, 138, 4)"
      : "rgb(220, 38, 38)";

  return (
    <div
      className={className}
      style={{ height, lineHeight: 0 }}
      data-testid="quality-sparkline"
      data-score={latest ?? "null"}
    >
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart
          data={data.map((d) => ({ ...d, label: d.date.slice(5) }))}
          margin={{ top: 2, right: 0, bottom: 2, left: 0 }}
        >
          <defs>
            <linearGradient
              id={`spark-${stroke.replace(/[^a-z0-9]/gi, "")}`}
              x1="0"
              y1="0"
              x2="0"
              y2="1"
            >
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
            fill={`url(#spark-${stroke.replace(/[^a-z0-9]/gi, "")})`}
            isAnimationActive={false}
            connectNulls
          />
        </AreaChart>
      </ResponsiveContainer>
      {/* `colourClass` is included as a hook for the surrounding layout
          (e.g. a coloured top border) — currently unused, but keeps the
          import live and signals intent. */}
      <span className="sr-only">{colourClass}</span>
    </div>
  );
}

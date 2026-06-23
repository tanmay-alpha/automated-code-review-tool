import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Combines clsx + tailwind-merge so duplicate Tailwind classes
 * are deduplicated (e.g. `p-2 p-4` → `p-4`). Every shadcn component
 * imports this for its `cn()` helper.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Maps a numeric quality score (0–100) to a tailwind color class. */
export function qualityScoreColor(score: number | null | undefined): string {
  if (score == null) return "bg-gray-200 text-gray-700";
  if (score >= 80) return "bg-green-100 text-green-800 border-green-300";
  if (score >= 60) return "bg-yellow-100 text-yellow-800 border-yellow-300";
  return "bg-red-100 text-red-800 border-red-300";
}

/** Convert an API enum like CRITICAL into a UI-friendly "Critical". */
export function titleCase(s: string | null | undefined): string {
  if (!s) return "";
  return s
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/**
 * Formats PERF_N_PLUS_ONE → "N+1 Query Performance" (display-friendly
 * rendering of an anti-pattern id).
 */
export function formatAntiPattern(id: string | null | undefined): string {
  if (!id) return "Unknown";
  return id
    .toLowerCase()
    .split("_")
    .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(" ")
    .replace(/\bn\b/g, "N")
    .replace(/\b1\b/g, "1")
    .replace(/\b1 plus 1\b/g, "N+1")
    .replace(/\bn plus 1\b/g, "N+1")
    .replace(/\bquery\b/gi, "Query");
}

/**
 * Render an ISO instant as a short human-readable relative-time string
 * (e.g. "2h ago", "3 days ago"). Returns "—" for null/undefined.
 */
export function timeAgo(iso: string | null | undefined): string {
  if (!iso) return "—";
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "—";
  const diffMs = Date.now() - t;
  const sec = Math.round(diffMs / 1000);
  if (sec < 60) return `${sec}s ago`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m ago`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const days = Math.round(hr / 24);
  if (days < 30) return `${days}d ago`;
  const months = Math.round(days / 30);
  if (months < 12) return `${months}mo ago`;
  return `${Math.round(months / 12)}y ago`;
}

/** Severity → badge variant class. */
export function severityClasses(sev: string | null | undefined): string {
  switch ((sev ?? "").toLowerCase()) {
    case "critical":
      return "bg-red-100 text-red-800 border-red-300";
    case "major":
      return "bg-orange-100 text-orange-800 border-orange-300";
    case "minor":
      return "bg-yellow-100 text-yellow-800 border-yellow-300";
    default:
      return "bg-gray-100 text-gray-700 border-gray-300";
  }
}

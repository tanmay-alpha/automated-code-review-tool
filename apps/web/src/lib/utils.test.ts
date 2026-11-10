import { afterEach, describe, expect, it, vi } from "vitest";
import {
  githubCommentUrl,
  githubPullRequestUrl,
  qualityScoreColor,
  shortSha,
  timeAgo,
  titleCase,
} from "@/lib/utils";

afterEach(() => {
  vi.useRealTimers();
});

describe("titleCase", () => {
  it("turns API enums into readable labels", () => {
    expect(titleCase("CRITICAL")).toBe("Critical");
    expect(titleCase("SECURITY_HARDCODED_SECRET")).toBe("Security Hardcoded Secret");
    expect(titleCase(null)).toBe("");
  });
});

describe("timeAgo", () => {
  it("formats relative times and handles missing or future values", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-10T12:00:00Z"));

    expect(timeAgo("2026-01-10T11:59:30Z")).toBe("30s ago");
    expect(timeAgo("2026-01-10T10:00:00Z")).toBe("2h ago");
    expect(timeAgo("2026-01-07T12:00:00Z")).toBe("3d ago");
    expect(timeAgo("2026-01-10T12:00:05Z")).toBe("0s ago");
    expect(timeAgo(null)).toBe("—");
    expect(timeAgo("not a date")).toBe("—");
  });
});

describe("qualityScoreColor", () => {
  it("uses the 80 / 60 thresholds", () => {
    expect(qualityScoreColor(80)).toContain("green");
    expect(qualityScoreColor(60)).toContain("yellow");
    expect(qualityScoreColor(59.9)).toContain("red");
    expect(qualityScoreColor(null)).toContain("gray");
  });
});

describe("githubPullRequestUrl", () => {
  it("accepts only HTTPS GitHub pull requests without credentials", () => {
    expect(githubPullRequestUrl("https://github.com/owner/repo/pull/42")).toBe(
      "https://github.com/owner/repo/pull/42",
    );
    for (const value of [
      "javascript:alert(document.cookie)",
      "data:text/html,<script>alert(1)</script>",
      "//github.com/owner/repo/pull/42",
      "https://github.com.evil.test/owner/repo/pull/42",
      "https://github.com@evil.test/owner/repo/pull/42",
      "https://user:password@github.com/owner/repo/pull/42",
      "http://github.com/owner/repo/pull/42",
      "https://github.com/owner/repo/issues/42",
    ]) expect(githubPullRequestUrl(value)).toBeUndefined();
  });
});

describe("githubCommentUrl", () => {
  it("accepts only HTTPS GitHub PR or issue comment links", () => {
    expect(githubCommentUrl("https://github.com/o/r/pull/3#issuecomment-9")).toBe(
      "https://github.com/o/r/pull/3#issuecomment-9",
    );
    expect(githubCommentUrl("https://github.com/o/r/issues/3#issuecomment-9")).toBeDefined();
    expect(githubCommentUrl("javascript:alert(1)")).toBeUndefined();
    expect(githubCommentUrl("https://evil.example/o/r/pull/3")).toBeUndefined();
    expect(githubCommentUrl("http://github.com/o/r/pull/3")).toBeUndefined();
    expect(githubCommentUrl("https://u:p@github.com/o/r/pull/3")).toBeUndefined();
    expect(githubCommentUrl(null)).toBeUndefined();
  });
});

describe("shortSha", () => {
  it("abbreviates commit SHAs", () => {
    expect(shortSha("0123456789abcdef")).toBe("0123456");
    expect(shortSha(null)).toBe("—");
  });
});

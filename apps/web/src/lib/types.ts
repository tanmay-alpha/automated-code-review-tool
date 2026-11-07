// =============================================================================
// API response types — mirror the Java records under
// apps/api/src/main/java/com/automatedcodereviewtool/dto/{,response/}/*.java.
// Keep field names in sync with those records; Java's record component names
// serialize to JSON verbatim (Jackson defaults).
// =============================================================================

// ---------- /api/auth/* ----------

/** GET /api/auth/me → UserResponse.java */
export interface UserResponse {
  id: string;
  githubUsername: string;
  avatarUrl: string | null;
  createdAt: string; // ISO Instant
}

// ---------- /api/repos/* ----------

/** GET /api/repos, GET /api/repos/{id}, POST /api/repos/connect → RepoResponse.java */
export interface RepoResponse {
  id: string;
  fullName: string;
  githubId: number;
  active: boolean;
  createdAt: string;
  totalPrsReviewed: number;
  lastReviewedAt: string | null;
  latestQualityScore: number | null;
}

// ---------- /api/repos/{id}/prs ----------

/** The subset of the Spring Data page envelope the dashboard reads. */
export interface PaginatedPRResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // zero-based page index
  first: boolean;
  last: boolean;
}

/** One row of the PR list. */
export interface PullRequestSummary {
  id: string;
  githubPrNumber: number;
  title: string | null;
  authorGithub: string | null;
  headSha: string | null;
  githubPrUrl: string | null;
  status: string; // "processing" | "reviewed" | "failed"
  qualityScore: number | null;
  githubCommentId: number | null;
  errorMessage: string | null;
  reviewedAt: string | null;
  createdAt: string;
}

// ---------- /api/reviews/{prId} → ReviewResponse.java ----------

export interface FindingDto {
  id: string;
  filePath: string;
  lineStart: number | null;
  lineEnd: number | null;
  antiPattern: string;
  category: string;
  severity: string; // "critical" | "major" | "minor"
  confidence: number; // 0..1
  explanation: string | null;
  codeSnippet: string | null;
  /** Saved developer disposition; "open" until an action is recorded. */
  status: "open" | FindingDisposition;
}

export interface ReviewDetailResponse {
  id: string;
  githubPrNumber: number;
  title: string | null;
  authorGithub: string | null;
  status: string;
  qualityScore: number | null;
  createdAt: string;
  reviewedAt: string | null;
  errorMessage: string | null;
  headSha: string | null;
  githubPrUrl: string | null;
  repoId: string | null;
  repoFullName: string | null;
  repoOwnerLogin: string | null;
  repoOwnerAvatar: string | null;
  findings: FindingDto[];
}

// ---------- /api/scan/action ----------

/** Verb accepted by POST /api/scan/action (FindingActionRequest.java). */
export type FindingAction = "accept" | "dismiss" | "fix";

/** Value the API stores in `findings.status` for each action. */
export type FindingDisposition = "accepted" | "dismissed" | "fixed";

/** POST /api/scan/action → FindingActionResponse.java */
export interface FindingActionResponse {
  findingId: string;
  action: FindingAction;
  status: FindingDisposition;
}

// ---------- /api/metrics/* ----------

/** GET /api/metrics/quality-trend?repo=…&days=… → QualityTrendResponse.java */
export interface QualityTrendResponse {
  repoId: string;
  fullName: string;
  trend: QualityTrendPoint[];
  topAntiPattern: string | null;
}

export interface QualityTrendPoint {
  date: string; // ISO LocalDate ("YYYY-MM-DD")
  avgQuality: number | null;
  prsReviewed: number;
  criticalCount: number;
  majorCount: number;
  minorCount: number;
}

// ---------- /api/auth/api-keys ----------

/** GET /api/auth/api-keys → List<ApiKeyResponse> */
export interface ApiKeyResponse {
  id: string;
  label: string;
  prefix: string;
  createdAt: string;
  lastUsedAt: string | null;
}

/** POST /api/auth/api-keys → CreatedApiKeyResponse (returned once) */
export interface CreatedApiKeyResponse {
  id: string;
  label: string;
  prefix: string;
  key: string; // the only time the full plaintext is shown
  createdAt: string;
}

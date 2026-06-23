// =============================================================================
// API response types — mirror the Java records under
// apps/api/src/main/java/com/codelens/dto/{,response/}/*.java.
// Keep field names in sync with those records; Java's record component names
// serialize to JSON verbatim (Jackson defaults).
// =============================================================================

// ---------- /api/auth/* ----------

/** GET /api/auth/me → UserResponse.java */
export interface UserResponse {
  id: string;
  githubUsername: string;
  avatarUrl: string | null;
  apiKeyPrefix: string | null;
  createdAt: string; // ISO Instant
}

/** POST /api/auth/api-key/regenerate → Map.of("apiKey", …) */
export interface RegenerateApiKeyResponse {
  apiKey: string;
}

// ---------- /api/repos/* ----------

/** GET /api/repos → List<RepoResponse> (one RepoSummary per element) */
export interface RepoSummaryResponse {
  id: string;
  fullName: string;
  githubId: number;
  active: boolean;
  createdAt: string;
  totalPrsReviewed: number;
  lastReviewedAt: string | null;
  latestQualityScore: number | null;
}

/** POST /api/repos/connect → RepoResponse (X-Webhook-Secret header ignored here) */
export type RepoResponse = RepoSummaryResponse;

/** GET /api/repos/{id} → RepoResponse */
export type RepoDetailResponse = RepoResponse;

// ---------- /api/repos/{id}/prs ----------

/**
 * Spring Data `Page<T>` serializes as the following JSON shape.
 * Field names match `org.springframework.data.domain.Page`.
 */
export interface PaginatedPRResponse<T> {
  content: T[];
  pageable: {
    pageNumber: number;
    pageSize: number;
    sort: { empty: boolean; sorted: boolean; unsorted: boolean };
    offset: number;
    paged: boolean;
    unpaged: boolean;
  };
  totalElements: number;
  totalPages: number;
  last: boolean;
  size: number;
  number: number;
  sort: { empty: boolean; sorted: boolean; unsorted: boolean };
  first: boolean;
  numberOfElements: number;
  empty: boolean;
}

/** A row of the PR list (one entry of `Page<PullRequestEntity>`). */
export interface PullRequestSummary {
  id: string;
  githubPrNumber: number;
  title: string | null;
  authorGithub: string | null;
  headSha: string | null;
  githubPrUrl: string | null;
  status: string; // "pending" | "reviewed" | "failed"
  qualityScore: number | null;
  githubCommentId: number | null;
  errorMessage: string | null;
  reviewedAt: string | null;
  createdAt: string;
  // `repo` field omitted — we know it from the URL.
}

// ---------- /api/reviews/{prId} → ReviewResponse ----------

export interface FindingDto {
  id: string;
  filePath: string;
  lineStart: number | null;
  lineEnd: number | null;
  antiPattern: string;
  category: string;
  severity: "critical" | "major" | "minor" | string;
  confidence: number; // 0..1
  explanation: string | null;
  codeSnippet: string | null;
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
  prefix: string; // e.g. "cl_live_abc1"
  createdAt: string;
  lastUsedAt: string | null;
}

/** POST /api/auth/api-keys → CreatedApiKeyResponse (returned ONCE) */
export interface CreatedApiKeyResponse {
  id: string;
  label: string;
  prefix: string;
  key: string; // the only time the full plaintext is shown
  createdAt: string;
}

// ---------- convenience unions ----------

export type Severity = "critical" | "major" | "minor";
export type ReviewStatus = "pending" | "reviewed" | "failed";

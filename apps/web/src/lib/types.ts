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

/** Stored triage state of a finding (`findings.status`). */
export type FindingStatus =
  | "open"
  | "accepted"
  | "dismissed"
  | "fixed"
  | "false_positive";

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
  status: FindingStatus;
  /** Reason recorded with the latest triage action (required for dismiss / false_positive). */
  triageReason: string | null;
  triagedAt: string | null;
  /** The added line that triggered the finding, secret-redacted (≤200 chars). */
  evidence: string | null;
  /** How to fix it (≤300 chars). */
  remediation: string | null;
  /** When the finding may not apply (≤300 chars). */
  caveat: string | null;
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

// ---------- /api/reviews/{prId}/runs ----------

export interface ReviewRunPublication {
  status: string;
  commentUrl: string | null;
  publishedHeadSha: string | null;
}

/** One webhook delivery processed for a pull request (newest first, max 50). */
export interface ReviewRun {
  deliveryId: string;
  headSha: string | null;
  action: string | null;
  status: string;
  attemptCount: number;
  lastError: string | null;
  receivedAt: string;
  completedAt: string | null;
  publication: ReviewRunPublication | null;
}

// ---------- /api/scan/action ----------

/** Verb accepted by POST /api/scan/action. */
export type FindingAction =
  | "accept"
  | "dismiss"
  | "fix"
  | "false_positive"
  | "reopen";

/** POST /api/scan/action body. `reason` is required for dismiss and false_positive. */
export interface FindingActionRequest {
  findingId: string;
  action: FindingAction;
  reason?: string;
}

/** POST /api/scan/action → FindingActionResponse */
export interface FindingActionResponse {
  findingId: string;
  status: FindingStatus;
  reason: string | null;
  triagedAt: string | null;
  triagedBy: string | null;
}

// ---------- /api/repos/{id}/settings ----------

export type SeverityLevel = "minor" | "major" | "critical";
export type ReviewLanguage = "python" | "javascript" | "typescript" | "java";

export interface RepoSettings {
  /** Taxonomy IDs to report; empty means every rule. */
  enabledRules: string[];
  minSeverity: SeverityLevel;
  /** 0..1 */
  minConfidence: number;
  postComments: boolean;
  inlineComments: boolean;
  languages: ReviewLanguage[];
}

// ---------- /api/diagnostics ----------

export type HealthStatus = "up" | "degraded" | "down";

export interface ComponentHealth {
  status: HealthStatus;
  detail: string | null;
}

export interface MlWorkerHealth extends ComponentHealth {
  engine: string | null;
}

/** Counts are scoped to the caller's repositories. */
export interface DiagnosticsQueue {
  webhooksPending: number;
  webhooksRetrying: number;
  webhooksDeadLetter: number;
  oldestPendingSeconds: number | null;
  outboxPending: number;
  outboxFailed: number;
}

export interface DiagnosticsResponse {
  status: HealthStatus;
  checkedAt: string;
  components: {
    database: ComponentHealth;
    redis: ComponentHealth;
    mlWorker: MlWorkerHealth;
    github: ComponentHealth;
  };
  /** Null when the database could not be queried. */
  queue: DiagnosticsQueue | null;
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

export interface FindingSummaryByRule {
  antiPattern: string;
  total: number;
  open: number;
  accepted: number;
  dismissed: number;
  fixed: number;
  falsePositive: number;
}

export interface FindingSummaryByEngine {
  engine: string;
  total: number;
}

/** GET /api/metrics/finding-summary?repo=…&days=… */
export interface FindingSummaryResponse {
  repoId: string;
  days: number;
  byRule: FindingSummaryByRule[];
  byEngine: FindingSummaryByEngine[];
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

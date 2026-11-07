// Mirrors the @Size(max = 200_000) limit on ScanFileRequest.content in the API.
export const MAX_CONTENT_CHARS = 200_000;
export const DEFAULT_TIMEOUT_MS = 15_000;

export interface ScanFileRequest {
  content: string;
  language: string;
  filePath: string;
}

// Maps VS Code language ids to the ML worker's file-mode `language` field,
// which accepts only python/javascript/java/unknown; TypeScript is sent as javascript.
const API_LANGUAGE_BY_VSCODE_ID = new Map<string, string>([
  ["python", "python"],
  ["javascript", "javascript"],
  ["javascriptreact", "javascript"],
  ["typescript", "javascript"],
  ["typescriptreact", "javascript"],
  ["java", "java"],
]);

export interface FindingDTO {
  filePath?: string | null;
  lineStart: number | null;
  lineEnd: number | null;
  antiPattern: string;
  category?: string | null;
  severity: string;
  confidence: number;
  explanation: string;
}

export interface ScanFileResponse {
  findings: FindingDTO[];
  qualityScore: number | null;
  language: string;
}

export interface DiagnosticData {
  startLine: number;
  endLine: number;
  severity: "error" | "warning" | "information";
  message: string;
  code: string;
}

export class ReviewerClientError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "ReviewerClientError";
  }
}

interface PostScanOptions {
  apiUrl: string;
  apiKey: string;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
}

/** Returns the API language for a VS Code language id, or undefined if unsupported. */
export function apiLanguage(languageId: string): string | undefined {
  return API_LANGUAGE_BY_VSCODE_ID.get(languageId);
}

export function exceedsContentLimit(content: string): boolean {
  return content.length > MAX_CONTENT_CHARS;
}

export function dedupeFindings(findings: FindingDTO[]): FindingDTO[] {
  const seen = new Set<string>();
  return findings.filter((finding) => {
    const key = [
      finding.antiPattern,
      finding.filePath ?? "",
      finding.lineStart ?? "",
      finding.lineEnd ?? "",
      finding.explanation ?? "",
    ].join("\u0000");
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function mapFindingToDiagnosticData(finding: FindingDTO): DiagnosticData {
  const startLine = Math.max(0, (finding.lineStart ?? 1) - 1);
  const endLine = Math.max(
    startLine,
    (finding.lineEnd ?? finding.lineStart ?? 1) - 1,
  );
  const confidence =
    typeof finding.confidence === "number" && Number.isFinite(finding.confidence)
      ? Math.min(100, Math.max(0, Math.round(finding.confidence * 100)))
      : null;
  const confidenceText =
    confidence === null ? "" : ` (${confidence}% confidence)`;
  const explanation = finding.explanation?.trim() || "No explanation provided.";

  return {
    startLine,
    endLine,
    severity: mapSeverity(finding.severity),
    message: `${prettify(finding.antiPattern)}${confidenceText}: ${explanation}`,
    code: finding.antiPattern,
  };
}

export async function postScan(
  payload: ScanFileRequest,
  options: PostScanOptions,
): Promise<ScanFileResponse> {
  const apiUrl = options.apiUrl.replace(/\/+$/, "");
  let endpoint: URL;
  try {
    endpoint = new URL(apiUrl);
  } catch {
    throw new ReviewerClientError("Invalid API URL.");
  }
  const localHttp = endpoint.protocol === "http:" &&
    ["localhost", "127.0.0.1", "[::1]"].includes(endpoint.hostname);
  if (
    (endpoint.protocol !== "https:" && !localHttp) ||
    endpoint.username || endpoint.password || endpoint.search || endpoint.hash
  ) {
    throw new ReviewerClientError("API URL must use HTTPS (HTTP is allowed only for loopback) and contain no credentials, query or fragment.");
  }
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const fetchImpl = options.fetchImpl ?? fetch;
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  try {
    let response: Response;
    try {
      response = await fetchImpl(`${apiUrl}/api/scan/file`, {
        method: "POST",
        redirect: "error",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          Authorization: `Bearer ${options.apiKey}`,
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });
    } catch (error) {
      if (timedOut || (error instanceof Error && error.name === "AbortError")) {
        throw new ReviewerClientError(`Request timed out after ${timeoutMs} ms.`);
      }
      throw error;
    }

    if (!response.ok) {
      throw new ReviewerClientError(
        `API returned HTTP ${response.status}.`,
        response.status,
      );
    }

    const contentType = response.headers.get("content-type") ?? "";
    if (!contentType.toLowerCase().includes("application/json")) {
      throw new ReviewerClientError(
        "Expected a JSON API response.",
        response.status,
      );
    }

    let parsed: unknown;
    try {
      parsed = await response.json();
    } catch {
      throw new ReviewerClientError("API returned invalid JSON.", response.status);
    }
    if (!isScanFileResponse(parsed)) {
      throw new ReviewerClientError(
        "Invalid API response shape: expected { findings: [...] }.",
        response.status,
      );
    }
    return { ...parsed, findings: dedupeFindings(parsed.findings) };
  } finally {
    clearTimeout(timer);
  }
}

function isScanFileResponse(value: unknown): value is ScanFileResponse {
  if (!value || typeof value !== "object") return false;
  const findings = (value as { findings?: unknown }).findings;
  return Array.isArray(findings);
}

function mapSeverity(severity: string): DiagnosticData["severity"] {
  switch (severity.toUpperCase()) {
    case "CRITICAL":
    case "MAJOR":
      return "error";
    case "MINOR":
      return "warning";
    default:
      return "information";
  }
}

function prettify(id: string): string {
  return (id || "Anti-pattern")
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map((word) => word[0].toUpperCase() + word.slice(1))
    .join(" ");
}


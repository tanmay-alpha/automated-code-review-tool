export const MAX_FILE_BYTES = 1_048_576;
export const DEFAULT_TIMEOUT_MS = 15_000;

export interface ScanFileRequest {
  content: string;
  language: string;
  filePath: string;
}

export interface FindingDTO {
  id?: string;
  antiPattern: string;
  severity: "CRITICAL" | "MAJOR" | "MINOR" | string;
  confidence: number;
  filePath?: string;
  lineStart: number | null;
  lineEnd: number | null;
  explanation: string;
  codeSnippet?: string;
  suggestion?: string;
}

export interface ScanFileResponse {
  filePath: string;
  language: string;
  findings: FindingDTO[];
  qualityScore?: number;
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

export function utf8ByteLength(content: string): number {
  return new TextEncoder().encode(content).byteLength;
}

export function exceedsFileSizeLimit(content: string): boolean {
  return utf8ByteLength(content) > MAX_FILE_BYTES;
}

export function dedupeFindings(findings: FindingDTO[]): FindingDTO[] {
  const seen = new Set<string>();
  return findings.filter((finding) => {
    const key = finding.id
      ? `id:${finding.id}`
      : [
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
    code: finding.id ?? finding.antiPattern,
  };
}

export async function postScan(
  payload: ScanFileRequest,
  options: PostScanOptions,
): Promise<ScanFileResponse> {
  const apiUrl = options.apiUrl.replace(/\/+$/, "");
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
      const body = await safeReadText(response);
      throw new ReviewerClientError(
        `API returned ${response.status} ${response.statusText}: ${body}`,
        response.status,
      );
    }

    const contentType = response.headers.get("content-type") ?? "";
    if (!contentType.toLowerCase().includes("application/json")) {
      const body = await safeReadText(response);
      throw new ReviewerClientError(
        `Expected JSON but got ${contentType.split(";")[0].trim() || "unknown content type"}: ${body}`,
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

async function safeReadText(response: Response): Promise<string> {
  try {
    return (await response.text()).slice(0, 200) || "(empty body)";
  } catch {
    return "(unreadable body)";
  }
}

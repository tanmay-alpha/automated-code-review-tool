/**
 * automated-code-review-tool file reviewer.
 *
 *  1. Reads the current automated-code-review-tool API key + URL from VS Code settings
 *     (see `config.ts`).
 *  2. POSTs the file's text to `{apiUrl}/api/scan/file` with a
 *     `Authorization: Bearer <apiKey>` header.
 *  3. Maps the returned findings onto a `vscode.DiagnosticCollection`
 *     so they show up as squigglies in the editor.
 *  4. Surfaces a status-bar message: "automated-code-review-tool: N issues found" or
 *     "automated-code-review-tool: ✅ Clean".
 *
 * The reviewer is intentionally tolerant: every failure path surfaces
 * a user-visible message (status bar or error toast) rather than
 * throwing, so a flaky network or missing key never breaks the editor.
 */
import {
  Diagnostic,
  DiagnosticCollection,
  DiagnosticSeverity,
  Range,
  TextDocument,
  Uri,
  window,
} from "vscode";
import { getApiKey, getApiUrl, isEnabled } from "./config";

// Mirrors the Spring Boot ScanFileRequest DTO.
interface ScanFileRequest {
  content: string;
  language: string;
  filePath: string;
}

// Mirrors the Spring Boot FindingDTO.
interface FindingDTO {
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

// Mirrors the Spring Boot ScanFileResponse DTO.
interface ScanFileResponse {
  filePath: string;
  language: string;
  findings: FindingDTO[];
  qualityScore?: number;
}

/**
 * The status-bar item we update after every scan. Lazily created
 * so tests / library consumers don't pay for it.
 */
let statusBarItem = null as ReturnType<typeof window.createStatusBarItem> | null;

function statusBar(): NonNullable<typeof statusBarItem> {
  if (!statusBarItem) {
    statusBarItem = window.createStatusBarItem("automated-code-review-tool.status", 1);
    statusBarItem.name = "automated-code-review-tool";
    statusBarItem.command = "automated-code-review-tool.scanFile";
  }
  return statusBarItem;
}

/**
 * Entry point. Called by the extension on every file save (filtered
 * by language) and from the manual scan command.
 *
 * Errors are caught and surfaced to the user; they never propagate
 * back into VS Code's event loop.
 */
export async function scanFile(
  doc: TextDocument,
  collection: DiagnosticCollection,
): Promise<void> {
  if (!isEnabled()) {
    statusBar().text = "automated-code-review-tool: disabled";
    statusBar().show();
    return;
  }

  const apiKey = getApiKey();
  if (!apiKey) {
    const msg =
      "automated-code-review-tool: Set your API key in settings (automated-code-review-tool.apiKey)";
    statusBar().text = "$(error) automated-code-review-tool: no API key";
    statusBar().tooltip = msg;
    statusBar().show();
    void window.showErrorMessage(msg);
    collection.set(doc.uri, []);
    return;
  }

  const content = doc.getText();
  if (content.length > 1_048_576) {
    statusBar().text = "$(warning) automated-code-review-tool: file too large (>1MB)";
    statusBar().tooltip = "File exceeds 1MB limit for automatic scanning.";
    statusBar().show();
    return;
  }

  statusBar().text = "$(sync~spin) automated-code-review-tool: scanning…";
  statusBar().show();

  try {
    const response = await postScan({
      content,
      language: doc.languageId,
      filePath: doc.fileName,
    }, getApiUrl());
    applyDiagnostics(doc.uri, response, collection);
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    statusBar().text = `$(error) automated-code-review-tool: ${shortError(message)}`;
    statusBar().tooltip = message;
    statusBar().show();
    void window.showErrorMessage(`automated-code-review-tool scan failed: ${message}`);
    // Don't leave stale diagnostics on failure.
    collection.set(doc.uri, []);
  }
}

/**
 * Sends the current document to the automated-code-review-tool API for scanning.
 *
 * Wraps `fetch` with a 15-second timeout so a slow or hung API never
 * stalls the editor's event loop. Also validates the response is JSON
 * and warns the user if they're pointing at an HTTP URL (non-HTTPS
 * API calls leak credentials in plaintext over the network).
 */
async function postScan(
  payload: ScanFileRequest,
  apiUrl: string,
): Promise<ScanFileResponse> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15_000);

  try {
    try {
      const url = new URL(apiUrl);
      if (url.protocol === "http:" && !["localhost", "127.0.0.1"].includes(url.hostname)) {
        void window.showWarningMessage(
          "automated-code-review-tool: API key is being sent over unencrypted HTTP.",
        );
      }
    } catch {
      // invalid URL will fail fetch below
    }

    const res = await fetch(`${apiUrl}/api/scan/file`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${getApiKey()}`,
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!res.ok) {
      const body = await safeReadText(res);
      throw new Error(
        `API returned ${res.status} ${res.statusText}: ${body}`,
      );
    }

    // Guard against HTML error pages (e.g. 404 from a dev proxy).
    const ct = res.headers.get("content-type") ?? "";
    if (!ct.includes("application/json")) {
      const body = await safeReadText(res);
      throw new Error(
        `Expected JSON but got ${ct.split(";")[0].trim()}: ${body.slice(0, 200)}`,
      );
    }

    const parsed = await res.json();
    if (!parsed || !Array.isArray(parsed.findings)) {
      throw new Error("Invalid API response shape: expected { findings: [...] }");
    }
    return parsed as ScanFileResponse;
  } finally {
    clearTimeout(timer);
  }
}

/** Clear diagnostics for every URI the collection knows about. */
export function clearAll(collection: DiagnosticCollection): void {
  collection.clear();
  statusBar().hide();
}

// --------------------------------------------------------------------
// Internals
// --------------------------------------------------------------------

function applyDiagnostics(
  uri: Uri,
  response: ScanFileResponse,
  collection: DiagnosticCollection,
): void {
  const diags: Diagnostic[] = response.findings.map(toDiagnostic);
  collection.set(uri, diags);

  if (diags.length === 0) {
    statusBar().text = "automated-code-review-tool: ✅ Clean";
    statusBar().tooltip = "No issues found in this file.";
  } else {
    statusBar().text = `automated-code-review-tool: ${diags.length} issue${
      diags.length === 1 ? "" : "s"
    } found`;
    statusBar().tooltip =
      `${diags.length} automated-code-review-tool finding${diags.length === 1 ? "" : "s"}. ` +
      `Click to re-scan.`;
  }
  statusBar().show();
}

function toDiagnostic(f: FindingDTO): Diagnostic {
  // lineStart/lineEnd come from the API as 1-based line numbers.
  // VS Code ranges must have start < end; a zero-width range (start == end)
  // is valid but renders as a single underline marker rather than a
  // squiggly, which is what we want when the API only knows a line.
  const startLine = Math.max(0, (f.lineStart ?? 1) - 1);
  const endLine = Math.max(startLine, (f.lineEnd ?? f.lineStart ?? 1) - 1);
  // If we don't have a usable start line, place a tiny range at the very
  // start of the file rather than a zero-width Range(0,0,0,0).
  const range =
    f.lineStart == null
      ? new Range(0, 0, 0, 1)
      : new Range(startLine, 0, endLine, Number.MAX_SAFE_INTEGER);

  const severity = mapSeverity(f.severity);

  // The diagnostic message is rendered in the editor's hover popup
  // and the Problems panel. We pack anti-pattern name + confidence +
  // explanation into a stable, readable format.
  const pctVal = typeof f.confidence === "number" ? Math.min(100, Math.max(0, Math.round(f.confidence * 100))) : null;
  const pct = pctVal !== null ? ` (${pctVal}% confidence)` : "";
  const message = `${prettify(f.antiPattern)}${pct}: ${f.explanation}`;

  const d = new Diagnostic(range, message, severity);
  d.source = "automated-code-review-tool";
  d.code = f.id ?? f.antiPattern;

  return d;
}

function mapSeverity(s: string): DiagnosticSeverity {
  switch (s.toUpperCase()) {
    case "CRITICAL":
    case "MAJOR":
      return DiagnosticSeverity.Error;
    case "MINOR":
      return DiagnosticSeverity.Warning;
    default:
      return DiagnosticSeverity.Information;
  }
}

/**
 * BEST_GUIDE_LINE_SUBLINES → "Best Guide Line Sublines".
 * We keep the underscore-to-space conversion simple; rich
 * formatting is shown in the web dashboard.
 */
function prettify(id: string): string {
  return id
    .toLowerCase()
    .split("_")
    .map((w) => (w ? w[0].toUpperCase() + w.slice(1) : ""))
    .filter(Boolean)
    .join(" ");
}

async function safeReadText(r: Response): Promise<string> {
  try {
    return (await r.text()).slice(0, 200);
  } catch {
    return "(no body)";
  }
}

function shortError(msg: string): string {
  if (msg.length <= 60) return msg;
  return msg.slice(0, 57) + "…";
}

// Re-export the languages module helper for the workspace command.
export { languages };

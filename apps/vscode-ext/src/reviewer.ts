/**
 * CodeLens file reviewer.
 *
 *  1. Reads the current CodeLens API key + URL from VS Code settings
 *     (see `config.ts`).
 *  2. POSTs the file's text to `{apiUrl}/api/scan/file` with a
 *     `Authorization: Bearer <apiKey>` header.
 *  3. Maps the returned findings onto a `vscode.DiagnosticCollection`
 *     so they show up as squigglies in the editor.
 *  4. Surfaces a status-bar message: "CodeLens: N issues found" or
 *     "CodeLens: ✅ Clean".
 *
 * The reviewer is intentionally tolerant: every failure path surfaces
 * a user-visible message (status bar or error toast) rather than
 * throwing, so a flaky network or missing key never breaks the editor.
 */
import {
  Diagnostic,
  DiagnosticCollection,
  DiagnosticSeverity,
  TextDocument,
  Uri,
  languages,
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
    statusBarItem = window.createStatusBarItem("codelens.status", 1);
    statusBarItem.name = "CodeLens";
    statusBarItem.command = "codelens.scanFile";
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
    statusBar().text = "CodeLens: disabled";
    statusBar().show();
    return;
  }

  const apiKey = getApiKey();
  if (!apiKey) {
    const msg =
      "CodeLens: Set your API key in settings (codelens.apiKey)";
    statusBar().text = "$(error) CodeLens: no API key";
    statusBar().tooltip = msg;
    statusBar().show();
    void window.showErrorMessage(msg);
    collection.set(doc.uri, []);
    return;
  }

  statusBar().text = "$(sync~spin) CodeLens: scanning…";
  statusBar().show();

  try {
    const response = await fetch(buildUrl(), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        content: doc.getText(),
        language: doc.languageId,
        filePath: doc.fileName,
      } satisfies ScanFileRequest),
    });

    if (!response.ok) {
      const body = await safeReadText(response);
      throw new Error(
        `API returned ${response.status} ${response.statusText}: ${body}`,
      );
    }

    const json = (await response.json()) as ScanFileResponse;
    applyDiagnostics(doc.uri, json, collection);
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    statusBar().text = `$(error) CodeLens: ${shortError(message)}`;
    statusBar().tooltip = message;
    statusBar().show();
    void window.showErrorMessage(`CodeLens scan failed: ${message}`);
    // Don't leave stale diagnostics on failure.
    collection.set(doc.uri, []);
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

function buildUrl(): string {
  return `${getApiUrl()}/api/scan/file`;
}

function applyDiagnostics(
  uri: Uri,
  response: ScanFileResponse,
  collection: DiagnosticCollection,
): void {
  const diags: Diagnostic[] = response.findings.map(toDiagnostic);
  collection.set(uri, diags);

  if (diags.length === 0) {
    statusBar().text = "CodeLens: ✅ Clean";
    statusBar().tooltip = "No issues found in this file.";
  } else {
    statusBar().text = `CodeLens: ${diags.length} issue${
      diags.length === 1 ? "" : "s"
    } found`;
    statusBar().tooltip =
      `${diags.length} CodeLens finding${diags.length === 1 ? "" : "s"}. ` +
      `Click to re-scan.`;
  }
  statusBar().show();
}

function toDiagnostic(f: FindingDTO): Diagnostic {
  // lineStart/lineEnd come from the API as 1-based line numbers.
  // If either is missing, highlight the whole file (line 0, col 0 to
  // line 0 col 0 is invalid in VS Code; clamp to a 1-char range at
  // position 0).
  const startLine = Math.max(0, (f.lineStart ?? 1) - 1);
  const endLine = Math.max(startLine, (f.lineEnd ?? f.lineStart ?? 1) - 1);
  const range =
    f.lineStart == null
      ? new (require("vscode").Range)(0, 0, 0, 0)
      : new (require("vscode").Range)(startLine, 0, endLine, 999);

  const severity = mapSeverity(f.severity);

  // The diagnostic message is rendered in the editor's hover popup
  // and the Problems panel. We pack anti-pattern name + confidence +
  // explanation into a stable, readable format.
  const pct = typeof f.confidence === "number"
    ? ` (${Math.round(f.confidence * 100)}% confidence)`
    : "";
  const message = `${prettify(f.antiPattern)}${pct}: ${f.explanation}`;

  const d = new Diagnostic(range, message, severity);
  d.source = "CodeLens";
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

import {
  Diagnostic,
  DiagnosticCollection,
  DiagnosticSeverity,
  Range,
  TextDocument,
  Uri,
  languages,
  window,
} from "vscode";
import { getApiKey, getApiUrl, isEnabled } from "./config";
import {
  exceedsFileSizeLimit,
  mapFindingToDiagnosticData,
  postScan,
  ScanFileResponse,
} from "./reviewerClient";

let statusBarItem = null as ReturnType<typeof window.createStatusBarItem> | null;

function statusBar(): NonNullable<typeof statusBarItem> {
  if (!statusBarItem) {
    statusBarItem = window.createStatusBarItem("automated-code-review-tool.status", 1);
    statusBarItem.name = "automated-code-review-tool";
    statusBarItem.command = "automated-code-review-tool.scanFile";
  }
  return statusBarItem;
}

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
    const message =
      "automated-code-review-tool: set automated-code-review-tool.apiKey in Settings.";
    statusBar().text = "$(error) automated-code-review-tool: no API key";
    statusBar().tooltip = message;
    statusBar().show();
    void window.showErrorMessage(message);
    collection.set(doc.uri, []);
    return;
  }

  const content = doc.getText();
  if (exceedsFileSizeLimit(content)) {
    statusBar().text = "$(warning) automated-code-review-tool: file too large (>1 MB)";
    statusBar().tooltip = "The UTF-8 file size exceeds the 1 MB scan limit.";
    statusBar().show();
    collection.set(doc.uri, []);
    return;
  }

  const apiUrl = getApiUrl();
  warnForInsecureRemoteUrl(apiUrl);
  statusBar().text = "$(sync~spin) automated-code-review-tool: scanning…";
  statusBar().show();

  try {
    const response = await postScan(
      {
        content,
        language: doc.languageId,
        filePath: doc.fileName,
      },
      { apiUrl, apiKey },
    );
    applyDiagnostics(doc.uri, response, collection);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    statusBar().text = `$(error) automated-code-review-tool: ${shortError(message)}`;
    statusBar().tooltip = message;
    statusBar().show();
    void window.showErrorMessage(`automated-code-review-tool scan failed: ${message}`);
    collection.set(doc.uri, []);
  }
}

export function clearAll(collection: DiagnosticCollection): void {
  collection.clear();
  statusBar().hide();
}

function applyDiagnostics(
  uri: Uri,
  response: ScanFileResponse,
  collection: DiagnosticCollection,
): void {
  const diagnostics = response.findings.map((finding) => {
    const mapped = mapFindingToDiagnosticData(finding);
    const range =
      finding.lineStart == null
        ? new Range(0, 0, 0, 1)
        : new Range(
            mapped.startLine,
            0,
            mapped.endLine,
            Number.MAX_SAFE_INTEGER,
          );
    const severity =
      mapped.severity === "error"
        ? DiagnosticSeverity.Error
        : mapped.severity === "warning"
          ? DiagnosticSeverity.Warning
          : DiagnosticSeverity.Information;
    const diagnostic = new Diagnostic(range, mapped.message, severity);
    diagnostic.source = "automated-code-review-tool";
    diagnostic.code = mapped.code;
    return diagnostic;
  });

  collection.set(uri, diagnostics);
  if (diagnostics.length === 0) {
    statusBar().text = "automated-code-review-tool: Clean";
    statusBar().tooltip = "No issues found in this file.";
  } else {
    statusBar().text = `automated-code-review-tool: ${diagnostics.length} issue${
      diagnostics.length === 1 ? "" : "s"
    } found`;
    statusBar().tooltip = "Click to scan again.";
  }
  statusBar().show();
}

function warnForInsecureRemoteUrl(apiUrl: string): void {
  try {
    const url = new URL(apiUrl);
    if (
      url.protocol === "http:" &&
      !["localhost", "127.0.0.1", "::1"].includes(url.hostname)
    ) {
      void window.showWarningMessage(
        "automated-code-review-tool: the API key is being sent over unencrypted HTTP.",
      );
    }
  } catch {
    // The client reports malformed URLs as a request error.
  }
}

function shortError(message: string): string {
  return message.length <= 60 ? message : `${message.slice(0, 57)}…`;
}

export { languages };

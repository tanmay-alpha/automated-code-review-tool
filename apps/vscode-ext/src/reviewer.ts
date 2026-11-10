import {
  commands,
  Diagnostic,
  DiagnosticCollection,
  DiagnosticSeverity,
  Disposable,
  Range,
  SecretStorage,
  StatusBarAlignment,
  StatusBarItem,
  TextDocument,
  Uri,
  window,
  workspace,
} from "vscode";
import { getApiKey, getApiUrl, isEnabled } from "./config";
import {
  apiLanguage,
  exceedsContentLimit,
  mapFindingToDiagnosticData,
  postScan,
  ReviewerClientError,
  ScanFileResponse,
  scanFilePath,
} from "./reviewerClient";
import { ScanSequencer } from "./scanSequencer";

let statusBarItem: StatusBarItem | null = null;

/**
 * Creates the status bar item. The returned disposable must be registered in
 * `context.subscriptions` so the item is removed when the extension deactivates.
 */
export function createStatusBar(): Disposable {
  const item = window.createStatusBarItem(
    "automated-code-review-tool.status",
    StatusBarAlignment.Left,
  );
  item.name = "automated-code-review-tool";
  item.command = "automated-code-review-tool.scanFile";
  statusBarItem = item;
  return new Disposable(() => {
    item.dispose();
    if (statusBarItem === item) statusBarItem = null;
  });
}

function statusBar(): StatusBarItem {
  if (!statusBarItem) {
    throw new Error("automated-code-review-tool status bar used before activation.");
  }
  return statusBarItem;
}

export type ScanOutcome =
  | { kind: "scanned" }
  | { kind: "skipped"; reason: string }
  | { kind: "stale" }
  | { kind: "failed"; message: string; status?: number };

export interface ScanFileOptions {
  /** Show error popups for failures (default true). */
  notify?: boolean;
  /** API key already resolved by the caller; skips the secret-storage lookup. */
  apiKey?: string;
}

const sequencer = new ScanSequencer();

/** Shows the single "no API key" error with a Set API Key action. */
export function promptForApiKey(): void {
  const message =
    "automated-code-review-tool: no API key. Run 'automated-code-review-tool: Set API Key'.";
  statusBar().text = "$(error) automated-code-review-tool: no API key";
  statusBar().tooltip = message;
  statusBar().show();
  const setKey = "Set API Key";
  void window.showErrorMessage(message, setKey).then((choice) => {
    if (choice === setKey) {
      void commands.executeCommand("automated-code-review-tool.setApiKey");
    }
  });
}

export async function scanFile(
  doc: TextDocument,
  collection: DiagnosticCollection,
  secrets: SecretStorage,
  options: ScanFileOptions = {},
): Promise<ScanOutcome> {
  const notify = options.notify ?? true;
  if (!isEnabled()) {
    statusBar().text = "automated-code-review-tool: disabled";
    statusBar().show();
    return { kind: "skipped", reason: "disabled" };
  }

  const apiKey = options.apiKey ?? (await getApiKey(secrets)).key;
  if (!apiKey) {
    collection.set(doc.uri, []);
    if (notify) promptForApiKey();
    return { kind: "failed", message: "no API key" };
  }

  const language = apiLanguage(doc.languageId);
  if (!language) {
    statusBar().text = `automated-code-review-tool: ${doc.languageId} not supported`;
    statusBar().tooltip = "Supported: Python, JavaScript, TypeScript, Java.";
    statusBar().show();
    return { kind: "skipped", reason: "unsupported language" };
  }

  const content = doc.getText();
  if (exceedsContentLimit(content)) {
    statusBar().text = "$(warning) automated-code-review-tool: file too large";
    statusBar().tooltip = "The file exceeds the API's 200,000-character scan limit.";
    statusBar().show();
    collection.set(doc.uri, []);
    return { kind: "skipped", reason: "file too large" };
  }

  const apiUrl = getApiUrl();
  const key = doc.uri.toString();
  const token = sequencer.begin(key);
  statusBar().text = "$(sync~spin) automated-code-review-tool: scanning…";
  statusBar().show();

  try {
    const response = await postScan(
      {
        content,
        language,
        filePath: scanFilePath(workspace.asRelativePath(doc.uri, false)),
      },
      { apiUrl, apiKey },
    );
    if (!sequencer.isLatest(key, token)) return { kind: "stale" };
    applyDiagnostics(doc.uri, response, collection);
    return { kind: "scanned" };
  } catch (error) {
    if (!sequencer.isLatest(key, token)) return { kind: "stale" };
    const message = error instanceof Error ? error.message : String(error);
    const status = error instanceof ReviewerClientError ? error.status : undefined;
    statusBar().text = `$(error) automated-code-review-tool: ${shortError(message)}`;
    statusBar().tooltip = message;
    statusBar().show();
    if (notify) {
      void window.showErrorMessage(`automated-code-review-tool scan failed: ${message}`);
    }
    collection.set(doc.uri, []);
    return { kind: "failed", message, status };
  } finally {
    sequencer.finish(key, token);
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


function shortError(message: string): string {
  return message.length <= 60 ? message : `${message.slice(0, 57)}…`;
}

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
} from "vscode";
import { getApiKey, getApiUrl, isEnabled } from "./config";
import {
  apiLanguage,
  exceedsContentLimit,
  mapFindingToDiagnosticData,
  postScan,
  ScanFileResponse,
} from "./reviewerClient";

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

export async function scanFile(
  doc: TextDocument,
  collection: DiagnosticCollection,
  secrets: SecretStorage,
): Promise<void> {
  if (!isEnabled()) {
    statusBar().text = "automated-code-review-tool: disabled";
    statusBar().show();
    return;
  }

  const { key: apiKey } = await getApiKey(secrets);
  if (!apiKey) {
    const message =
      "automated-code-review-tool: no API key. Run 'automated-code-review-tool: Set API Key'.";
    statusBar().text = "$(error) automated-code-review-tool: no API key";
    statusBar().tooltip = message;
    statusBar().show();
    collection.set(doc.uri, []);
    const setKey = "Set API Key";
    void window.showErrorMessage(message, setKey).then((choice) => {
      if (choice === setKey) {
        void commands.executeCommand("automated-code-review-tool.setApiKey");
      }
    });
    return;
  }

  const language = apiLanguage(doc.languageId);
  if (!language) {
    statusBar().text = `automated-code-review-tool: ${doc.languageId} not supported`;
    statusBar().tooltip = "Supported: Python, JavaScript, TypeScript, Java.";
    statusBar().show();
    return;
  }

  const content = doc.getText();
  if (exceedsContentLimit(content)) {
    statusBar().text = "$(warning) automated-code-review-tool: file too large";
    statusBar().tooltip = "The file exceeds the API's 200,000-character scan limit.";
    statusBar().show();
    collection.set(doc.uri, []);
    return;
  }

  const apiUrl = getApiUrl();
  statusBar().text = "$(sync~spin) automated-code-review-tool: scanning…";
  statusBar().show();

  try {
    const response = await postScan(
      {
        content,
        language,
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


function shortError(message: string): string {
  return message.length <= 60 ? message : `${message.slice(0, 57)}…`;
}

/**
 * automated-code-review-tool — VS Code extension entry point.
 *
 * Loaded by VS Code when one of the activationEvents in
 * package.json fires (any of the supported languages is opened).
 *
 * Responsibilities:
 *   - Create the `automated-code-review-tool` DiagnosticCollection so findings can
 *     be displayed as inline squigglies in the editor.
 *   - Auto-scan any saved file whose language is supported.
 *   - Provide scan-file, scan-workspace and clear commands.
 *   - Store the API key in SecretStorage (set/clear commands, one-time
 *     migration offer for the deprecated plain-text setting).
 */
import * as vscode from "vscode";
import { clearAll, createStatusBar, promptForApiKey, scanFile } from "./reviewer";
import {
  clearLegacyApiKeySetting,
  deleteApiKey,
  getApiKey,
  offerApiKeyMigration,
  scanOnSave,
  storeApiKey,
} from "./config";
import { apiLanguage } from "./reviewerClient";

/**
 * Called by VS Code when the extension activates. The context
 * holds the disposables so they can be cleaned up on deactivation.
 */
export function activate(context: vscode.ExtensionContext): void {
  const secrets = context.secrets;

  // Single shared collection — squigglies across the editor surface.
  const diagnosticCollection = vscode.languages.createDiagnosticCollection(
    "automated-code-review-tool",
  );
  context.subscriptions.push(diagnosticCollection);
  context.subscriptions.push(createStatusBar());

  // Auto-scan on save (only supported languages).
  context.subscriptions.push(
    vscode.workspace.onDidSaveTextDocument(async (doc) => {
      if (!apiLanguage(doc.languageId)) return;
      if (!scanOnSave()) return;
      await scanFile(doc, diagnosticCollection, secrets);
    }),
  );

  // Manual scan command — runs even if the auto-scan is off.
  context.subscriptions.push(
    vscode.commands.registerCommand("automated-code-review-tool.scanFile", async () => {
      const editor = vscode.window.activeTextEditor;
      if (!editor) {
        void vscode.window.showInformationMessage(
          "automated-code-review-tool: open a file to scan.",
        );
        return;
      }
      await scanFile(editor.document, diagnosticCollection, secrets);
    }),
  );

  // Workspace scan command advertised in package.json.
  context.subscriptions.push(
    vscode.commands.registerCommand("automated-code-review-tool.scanWorkspace", async () => {
      const { key: apiKey } = await getApiKey(secrets);
      if (!apiKey) {
        promptForApiKey();
        return;
      }
      const files = await vscode.workspace.findFiles(
        "**/*.{py,js,jsx,ts,tsx,java}",
        "**/{.git,node_modules,out,dist,build}/**",
      );
      let scanned = 0;
      let failed = 0;
      let skipped = 0;
      let stopReason: string | null = null;
      let cancelled = false;
      await vscode.window.withProgress(
        {
          location: vscode.ProgressLocation.Notification,
          title: "automated-code-review-tool: scanning workspace",
          cancellable: true,
        },
        async (progress, token) => {
          for (let i = 0; i < files.length; i++) {
            if (token.isCancellationRequested) {
              cancelled = true;
              break;
            }
            progress.report({
              message: `${i + 1}/${files.length}`,
              increment: 100 / files.length,
            });
            let doc: vscode.TextDocument;
            try {
              doc = await vscode.workspace.openTextDocument(files[i]);
            } catch {
              failed++;
              continue;
            }
            const outcome = await scanFile(doc, diagnosticCollection, secrets, {
              notify: false,
              apiKey,
            });
            if (outcome.kind === "failed") {
              failed++;
              if (outcome.status === 401 || outcome.status === 403 || outcome.status === 429) {
                stopReason = outcome.message;
                break;
              }
            } else if (outcome.kind === "skipped") {
              skipped++;
            } else {
              scanned++;
            }
          }
        },
      );
      const summary = `scanned ${scanned}, failed ${failed}, skipped ${skipped} of ${files.length} workspace file${files.length === 1 ? "" : "s"}.`;
      if (stopReason) {
        void vscode.window.showErrorMessage(
          `automated-code-review-tool: workspace scan stopped: ${stopReason} (${summary})`,
        );
      } else {
        void vscode.window.showInformationMessage(
          `automated-code-review-tool: ${cancelled ? "workspace scan cancelled; " : ""}${summary}`,
        );
      }
    }),
  );

  // Clear command — useful when reviewing fixes.
  context.subscriptions.push(
    vscode.commands.registerCommand("automated-code-review-tool.clear", () => {
      clearAll(diagnosticCollection);
    }),
  );

  // Store the API key in SecretStorage; also drop any plain-text copy.
  context.subscriptions.push(
    vscode.commands.registerCommand("automated-code-review-tool.setApiKey", async () => {
      const key = await vscode.window.showInputBox({
        title: "automated-code-review-tool API key",
        prompt: "Paste the API key from the dashboard (Settings → API Key).",
        password: true,
        ignoreFocusOut: true,
        validateInput: (value) => (value.trim() ? undefined : "API key cannot be empty."),
      });
      if (key === undefined) return;
      await storeApiKey(secrets, key);
      await clearLegacyApiKeySetting();
      void vscode.window.showInformationMessage(
        "automated-code-review-tool: API key saved to secret storage.",
      );
    }),
  );

  // Remove the API key from SecretStorage and the deprecated setting.
  context.subscriptions.push(
    vscode.commands.registerCommand("automated-code-review-tool.clearApiKey", async () => {
      await deleteApiKey(secrets);
      await clearLegacyApiKeySetting();
      void vscode.window.showInformationMessage(
        "automated-code-review-tool: API key cleared.",
      );
    }),
  );

  void offerApiKeyMigration(context).catch(() => {
    void vscode.window.showErrorMessage(
      "automated-code-review-tool: API key migration failed. Plain-text settings may remain; retry with Set API Key.",
    );
  });
}

/** Subscriptions registered on the context are disposed by VS Code. */
export function deactivate(): void {}

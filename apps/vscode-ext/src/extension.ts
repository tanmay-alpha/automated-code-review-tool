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
import { clearAll, createStatusBar, scanFile } from "./reviewer";
import {
  clearLegacyApiKeySetting,
  deleteApiKey,
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
      const files = await vscode.workspace.findFiles(
        "**/*.{py,js,jsx,ts,tsx,java}",
        "**/{.git,node_modules,out,dist,build}/**",
      );
      for (const uri of files) {
        const doc = await vscode.workspace.openTextDocument(uri);
        if (apiLanguage(doc.languageId)) {
          await scanFile(doc, diagnosticCollection, secrets);
        }
      }
      void vscode.window.showInformationMessage(
        `automated-code-review-tool: scanned ${files.length} workspace file${files.length === 1 ? "" : "s"}.`,
      );
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

  void offerApiKeyMigration(context);
}

/** Subscriptions registered on the context are disposed by VS Code. */
export function deactivate(): void {}

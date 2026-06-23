/**
 * CodeLens — VS Code extension entry point.
 *
 * Loaded by VS Code when one of the activationEvents in
 * package.json fires (any of the supported languages is opened).
 *
 * Responsibilities:
 *   - Create the `codelens` DiagnosticCollection so findings can
 *     be displayed as inline squigglies in the editor.
 *   - Auto-scan any saved file whose language is supported.
 *   - Provide a manual "Scan Current File" command.
 *   - Clean up subscriptions and the status bar on deactivation.
 */
import * as vscode from "vscode";
import { clearAll, scanFile } from "./reviewer";
import { scanOnSave } from "./config";

/** Languages the CodeLens backend currently understands. */
const SUPPORTED_LANGUAGES = new Set<string>([
  "python",
  "javascript",
  "typescript",
  "java",
]);

/**
 * Called by VS Code when the extension activates. The context
 * holds the disposables so they can be cleaned up on deactivation.
 */
export function activate(context: vscode.ExtensionContext): void {
  // Single shared collection — squigglies across the editor surface.
  const diagnosticCollection = vscode.languages.createDiagnosticCollection(
    "codelens",
  );
  context.subscriptions.push(diagnosticCollection);

  // Auto-scan on save (only supported languages).
  context.subscriptions.push(
    vscode.workspace.onDidSaveTextDocument(async (doc) => {
      if (!SUPPORTED_LANGUAGES.has(doc.languageId)) return;
      if (!scanOnSave()) return;
      await scanFile(doc, diagnosticCollection);
    }),
  );

  // Manual scan command — runs even if the auto-scan is off.
  context.subscriptions.push(
    vscode.commands.registerCommand("codelens.scanFile", async () => {
      const editor = vscode.window.activeTextEditor;
      if (!editor) {
        void vscode.window.showInformationMessage(
          "CodeLens: open a file to scan.",
        );
        return;
      }
      await scanFile(editor.document, diagnosticCollection);
    }),
  );

  // Clear command — useful when reviewing fixes.
  context.subscriptions.push(
    vscode.commands.registerCommand("codelens.clear", () => {
      clearAll(diagnosticCollection);
    }),
  );

  // Status bar message on first activation.
  void vscode.window.showInformationMessage(
    "CodeLens is active. Save any supported file to scan it.",
  );
}

/**
 * Called by VS Code on extension uninstall / window reload. The
 * context disposes its own subscriptions; we just need to make
 * sure the diagnostic collection is cleared.
 */
export function deactivate(): void {
  // Disposables are cleaned up by VS Code. Nothing to do here.
}

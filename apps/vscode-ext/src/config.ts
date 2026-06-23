/**
 * Configuration readers for the CodeLens VS Code extension.
 *
 * Every setting lives under the `codelens.*` namespace in
 * VS Code's `settings.json`. We re-read on every call (not cached)
 * so a user's edits take effect immediately on the next save.
 *
 * See `package.json → contributes.configuration` for the schema.
 */
import * as vscode from "vscode";

const NAMESPACE = "codelens";

/**
 * Returns the user's API key, or an empty string if not set.
 * Callers should treat an empty string as "user not configured".
 */
export function getApiKey(): string {
  const raw = readConfig().get<string>("apiKey") ?? "";
  return raw.trim();
}

/**
 * Base URL of the CodeLens API, with a sensible default for
 * local development. Trailing slashes are stripped.
 */
export function getApiUrl(): string {
  const raw =
    readConfig().get<string>("apiUrl") ?? "http://localhost:8080";
  return raw.replace(/\/+$/, "");
}

/**
 * Master switch. When false, onDidSaveTextDocument is a no-op
 * (the manual `codelens.scanFile` command still works).
 */
export function isEnabled(): boolean {
  return readConfig().get<boolean>("enabled", true);
}

/**
 * Whether to auto-scan on save. Independent of `enabled` so a
 * user can keep the extension loaded but skip background work.
 */
export function scanOnSave(): boolean {
  return readConfig().get<boolean>("scanOnSave", true);
}

function readConfig(): vscode.WorkspaceConfiguration {
  return vscode.workspace.getConfiguration(NAMESPACE);
}

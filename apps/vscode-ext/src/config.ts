/**
 * Configuration readers for the automated-code-review-tool VS Code extension.
 *
 * Every setting lives under the `automated-code-review-tool.*` namespace in
 * VS Code's `settings.json`. We re-read on every call (not cached)
 * so a user's edits take effect immediately on the next save.
 *
 * The API key is the exception: it lives in VS Code SecretStorage. The
 * deprecated plain-text `apiKey` setting is read only as a migration fallback.
 *
 * See `package.json → contributes.configuration` for the schema.
 */
import * as vscode from "vscode";
import { API_KEY_SECRET, resolveApiKey, ResolvedApiKey } from "./apiKey";

const NAMESPACE = "automated-code-review-tool";
const LEGACY_API_KEY_SETTING = "apiKey";
const MIGRATION_OFFERED_STATE = "automated-code-review-tool.apiKeyMigrationOffered";

/**
 * Returns the API key from SecretStorage, falling back to the deprecated
 * plain-text setting. An empty key means "user not configured".
 */
export async function getApiKey(
  secrets: vscode.SecretStorage,
): Promise<ResolvedApiKey> {
  return resolveApiKey(
    await secrets.get(API_KEY_SECRET),
    readConfig().get<string>(LEGACY_API_KEY_SETTING),
  );
}

export async function storeApiKey(
  secrets: vscode.SecretStorage,
  key: string,
): Promise<void> {
  await secrets.store(API_KEY_SECRET, key.trim());
}

export async function deleteApiKey(secrets: vscode.SecretStorage): Promise<void> {
  await secrets.delete(API_KEY_SECRET);
}

/**
 * If the key still comes from the plain-text setting, offer once (tracked in
 * globalState) to move it into SecretStorage and remove it from settings.
 */
export async function offerApiKeyMigration(
  context: vscode.ExtensionContext,
): Promise<void> {
  if (context.globalState.get<boolean>(MIGRATION_OFFERED_STATE)) return;
  const resolved = await getApiKey(context.secrets);
  if (resolved.source !== "legacySetting") return;

  await context.globalState.update(MIGRATION_OFFERED_STATE, true);
  const move = "Move to secure storage";
  const choice = await vscode.window.showWarningMessage(
    "automated-code-review-tool: your API key is stored in plain text in settings.json. " +
      "Move it to VS Code's secret storage?",
    move,
    "Not now",
  );
  if (choice !== move) return;

  await storeApiKey(context.secrets, resolved.key);
  await clearLegacyApiKeySetting();
  void vscode.window.showInformationMessage(
    "automated-code-review-tool: API key moved to secret storage and removed from settings.",
  );
}

/** Removes the deprecated plain-text key from the user and workspace settings that set it. */
export async function clearLegacyApiKeySetting(): Promise<void> {
  const config = readConfig();
  const inspected = config.inspect<string>(LEGACY_API_KEY_SETTING);
  if (!inspected) return;
  if (inspected.globalValue !== undefined) {
    await config.update(LEGACY_API_KEY_SETTING, undefined, vscode.ConfigurationTarget.Global);
  }
  if (inspected.workspaceValue !== undefined) {
    await config.update(LEGACY_API_KEY_SETTING, undefined, vscode.ConfigurationTarget.Workspace);
  }
}

/**
 * Base URL of the automated-code-review-tool API, with a sensible default for
 * local development. Trailing slashes are stripped.
 */
export function getApiUrl(): string {
  const raw =
    readConfig().get<string>("apiUrl") ?? "http://localhost:8080";
  return raw.replace(/\/+$/, "");
}

/**
 * Master switch. When false, no scans run, neither on save nor via the
 * scan commands.
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

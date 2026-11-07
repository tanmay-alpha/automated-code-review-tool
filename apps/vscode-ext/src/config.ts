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
 * If a plain-text setting remains, offer to remove it after securing the
 * effective key. Retry on activation until migrated; preserve existing secrets.
 */
export async function offerApiKeyMigration(
  context: vscode.ExtensionContext,
): Promise<void> {
  const resources = [undefined, ...(vscode.workspace.workspaceFolders ?? []).map((folder) => folder.uri)];
  const legacy = resources
    .map((resource) => resolveApiKey(undefined,
      vscode.workspace.getConfiguration(NAMESPACE, resource).get<string>(LEGACY_API_KEY_SETTING)))
    .find((value) => value.source === "legacySetting");
  if (!legacy) return;
  const resolved = resolveApiKey(await context.secrets.get(API_KEY_SECRET), legacy.key);

  const move = "Move to secure storage";
  const choice = await vscode.window.showWarningMessage(
    "automated-code-review-tool: your API key is stored in plain text in settings.json. " +
      "Move it to VS Code's secret storage?",
    move,
    "Not now",
  );
  if (choice !== move) return;

  if (resolved.source !== "secretStorage") {
    await storeApiKey(context.secrets, resolved.key);
  }
  await clearLegacyApiKeySetting();
  void vscode.window.showInformationMessage(
    "automated-code-review-tool: API key moved to secret storage and removed from settings.",
  );
}

/** Removes the deprecated plain-text key from user, workspace and all workspace folders. */
export async function clearLegacyApiKeySetting(): Promise<void> {
  const resources = [undefined, ...(vscode.workspace.workspaceFolders ?? []).map((folder) => folder.uri)];
  for (const resource of resources) {
    const config = vscode.workspace.getConfiguration(NAMESPACE, resource);
    const inspected = config.inspect<string>(LEGACY_API_KEY_SETTING);
    if (!inspected) continue;
    if (inspected.globalValue !== undefined) {
      await config.update(LEGACY_API_KEY_SETTING, undefined, vscode.ConfigurationTarget.Global);
    }
    if (inspected.workspaceValue !== undefined) {
      await config.update(LEGACY_API_KEY_SETTING, undefined, vscode.ConfigurationTarget.Workspace);
    }
    if (resource && inspected.workspaceFolderValue !== undefined) {
      await config.update(LEGACY_API_KEY_SETTING, undefined, vscode.ConfigurationTarget.WorkspaceFolder);
    }
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

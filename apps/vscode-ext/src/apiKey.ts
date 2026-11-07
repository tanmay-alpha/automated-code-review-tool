/**
 * API key resolution. Kept free of the `vscode` import so it can be unit
 * tested with plain Node.
 */

/** Key under which the API key is stored in VS Code SecretStorage. */
export const API_KEY_SECRET = "automated-code-review-tool.apiKey";

export type ApiKeySource = "secretStorage" | "legacySetting" | "none";

export interface ResolvedApiKey {
  key: string;
  source: ApiKeySource;
}

/**
 * SecretStorage wins; the deprecated plain-text `apiKey` setting is only a
 * fallback for users who have not migrated yet. Blank values count as unset.
 */
export function resolveApiKey(
  secretValue: string | undefined,
  legacySettingValue: string | undefined,
): ResolvedApiKey {
  const secret = secretValue?.trim() ?? "";
  if (secret) return { key: secret, source: "secretStorage" };
  const legacy = legacySettingValue?.trim() ?? "";
  if (legacy) return { key: legacy, source: "legacySetting" };
  return { key: "", source: "none" };
}

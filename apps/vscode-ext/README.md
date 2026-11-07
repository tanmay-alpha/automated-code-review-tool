# VS Code extension

This package sends the active file to an automated-code-review-tool API and
renders returned findings as VS Code diagnostics. It supports Python,
JavaScript, TypeScript, and Java documents; TypeScript is sent to the API as
`javascript`, the closest language the ML worker accepts.

The extension is not published to the VS Code Marketplace. Build a VSIX
locally with `npm run package` and install it as shown below.

## Build and test

```bash
cd apps/vscode-ext
npm ci
npm run compile
npm test
npm run package
```

`npm test` covers the request body, bearer header, timeout, HTTP failures,
finding-to-diagnostic mapping, duplicate suppression, language mapping, the
200,000-character input limit, and the API-key lookup order (secret storage
before the deprecated setting).

## Install a local VSIX

```bash
code --install-extension automated-code-review-tool-reviewer-1.0.0.vsix
```

Restart VS Code after installation.

## Configure

### API key

Run **`automated-code-review-tool: Set API Key`** from the Command Palette and
paste the key from the dashboard (Settings → API Key). The key is stored in
VS Code's SecretStorage (the OS keychain), not in `settings.json`, and is sent
as `Authorization: Bearer ...`. **`automated-code-review-tool: Clear API Key`**
removes it.

The old plain-text `automated-code-review-tool.apiKey` setting is deprecated.
If it is still set and no key is in secret storage, the extension uses it and
offers once to move it into secret storage (which also removes it from your
user and workspace settings). Setting or clearing the key via the commands also
removes the plain-text setting. Treat the key as a secret and rotate it if it
was ever committed.

### Settings

Open Settings and search for `automated-code-review-tool`:

| Setting | Default | Purpose |
| --- | --- | --- |
| `automated-code-review-tool.apiUrl` | `http://localhost:8080` | Spring API base URL. Remote URLs should use HTTPS. |
| `automated-code-review-tool.enabled` | `true` | Enables scans (on save and via commands). |
| `automated-code-review-tool.scanOnSave` | `true` | Scans supported files when saved. |
| `automated-code-review-tool.apiKey` | empty | Deprecated plain-text fallback; use the Set API Key command. |

## Commands

- `automated-code-review-tool: Scan Current File`
- `automated-code-review-tool: Scan All Files in Workspace`
- `automated-code-review-tool: Clear Diagnostics`
- `automated-code-review-tool: Set API Key`
- `automated-code-review-tool: Clear API Key`

The client skips files longer than 200,000 characters (the API's request
limit), applies a 15-second request timeout, clears stale diagnostics after
failures, and deduplicates repeated findings.
The server decides which concrete taxonomy IDs are detected; the extension does
not contain a separate label list.

## License

MIT © Tanmay Mangal. See [LICENSE](LICENSE).

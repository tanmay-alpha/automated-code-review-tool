# VS Code extension

This package sends the active file to an automated-code-review-tool API and
renders returned findings as VS Code diagnostics. It supports Python,
JavaScript, TypeScript, and Java documents.

The repository does not claim a Marketplace release. Build a VSIX locally or
install one produced by a trusted release workflow.

## Build and test

```bash
cd apps/vscode-ext
npm ci
npm run compile
npm test
npm run package
```

`npm test` covers the request body, bearer header, timeout, HTTP failures,
finding-to-diagnostic mapping, duplicate suppression, and the 1 MB UTF-8 input
limit.

## Install a local VSIX

```bash
code --install-extension automated-code-review-tool-reviewer-1.0.0.vsix
```

Restart VS Code after installation.

## Configure

Open Settings and search for `automated-code-review-tool`:

| Setting | Default | Purpose |
| --- | --- | --- |
| `automated-code-review-tool.apiUrl` | `http://localhost:8080` | Spring API base URL. Remote URLs should use HTTPS. |
| `automated-code-review-tool.apiKey` | empty | API key sent as `Authorization: Bearer ...`. |
| `automated-code-review-tool.enabled` | `true` | Enables scans. |
| `automated-code-review-tool.scanOnSave` | `true` | Scans supported files when saved. |

Store the API key in user settings, not a workspace settings file that might be
committed. Treat it as a secret and rotate it if exposed.

## Commands

- `automated-code-review-tool: Scan Current File`
- `automated-code-review-tool: Scan All Files in Workspace`
- `automated-code-review-tool: Clear Diagnostics`

The client rejects files larger than 1 MB, applies a 15-second request timeout,
clears stale diagnostics after failures, and deduplicates repeated findings.
The server decides which concrete taxonomy IDs are detected; the extension does
not contain a separate label list.

## License

MIT © Tanmay Mangal. See [LICENSE](LICENSE).

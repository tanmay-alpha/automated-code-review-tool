# CodeLens — VS Code Extension

Catch the architectural anti-patterns that traditional linters miss — directly in your editor, the moment you save.

## What it does

CodeLens is a VS Code extension that sends the file you're editing to the CodeLens API on save and surfaces architectural, security, performance, reliability, readability, and maintainability issues as inline squigglies. It catches the things linters don't:

- Architectural drift (god classes, tight coupling, circular deps)
- Performance foot-guns (N+1 queries, missing caching)
- Security smells (hardcoded secrets, injection risks)
- Reliability holes (missing null checks, bare `except`, resource leaks)
- Readability issues (magic numbers, misleading names)
- Maintainability debt (long methods, duplicated code, dead code)

Each finding shows up as a native VS Code diagnostic. Hover for the explanation, suggestion, and confidence score — no context switching.

## Installation

### Option 1 — VS Code Marketplace (recommended)

1. Open VS Code
2. Press `Ctrl+P` (or `Cmd+P` on macOS) to open the Quick Open palette
3. Run `Extensions: Install Extensions` (or click the Extensions icon in the sidebar)
4. Search for **CodeLens — Semantic Code Review**
5. Click **Install**

The extension is published by `tanmay-alpha`.

### Option 2 — Install from `.vsix`

If you have a pre-built `.vsix` file (for example, from a release artifact or local build):

```bash
# From a downloaded .vsix
code --install-extension codelens-reviewer-1.0.0.vsix

# Or build it locally first
cd apps/vscode-ext
npm run package   # produces a .vsix in this directory
code --install-extension codelens-reviewer-1.0.0.vsix
```

Restart VS Code after installation.

## Setup

CodeLens needs an API key to authenticate against the CodeLens backend.

1. Get your API key from the [CodeLens dashboard](https://codelens.example.com/dashboard/settings/api-key) — see [Getting an API key](#getting-an-api-key) below.
2. Open VS Code Settings (`Ctrl+,` / `Cmd+,`).
3. Search for **codelens.apiKey**.
4. Paste your key into the field.

![CodeLens settings screenshot placeholder — Settings panel showing "codelens.apiKey" field with API key pasted in](docs/images/settings-apikey-placeholder.png)

> **Screenshot placeholder:** replace `docs/images/settings-apikey-placeholder.png` with an actual screenshot of the VS Code Settings UI showing the `codelens.apiKey` field filled in.

That's it. The next time you save a supported file (`*.py`, `*.js`, `*.ts`, `*.java`), CodeLens will scan it automatically.

## How it works

1. **Save** a supported file in VS Code.
2. CodeLens **POSTs** the file's contents to `{codelens.apiUrl}/api/scan/file` with a `Bearer` token in the `Authorization` header.
3. The API returns findings — each with an anti-pattern ID, severity, line range, explanation, and confidence score.
4. CodeLens maps those findings to **squigglies** in your editor.
5. **Hover** over any squiggle to see the explanation, suggested fix, and confidence.

The status bar (bottom-right of VS Code) shows the scan result: `CodeLens: 3 issues found`, `CodeLens: ✅ Clean`, or `CodeLens: scanning…` while a request is in flight.

### Commands

| Command | Description |
|---------|-------------|
| `CodeLens: Scan Current File` | Manually re-scan the active file |
| `CodeLens: Scan All Files in Workspace` | Scan every supported file in the workspace |
| `CodeLens: Clear Diagnostics` | Remove all CodeLens squigglies |

Find them via the Command Palette (`Ctrl+Shift+P` / `Cmd+Shift+P`).

## Configuration

All settings live under the `codelens.*` namespace in VS Code Settings.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| `codelens.apiKey` | `string` | `""` | Your CodeLens API key (from the dashboard). **Required.** Sent as `Authorization: Bearer <key>` on every request. |
| `codelens.apiUrl` | `string` | `"http://localhost:8080"` | Base URL of the CodeLens API. Change this to point at staging, production, or a self-hosted instance. |
| `codelens.enabled` | `boolean` | `true` | Master enable/disable switch. When `false`, scans are skipped and the status bar shows `CodeLens: disabled`. |
| `codelens.scanOnSave` | `boolean` | `true` | Automatically scan supported files when you save them. Set to `false` to only scan via the manual command. |

### Example — point at production

```jsonc
// .vscode/settings.json (workspace)
{
  "codelens.apiKey": "cl_live_••••••••••••••••",
  "codelens.apiUrl": "https://api.codelens.example.com",
  "codelens.enabled": true,
  "codelens.scanOnSave": true
}
```

> Tip: prefer storing `codelens.apiKey` in your **user** settings (not workspace settings) so it isn't accidentally committed to version control.

## Anti-patterns detected

CodeLens detects findings across **six top-level categories**:

| # | Category | What it catches |
|---|----------|-----------------|
| 1 | **SECURITY** | Hardcoded secrets, injection flaws (SQL/XSS/CSRF), unsafe `eval()`, weak auth/encryption, exposed credentials. |
| 2 | **PERFORMANCE** | N+1 queries, nested loops, redundant calls, unnecessary iterations, missing caching, missed index/eager-load opportunities. |
| 3 | **ARCHITECTURE** | God classes, circular dependencies, feature envy, tight coupling / poor cohesion, missing or improper abstractions, interfaces, modules, or layers. |
| 4 | **RELIABILITY** | Bare `except`, missing null/None checks, resource leaks, missing timeouts / retries / rollback, absent input validation. |
| 5 | **READABILITY** | Magic numbers, misleading or unclear names, abbreviations, poor naming, missing documentation / comments. |
| 6 | **MAINTAINABILITY** | Overly long methods, deep nesting, copy-paste / duplicated code, dead / unused code, overly complex logic, hardcoded constants instead of configuration. |

Each finding is tagged with a more specific `antiPattern` ID (e.g. `PERFORMANCE_N_PLUS_1`, `SECRET_HARDCODED`) underneath its top-level category.

## Getting an API key

1. Sign in to the CodeLens dashboard.
2. Navigate to **Settings → API Key**.
3. Click **Generate new key**, copy it, and paste it into the `codelens.apiKey` VS Code setting.

🔗 **[Open Dashboard → Settings → API Key](https://codelens.example.com/dashboard/settings/api-key)**

> Never commit your API key to version control. Treat it like any other secret.

## Troubleshooting

- **Status bar shows `CodeLens: no API key`** — set `codelens.apiKey` in VS Code settings.
- **Status bar shows `CodeLens: 401 / 403`** — your API key is invalid or revoked. Regenerate one from the dashboard.
- **Status bar shows `CodeLens: API unreachable`** — check `codelens.apiUrl` and that the backend is running.
- **No squigglies after saving** — make sure the file language is supported (Python, JavaScript, TypeScript, Java) and `codelens.enabled` + `codelens.scanOnSave` are both `true`.

## License

MIT © Tanmay Mangal — see [LICENSE](../../LICENSE) for details.
# CodeLens GitHub Action

Semantic code review as a GitHub Actions check. The action pulls the diff for a
pull request, posts it to the CodeLens API, and surfaces the findings as inline
PR annotations (errors, warnings, notices) sorted by severity. It exposes a
quality score, a finding count, and a critical-finding count as job outputs,
and optionally fails the check when the quality score drops below a threshold
you configure. Unlike traditional linters, CodeLens catches *semantic*
anti-patterns — the kind of structural smells and bad defaults a rule-based
tool misses.

## Usage

Add the following to `.github/workflows/codelens.yml` in any repository:

```yaml
name: CodeLens Review
on:
  pull_request:
    branches: [main]

jobs:
  codelens:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write
    steps:
      - uses: actions/checkout@v4

      - name: CodeLens Code Review
        uses: codelens/codelens-action@v1
        with:
          api-url: https://api.codelens.dev
          api-key: ${{ secrets.CODELENS_API_KEY }}
          language: python
          fail-threshold: '70'
```

The action requires a `GITHUB_TOKEN` (provided automatically by the runner)
so it can read the diff and post review comments. Store your CodeLens API key
as a repository or organization secret — see the
[API key section](#getting-an-api-key) below.

## Inputs

| Input             | Type    | Required | Default   | Description                                                                 |
| ----------------- | ------- | -------- | --------- | --------------------------------------------------------------------------- |
| `api-url`         | string  | **Yes**  | —         | CodeLens API base URL (e.g. `https://api.codelens.dev`).                    |
| `api-key`         | string  | **Yes**  | —         | CodeLens API key issued from the dashboard. Treat as a secret.              |
| `language`        | string  | No       | `python`  | Primary language of the PR. One of `python`, `javascript`, `java`.          |
| `fail-threshold`  | number  | No       | `60`      | Minimum quality score (0–100). The job fails when the score is below this.  |

`fail-threshold` is parsed as an integer and must be between `0` and `100`.
Values outside that range cause the action to fail with a clear error.

## Outputs

| Output            | Type    | Description                                                |
| ----------------- | ------- | ---------------------------------------------------------- |
| `quality-score`   | string  | Quality score reported by CodeLens (0–100). Empty if n/a. |
| `findings-count`  | string  | Total number of findings returned for the PR diff.         |
| `critical-count`  | string  | Number of `CRITICAL` severity findings.                    |

Consume them in downstream steps with `${{ steps.codelens.outputs.quality-score }}`,
`${{ steps.codelens.outputs.findings-count }}`, and
`${{ steps.codelens.outputs.critical-count }}`.

## Example PR annotation

When the action runs against a pull request, each finding shows up directly
on the changed lines of the **Files changed** tab and as a workflow log
annotation. Critical findings use the error gutter, major findings use the
warning gutter, minor findings are notices:

```
❌ src/services/auth_service.py:42
   God Class Anti-pattern (critical)
   AuthService now has 8 distinct responsibilities and 1,240 LOC —
   split into TokenService, UserService, and AuditService.
   confidence: 92%
```

In the workflow log the same finding prints as:

```
##[error]God Class Anti-pattern (critical)──src/services/auth_service.py:42──
AuthService now has 8 distinct responsibilities and 1,240 LOC — split into
TokenService, UserService, and AuditService.
confidence: 92%
```

A summary line is printed at the end of the run:

```
CodeLens complete: acme/widgets#142
Quality score: 74/100
Findings: 7 total (1 critical, 3 major, 3 minor)
```

## Getting an API key

CodeLens API keys are minted from the CodeLens dashboard:

1. Sign in at [https://codelens.dev](https://codelens.dev).
2. Open **Settings → API Keys**.
3. Click **Create key**, give it a name (e.g. `github-actions-prod`), and copy
   the value — it is shown only once.
4. In your repository, go to **Settings → Secrets and variables → Actions**
   and add the value as `CODELENS_API_KEY`.

Rotate the key from the dashboard if it ever leaks. The same key works across
all repositories under your CodeLens workspace, so reuse one secret across
many workflows rather than minting a fresh key per repo.

## License

MIT

# GitHub Action

This JavaScript action reviews the files changed in a pull request with an
automated-code-review-tool API, annotates findings on the lines the PR adds,
and optionally fails the job when the quality score is below a configured
threshold.

## How it works

1. Lists the PR files (`pulls.listFiles`, paginated). Removed files, binary
   files (GitHub returns no `patch`), files whose patch adds no lines, and
   files in other languages are skipped. Supported extensions: `.py`
   (`python`); `.js`, `.jsx`, `.mjs`, `.cjs`, `.ts`, `.tsx` (`javascript`;
   TypeScript is scanned with the JavaScript rules); `.java` (`java`).
2. Takes the first `max-files` eligible files (default 50) and, at most four
   at a time, reads each file at the PR head commit (`repos.getContent`) and
   sends it to `POST /api/scan/file` with its real path and language. Files
   longer than 200,000 characters (the API's request limit) are skipped with
   a warning.
3. Keeps only findings whose line range includes at least one line added by
   the PR, using the `@@ -a,b +c,d @@` hunk headers and `+` lines of the
   file's patch, and annotates them on the real file and lines.

### Quality gate

The gate uses one score for the whole change, computed with the API's own
formula, `clamp(100 - sum(weight * confidence), 0, 100)` rounded half-up to
two decimals in exact decimal arithmetic (weights from
`contracts/quality_score_cases.json`: critical 20, major 10, minor 3; a
missing or unknown severity weighs as minor, a missing confidence counts as 0,
and confidence is clamped to [0, 1]), applied once to the combined,
deduplicated findings on added lines from all scanned files. Penalties
therefore accumulate across files, exactly as they would if the whole change
were scanned as one document. A per-file minimum would ignore that
accumulation and an average would dilute it with clean files, so neither is
used. The per-file scores returned by the API are ignored because they also
count findings on unchanged lines.

### Limits

- Each scanned file is one API request; API keys are rate-limited (60
  requests per minute by default), so keep `max-files` below that.
- GitHub omits the `patch` for very large diffs and lists at most 3,000 files
  per pull request; such files are not scanned.

The committed `dist/` directory is intentional: GitHub executes
`dist/index.js` directly. Source changes must be followed by `npm run build`,
and CI verifies that the bundle is current.

## Usage

```yaml
name: Automated code review

on:
  pull_request:

jobs:
  review:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: read
    steps:
      - uses: tanmay-alpha/automated-code-review-tool/github-action@main
        with:
          api-url: https://your-api.example.com
          api-key: ${{ secrets.AUTOMATED_CODE_REVIEW_TOOL_API_KEY }}
          github-token: ${{ github.token }}
          fail-threshold: "60"
```

For production use, pin the action to a reviewed commit SHA or immutable
release tag. `api-url` must use HTTPS.

## Inputs

| Input | Required | Default | Description |
| --- | --- | --- | --- |
| `api-url` | yes | — | HTTPS base URL of the Spring API. |
| `api-key` | yes | — | API key stored as a GitHub Actions secret. |
| `github-token` | yes | — | Token used to list the PR files and read their contents. |
| `fail-threshold` | no | `60` | Minimum accepted quality score, from 0 to 100. |
| `fetch-timeout-ms` | no | `30000` | Timeout for each GitHub and API request, from 1000 to 300000 ms. |
| `max-files` | no | `50` | Maximum number of changed files to scan, from 1 to 3000. |

## Outputs

| Output | Description |
| --- | --- |
| `quality-score` | Score computed from findings on added lines (see Quality gate), or an empty string when no file was scanned. |
| `findings-count` | Deduplicated count of findings on added lines. |
| `critical-count` | Deduplicated count of `critical` findings on added lines. |

## Develop

```bash
cd github-action
npm ci
npm test
npm run build
git diff --exit-code -- dist
```

## License

MIT

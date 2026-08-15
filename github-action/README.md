# GitHub Action

This JavaScript action reads a pull-request diff, sends it to an
automated-code-review-tool API, emits workflow annotations for returned
findings, and optionally fails the job when the reported quality score is below
a configured threshold.

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
      - uses: actions/checkout@v4
      - uses: tanmay-alpha/automated-code-review-tool/github-action@main
        with:
          api-url: https://your-api.example.com
          api-key: ${{ secrets.AUTOMATED_CODE_REVIEW_TOOL_API_KEY }}
          github-token: ${{ github.token }}
          language: python
          fail-threshold: "60"
```

For production use, pin the action to a reviewed commit SHA or immutable
release tag. `api-url` must use HTTPS.

## Inputs

| Input | Required | Default | Description |
| --- | --- | --- | --- |
| `api-url` | yes | — | HTTPS base URL of the Spring API. |
| `api-key` | yes | — | API key stored as a GitHub Actions secret. |
| `github-token` | yes | — | Token used to read the pull-request diff. |
| `language` | no | `python` | Language hint passed to the scan endpoint. |
| `fail-threshold` | no | `60` | Minimum accepted quality score, from 0 to 100. |
| `fetch-timeout-ms` | no | `30000` | GitHub and API request timeout, from 1000 to 300000 ms. |

## Outputs

| Output | Description |
| --- | --- |
| `quality-score` | API-reported score, or an empty string when absent. |
| `findings-count` | Deduplicated finding count. |
| `critical-count` | Deduplicated `critical` finding count. |

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

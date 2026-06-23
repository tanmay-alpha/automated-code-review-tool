/**
 * CodeLens GitHub Action entrypoint.
 *
 * Flow:
 *   1. Read inputs (api-url, api-key, language, fail-threshold)
 *   2. Validate event is pull_request
 *   3. Fetch the diff via Octokit
 *   4. POST to ${apiUrl}/api/scan/action
 *   5. Surface findings as `core.warning` annotations
 *   6. Emit outputs and optionally fail the check
 *
 * No external test framework dependencies — runs in the GitHub-hosted
 * runner with Node 20+ and `@actions/*` already on the npm cache.
 */

const core = require('@actions/core');
const github = require('@actions/github');

/** @typedef {{antiPattern:string,severity:string,confidence:number,explanation:string,filePath?:string,lineStart?:number|null,lineEnd?:number|null,category?:string}} FindingDto */

async function run() {
  try {
    const apiUrl = (core.getInput('api-url') || '').trim().replace(/\/+$/, '');
    const apiKey = (core.getInput('api-key') || '').trim();
    const language = (core.getInput('language') || 'python').trim();
    const failThreshold = Number.parseInt(core.getInput('fail-threshold') || '60', 10);

    if (!apiUrl) return core.setFailed('Missing required input: api-url');
    if (!apiKey) return core.setFailed('Missing required input: api-key');
    if (Number.isNaN(failThreshold) || failThreshold < 0 || failThreshold > 100) {
      return core.setFailed(`Invalid fail-threshold: must be 0-100, got ${failThreshold}`);
    }
    if (!/^https?:\/\//i.test(apiUrl)) {
      return core.setFailed(`api-url must start with http(s):// — got: ${apiUrl}`);
    }

    const context = github.context;
    if (context.eventName !== 'pull_request') {
      core.info(`Event is "${context.eventName}" — CodeLens only runs on pull_request. Skipping.`);
      return;
    }

    const prNumber = context.payload.pull_request?.number;
    if (!prNumber) {
      return core.setFailed('Could not read pull_request.number from event payload');
    }
    const repoFullName = `${context.repo.owner}/${context.repo.repo}`;
    const githubToken = process.env.GITHUB_TOKEN;

    core.info(`CodeLens: scanning ${repoFullName}#${prNumber} (language=${language})`);

    // 1. Pull the diff from GitHub.
    const octokit = github.getOctokit(githubToken);
    const { data: diff } = await octokit.rest.pulls.get({
      owner: context.repo.owner,
      repo: context.repo.repo,
      pull_number: prNumber,
      mediaType: { format: 'diff' },
    });
    if (!diff || typeof diff !== 'string' || diff.length === 0) {
      core.warning('Empty diff returned from GitHub — nothing to scan.');
      return;
    }

    // 2. Call the CodeLens API.
    const response = await fetch(`${apiUrl}/api/scan/action`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({ diff, repoFullName, prNumber, language }),
    });

    if (!response.ok) {
      const text = await response.text().catch(() => '');
      return core.setFailed(
        `CodeLens API returned HTTP ${response.status}: ${text.slice(0, 500)}`,
      );
    }

    const result = await response.json();
    /** @type {FindingDto[]} */
    const findings = Array.isArray(result.findings) ? result.findings : [];
    const qualityScore =
      typeof result.qualityScore === 'number' ? result.qualityScore : null;

    // 3. Emit findings as GitHub annotations.
    let criticalCount = 0;
    let majorCount = 0;
    let minorCount = 0;
    for (const f of findings) {
      const sev = (f.severity || '').toLowerCase();
      if (sev === 'critical') criticalCount++;
      else if (sev === 'major') majorCount++;
      else if (sev === 'minor') minorCount++;

      const title = `${f.antiPattern || 'Anti-pattern'} (${sev || 'unknown'})`;
      const body = [
        f.explanation || '',
        `confidence: ${Math.round((f.confidence || 0) * 100)}%`,
      ]
        .filter(Boolean)
        .join('\n');

      if (f.lineStart) {
        // Inline annotation on the diff.
        const annotationProps = {
          file: f.filePath || '',
          startLine: f.lineStart,
          endLine: f.lineEnd || f.lineStart,
          title,
        };
        if (sev === 'critical') core.error(body, annotationProps);
        else if (sev === 'major') core.warning(body, annotationProps);
        else core.notice(body, annotationProps);
      } else {
        // File-level annotation (no line info).
        if (sev === 'critical') core.error(`${title}\n${body}`);
        else core.warning(`${title}\n${body}`);
      }
    }

    // 4. Set outputs.
    core.setOutput('quality-score', qualityScore == null ? '' : String(qualityScore));
    core.setOutput('findings-count', String(findings.length));
    core.setOutput('critical-count', String(criticalCount));

    const summary = [
      `CodeLens complete: ${repoFullName}#${prNumber}`,
      `Quality score: ${qualityScore == null ? 'n/a' : `${qualityScore}/100`}`,
      `Findings: ${findings.length} total (${criticalCount} critical, ${majorCount} major, ${minorCount} minor)`,
    ].join('\n');
    core.info(summary);

    // 5. Optional failure.
    if (qualityScore != null && qualityScore < failThreshold) {
      core.setFailed(
        `Quality score ${qualityScore}/100 is below threshold ${failThreshold}/100`,
      );
    }
  } catch (err) {
    // @actions/core's setFailed is itself safe to call from anywhere.
    core.setFailed(err instanceof Error ? err.message : String(err));
  }
}

run();

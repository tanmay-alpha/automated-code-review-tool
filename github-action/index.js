/**
 * CodeLens GitHub Action entrypoint.
 *
 * Fetches the pull-request diff, submits it to the ad-hoc file scan API,
 * emits annotations and outputs, and enforces the configured quality gate.
 */

import * as core from '@actions/core';
import * as github from '@actions/github';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** @typedef {{antiPattern:string,severity:string,confidence:number,explanation:string,filePath?:string,lineStart?:number|null,lineEnd?:number|null,category?:string}} FindingDto */

async function run(deps = {}) {
  const coreApi = deps.core || core;
  const githubApi = deps.github || github;
  const fetchApi = deps.fetch || fetch;

  try {
    const apiUrl = (coreApi.getInput('api-url') || '').trim().replace(/\/+$/, '');
    const apiKey = (coreApi.getInput('api-key') || '').trim();
    const language = (coreApi.getInput('language') || 'python').trim();
    const failThreshold = Number.parseInt(
      coreApi.getInput('fail-threshold') || '60',
      10,
    );

    if (!apiUrl) return coreApi.setFailed('Missing required input: api-url');
    if (!apiKey) return coreApi.setFailed('Missing required input: api-key');
    if (Number.isNaN(failThreshold) || failThreshold < 0 || failThreshold > 100) {
      return coreApi.setFailed(
        `Invalid fail-threshold: must be 0-100, got ${failThreshold}`,
      );
    }
    if (!/^https?:\/\//i.test(apiUrl)) {
      return coreApi.setFailed(`api-url must start with http(s)://; got: ${apiUrl}`);
    }

    const context = githubApi.context;
    if (context.eventName !== 'pull_request') {
      coreApi.info(
        `Event is "${context.eventName}"; CodeLens only runs on pull_request. Skipping.`,
      );
      return;
    }

    const prNumber = context.payload.pull_request?.number;
    if (!prNumber) {
      return coreApi.setFailed('Could not read pull_request.number from event payload');
    }

    const repoFullName = `${context.repo.owner}/${context.repo.repo}`;
    coreApi.info(
      `CodeLens: scanning ${repoFullName}#${prNumber} (language=${language})`,
    );

    const octokit = githubApi.getOctokit(process.env.GITHUB_TOKEN);
    const { data: diff } = await octokit.rest.pulls.get({
      owner: context.repo.owner,
      repo: context.repo.repo,
      pull_number: prNumber,
      mediaType: { format: 'diff' },
    });
    if (!diff || typeof diff !== 'string') {
      coreApi.warning('Empty diff returned from GitHub; nothing to scan.');
      return;
    }

    const response = await fetchApi(`${apiUrl}/api/scan/file`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        content: diff,
        language,
        filePath: `${repoFullName}#${prNumber}`,
      }),
    });

    if (!response.ok) {
      const text = await response.text().catch(() => '');
      return coreApi.setFailed(
        `CodeLens API returned HTTP ${response.status}: ${text.slice(0, 500)}`,
      );
    }

    const result = await response.json();
    /** @type {FindingDto[]} */
    const findings = Array.isArray(result.findings) ? result.findings : [];
    const qualityScore =
      typeof result.qualityScore === 'number' ? result.qualityScore : null;

    let criticalCount = 0;
    let majorCount = 0;
    let minorCount = 0;
    for (const finding of findings) {
      const severity = (finding.severity || '').toLowerCase();
      if (severity === 'critical') criticalCount++;
      else if (severity === 'major') majorCount++;
      else if (severity === 'minor') minorCount++;

      const title = `${finding.antiPattern || 'Anti-pattern'} (${severity || 'unknown'})`;
      const body = [
        finding.explanation || '',
        `confidence: ${Math.round((finding.confidence || 0) * 100)}%`,
      ]
        .filter(Boolean)
        .join('\n');

      if (finding.lineStart) {
        const annotation = {
          file: finding.filePath || '',
          startLine: finding.lineStart,
          endLine: finding.lineEnd || finding.lineStart,
          title,
        };
        if (severity === 'critical') coreApi.error(body, annotation);
        else if (severity === 'major') coreApi.warning(body, annotation);
        else coreApi.notice(body, annotation);
      } else if (severity === 'critical') {
        coreApi.error(`${title}\n${body}`);
      } else {
        coreApi.warning(`${title}\n${body}`);
      }
    }

    coreApi.setOutput('quality-score', qualityScore == null ? '' : String(qualityScore));
    coreApi.setOutput('findings-count', String(findings.length));
    coreApi.setOutput('critical-count', String(criticalCount));
    coreApi.info(
      [
        `CodeLens complete: ${repoFullName}#${prNumber}`,
        `Quality score: ${qualityScore == null ? 'n/a' : `${qualityScore}/100`}`,
        `Findings: ${findings.length} total (${criticalCount} critical, ${majorCount} major, ${minorCount} minor)`,
      ].join('\n'),
    );

    if (qualityScore != null && qualityScore < failThreshold) {
      coreApi.setFailed(
        `Quality score ${qualityScore}/100 is below threshold ${failThreshold}/100`,
      );
    }
  } catch (err) {
    coreApi.setFailed(err instanceof Error ? err.message : String(err));
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  void run();
}

export { run };

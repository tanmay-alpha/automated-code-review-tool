/**
 * automated-code-review-tool GitHub Action entrypoint.
 *
 * For each changed Python, JavaScript/TypeScript or Java file in the pull
 * request, fetches the file at the PR head commit, submits it to the API's
 * file scan endpoint, keeps only the findings that touch lines added by the
 * PR, emits file/line annotations, and enforces the quality gate on a score
 * computed from those findings.
 */

import * as core from '@actions/core';
import * as github from '@actions/github';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** @typedef {{antiPattern:string,severity:string,confidence:number,explanation:string,filePath?:string|null,lineStart?:number|null,lineEnd?:number|null,category?:string|null}} FindingDto */

// Mirrors the @Size(max = 200_000) limit on ScanFileRequest.content in the API.
const MAX_CONTENT_CHARS = 200_000;
const SCAN_CONCURRENCY = 4;

// File extensions mapped to the languages the ML worker accepts; its file-mode
// `language` field accepts only python/javascript/java/unknown, so TypeScript
// is sent as javascript.
const LANGUAGE_BY_EXTENSION = {
  '.py': 'python',
  '.js': 'javascript',
  '.jsx': 'javascript',
  '.mjs': 'javascript',
  '.cjs': 'javascript',
  '.ts': 'javascript',
  '.tsx': 'javascript',
  '.java': 'java',
};

// Penalty weights from contracts/quality_score_cases.json, the score formula
// shared by the API and the ML worker. Unknown severities weigh as minor.
const SEVERITY_PENALTY = { critical: 20, major: 10, minor: 3 };

async function run(deps = {}) {
  const coreApi = deps.core || core;
  const githubApi = deps.github || github;
  const fetchApi = deps.fetch || fetch;

  try {
    const apiUrl = (coreApi.getInput('api-url') || '').trim().replace(/\/+$/, '');
    const apiKey = (coreApi.getInput('api-key') || '').trim();
    const githubToken = (coreApi.getInput('github-token') || '').trim();
    const failThreshold = Number.parseInt(
      coreApi.getInput('fail-threshold') || '60',
      10,
    );
    const fetchTimeoutMs = Number.parseInt(
      coreApi.getInput('fetch-timeout-ms') || '30000',
      10,
    );
    const maxFiles = Number.parseInt(coreApi.getInput('max-files') || '50', 10);

    if (!apiUrl) return coreApi.setFailed('Missing required input: api-url');
    if (!apiKey) return coreApi.setFailed('Missing required input: api-key');
    if (!githubToken) return coreApi.setFailed('Missing required input: github-token');
    coreApi.setSecret?.(apiKey);
    coreApi.setSecret?.(githubToken);
    if (Number.isNaN(failThreshold) || failThreshold < 0 || failThreshold > 100) {
      return coreApi.setFailed(
        `Invalid fail-threshold: must be 0-100, got ${failThreshold}`,
      );
    }
    if (
      Number.isNaN(fetchTimeoutMs) ||
      fetchTimeoutMs < 1000 ||
      fetchTimeoutMs > 300000
    ) {
      return coreApi.setFailed(
        `Invalid fetch-timeout-ms: must be 1000-300000, got ${fetchTimeoutMs}`,
      );
    }
    if (Number.isNaN(maxFiles) || maxFiles < 1 || maxFiles > 3000) {
      return coreApi.setFailed(`Invalid max-files: must be 1-3000, got ${maxFiles}`);
    }
    // HTTPS-only — a CI secret in HTTP is exfiltration waiting to happen.
    if (!/^https:\/\//i.test(apiUrl)) {
      return coreApi.setFailed(
        `api-url must use https:// (plain http is rejected for security); got: ${apiUrl}`,
      );
    }

    const context = githubApi.context;
    if (context.eventName !== 'pull_request') {
      coreApi.info(
        `Event is "${context.eventName}"; automated-code-review-tool only runs on pull_request. Skipping.`,
      );
      return;
    }

    const prNumber = context.payload.pull_request?.number;
    const headSha = context.payload.pull_request?.head?.sha;
    if (!prNumber || !headSha) {
      return coreApi.setFailed(
        'Could not read pull_request.number and pull_request.head.sha from event payload',
      );
    }

    const { owner, repo } = context.repo;
    const prName = `${owner}/${repo}#${prNumber}`;
    const octokit = githubApi.getOctokit(githubToken);
    // @octokit/request has no timeout option; each call gets its own abort signal.
    const request = () => ({ signal: AbortSignal.timeout(fetchTimeoutMs) });

    // Paginated by hand so every page request gets a fresh timeout. GitHub
    // returns at most 3,000 files, so this stops by page 31.
    const prFiles = [];
    for (let page = 1; ; page++) {
      const { data } = await octokit.rest.pulls.listFiles({
        owner,
        repo,
        pull_number: prNumber,
        per_page: 100,
        page,
        request: request(),
      });
      prFiles.push(...data);
      if (data.length < 100) break;
    }

    // Removed files and binaries (no patch) have no added lines to review.
    const candidates = [];
    for (const file of prFiles) {
      const language = languageForPath(file.filename);
      if (file.status === 'removed' || !file.patch || !language) continue;
      const added = addedLineNumbers(file.patch);
      if (added.size > 0) candidates.push({ filePath: file.filename, language, added });
    }

    if (candidates.length === 0) {
      coreApi.info(`${prName}: no added Python, JavaScript/TypeScript or Java lines to scan.`);
      coreApi.setOutput('quality-score', '');
      coreApi.setOutput('findings-count', '0');
      coreApi.setOutput('critical-count', '0');
      return;
    }

    const files = candidates.slice(0, maxFiles);
    if (candidates.length > maxFiles) {
      coreApi.warning(
        `Scanning the first ${maxFiles} of ${candidates.length} eligible files (max-files).`,
      );
    }
    coreApi.info(`automated-code-review-tool: scanning ${files.length} file(s) in ${prName}`);

    const perFile = await mapWithConcurrency(files, SCAN_CONCURRENCY, async (file) => {
      const { data } = await octokit.rest.repos.getContent({
        owner,
        repo,
        path: file.filePath,
        ref: headSha,
        request: request(),
      });
      // The contents API only inlines base64 content for regular files up to
      // 1 MB, which is already far above the API's character limit.
      if (Array.isArray(data) || data.type !== 'file' || data.encoding !== 'base64') {
        coreApi.warning(`Skipping ${file.filePath}: content not available (not a regular file or too large).`);
        return [];
      }
      const content = Buffer.from(data.content, 'base64').toString('utf8');
      if (content.length > MAX_CONTENT_CHARS) {
        coreApi.warning(
          `Skipping ${file.filePath}: ${content.length} characters exceeds the API limit of ${MAX_CONTENT_CHARS}.`,
        );
        return [];
      }
      if (!content.trim()) return [];

      const findings = await postScan(fetchApi, {
        apiUrl,
        apiKey,
        timeoutMs: fetchTimeoutMs,
        body: { content, language: file.language, filePath: file.filePath },
      });
      return findings
        .filter((finding) => touchesAddedLines(finding, file.added))
        .map((finding) => ({ ...finding, filePath: file.filePath }));
    });

    /** @type {FindingDto[]} */
    const findings = dedupeFindings(perFile.flat());
    const score = qualityScore(findings);

    let criticalCount = 0;
    let majorCount = 0;
    let minorCount = 0;
    for (const finding of findings) {
      const severity = (finding.severity || '').toLowerCase();
      if (severity === 'critical') criticalCount++;
      else if (severity === 'major') majorCount++;
      else if (severity === 'minor') minorCount++;

      const body = [
        finding.explanation || '',
        `confidence: ${Math.round((finding.confidence || 0) * 100)}%`,
      ]
        .filter(Boolean)
        .join('\n');
      const annotation = {
        file: finding.filePath,
        startLine: finding.lineStart,
        endLine: Math.max(finding.lineStart, finding.lineEnd || finding.lineStart),
        title: `${finding.antiPattern || 'Anti-pattern'} (${severity || 'unknown'})`,
      };
      if (severity === 'critical') coreApi.error(body, annotation);
      else if (severity === 'major') coreApi.warning(body, annotation);
      else coreApi.notice(body, annotation);
    }

    coreApi.setOutput('quality-score', String(score));
    coreApi.setOutput('findings-count', String(findings.length));
    coreApi.setOutput('critical-count', String(criticalCount));
    coreApi.info(
      [
        `automated-code-review-tool complete: ${prName}`,
        `Quality score (added lines): ${score}/100`,
        `Findings: ${findings.length} total (${criticalCount} critical, ${majorCount} major, ${minorCount} minor)`,
      ].join('\n'),
    );

    if (score < failThreshold) {
      coreApi.setFailed(`Quality score ${score}/100 is below threshold ${failThreshold}/100`);
    }
  } catch (err) {
    coreApi.setFailed(err instanceof Error ? err.message : String(err));
  }
}

/** Returns the API language for a repository path, or undefined if unsupported. */
function languageForPath(filePath) {
  const ext = path.posix.extname(filePath).toLowerCase();
  return Object.hasOwn(LANGUAGE_BY_EXTENSION, ext) ? LANGUAGE_BY_EXTENSION[ext] : undefined;
}

/**
 * Returns the new-file line numbers added by a GitHub unified-diff patch.
 * `@@ -a,b +c,d @@` sets the next new-file line to c; `+` and context lines
 * advance it, `-` lines and "\ No newline at end of file" markers do not.
 */
function addedLineNumbers(patch) {
  const added = new Set();
  let line = 0;
  let inHunk = false;
  for (const text of patch.split('\n')) {
    const header = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(text);
    if (header) {
      line = Number(header[1]);
      inHunk = true;
    } else if (!inHunk) {
      continue;
    } else if (text.startsWith('+')) {
      added.add(line);
      line++;
    } else if (text.startsWith(' ')) {
      line++;
    }
  }
  return added;
}

/** True when the finding's line range includes at least one added line. */
function touchesAddedLines(finding, added) {
  if (!finding.lineStart) return false;
  const end = Math.max(finding.lineStart, finding.lineEnd || finding.lineStart);
  for (let line = finding.lineStart; line <= end; line++) {
    if (added.has(line)) return true;
  }
  return false;
}

/**
 * clamp(100 - sum(weight * confidence), 0, 100), rounded half-up to 2 dp, with
 * the API's and ML worker's rules: a missing or unknown severity weighs as
 * minor, a missing confidence counts as 0, and confidence is clamped to [0, 1].
 * Like them, it uses exact decimal arithmetic (BigInt units), so half-up
 * rounding is not skewed by binary floating point (99.985 rounds to 99.99).
 */
function qualityScore(findings) {
  // penalty = penaltyUnits / 10^scale
  let penaltyUnits = 0n;
  let scale = 0;
  for (const finding of findings) {
    const severity = String(finding.severity || 'minor').toLowerCase();
    const weight = Object.hasOwn(SEVERITY_PENALTY, severity)
      ? SEVERITY_PENALTY[severity]
      : SEVERITY_PENALTY.minor;
    const raw = Number.isFinite(finding.confidence) ? finding.confidence : 0;
    const confidence = decimalOf(Math.min(Math.max(raw, 0), 1));
    if (confidence.scale > scale) {
      penaltyUnits *= 10n ** BigInt(confidence.scale - scale);
      scale = confidence.scale;
    }
    penaltyUnits +=
      BigInt(weight) * confidence.units * 10n ** BigInt(scale - confidence.scale);
  }
  let units = 100n * 10n ** BigInt(scale) - penaltyUnits;
  if (units < 0n) units = 0n;
  if (scale <= 2) return Number(units * 10n ** BigInt(2 - scale)) / 100;
  const divisor = 10n ** BigInt(scale - 2);
  let hundredths = units / divisor;
  if ((units % divisor) * 2n >= divisor) hundredths += 1n;
  return Number(hundredths) / 100;
}

/** Exact decimal value of a non-negative finite number: units / 10^scale. */
function decimalOf(value) {
  const [mantissa, exponent = '0'] = String(value).split('e');
  const [whole, fraction = ''] = mantissa.split('.');
  const scale = fraction.length - Number(exponent);
  const units = BigInt(whole + fraction);
  return scale >= 0 ? { units, scale } : { units: units * 10n ** BigInt(-scale), scale: 0 };
}

async function postScan(fetchApi, { apiUrl, apiKey, timeoutMs, body }) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetchApi(`${apiUrl}/api/scan/file`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new Error(
        `automated-code-review-tool API returned HTTP ${response.status} for ${body.filePath}: ${text.slice(0, 500)}`,
      );
    }
    const result = await response.json();
    return Array.isArray(result.findings) ? result.findings : [];
  } finally {
    clearTimeout(timeoutId);
  }
}

/** Runs fn over items with at most `limit` calls in flight; stops starting new calls after a failure. */
async function mapWithConcurrency(items, limit, fn) {
  const results = new Array(items.length);
  let next = 0;
  let failed = false;
  async function worker() {
    while (!failed && next < items.length) {
      const index = next++;
      try {
        results[index] = await fn(items[index]);
      } catch (err) {
        failed = true;
        throw err;
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

function dedupeFindings(findings) {
  const seen = new Set();
  return findings.filter((finding) => {
    const key = [
      finding.antiPattern || '',
      finding.filePath || '',
      finding.lineStart || '',
      finding.lineEnd || '',
      finding.explanation || '',
    ].join('\u0000');
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  void run();
}

export {
  addedLineNumbers,
  dedupeFindings,
  languageForPath,
  qualityScore,
  run,
  touchesAddedLines,
};

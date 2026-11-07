// Tests for the automated-code-review-tool GitHub Action. `run` is imported
// directly and exercised with stubbed @actions/core, @actions/github and fetch.
//
// Run with: `npm test`

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');

test('action.yml declares required fields and inputs', () => {
  const txt = fs.readFileSync(path.join(ROOT, 'action.yml'), 'utf8');
  for (const required of ['name:', 'description:', 'inputs:', 'runs:']) {
    assert.ok(txt.includes(required), `action.yml must contain "${required}"`);
  }
  for (const input of ['api-url:', 'api-key:', 'github-token:', 'max-files:']) {
    assert.ok(txt.includes(input), `action.yml must declare input "${input}"`);
  }
});

test('package.json declares required dependencies', () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'));
  assert.ok(pkg.dependencies['@actions/core'], 'must depend on @actions/core');
  assert.ok(pkg.dependencies['@actions/github'], 'must depend on @actions/github');
  assert.equal(pkg.main, 'index.js', 'main must be index.js');
});

test('addedLineNumbers returns new-file lines added by each hunk', async () => {
  const { addedLineNumbers } = await import('../index.js');
  const patch = [
    '@@ -1,4 +1,5 @@',
    ' import os',
    '-x = 1',
    '+x = 2',
    '+y = 3',
    ' ',
    ' def f():',
    '@@ -20,3 +21,3 @@ def g():',
    '     a = 1',
    '-    return a',
    '+    return a + 1',
    '\\ No newline at end of file',
  ].join('\n');

  assert.deepEqual([...addedLineNumbers(patch)], [2, 3, 22]);
});

test('addedLineNumbers handles hunk headers without counts and pure deletions', async () => {
  const { addedLineNumbers } = await import('../index.js');
  assert.deepEqual([...addedLineNumbers('@@ -0,0 +1 @@\n+only line')], [1]);
  assert.equal(addedLineNumbers('@@ -3,2 +2,0 @@\n-gone\n-also gone').size, 0);
});

test('touchesAddedLines keeps only findings whose range includes an added line', async () => {
  const { touchesAddedLines } = await import('../index.js');
  const added = new Set([5, 9]);
  assert.equal(touchesAddedLines({ lineStart: 5, lineEnd: 5 }, added), true);
  assert.equal(touchesAddedLines({ lineStart: 3, lineEnd: 6 }, added), true);
  assert.equal(touchesAddedLines({ lineStart: 9, lineEnd: null }, added), true);
  assert.equal(touchesAddedLines({ lineStart: 6, lineEnd: 8 }, added), false);
  assert.equal(touchesAddedLines({ lineStart: 1, lineEnd: 1 }, added), false);
  assert.equal(touchesAddedLines({ lineStart: null, lineEnd: null }, added), false);
});

test('languageForPath maps supported extensions and rejects others', async () => {
  const { languageForPath } = await import('../index.js');
  assert.equal(languageForPath('src/app.py'), 'python');
  assert.equal(languageForPath('web/App.TSX'), 'javascript');
  assert.equal(languageForPath('lib/index.mjs'), 'javascript');
  assert.equal(languageForPath('Main.java'), 'java');
  assert.equal(languageForPath('README.md'), undefined);
  assert.equal(languageForPath('Makefile'), undefined);
});

test('qualityScore matches the shared contract fixture', async () => {
  const { qualityScore } = await import('../index.js');
  const fixture = JSON.parse(
    fs.readFileSync(path.join(ROOT, '..', 'contracts', 'quality_score_cases.json'), 'utf8'),
  );
  for (const testCase of fixture.cases) {
    assert.equal(qualityScore(testCase.findings), testCase.expectedScore, testCase.name);
  }
});

test('qualityScore weighs unknown severities as minor, clamps confidence and rounds half-up', async () => {
  const { qualityScore } = await import('../index.js');
  assert.equal(qualityScore([{ severity: 'blocker', confidence: 1 }]), 97);
  assert.equal(qualityScore([{ severity: '', confidence: 1 }]), 97);
  assert.equal(qualityScore([{ severity: 'MAJOR', confidence: 1.5 }]), 90);
  assert.equal(qualityScore([{ severity: 'critical', confidence: -0.5 }]), 100);
  assert.equal(qualityScore([{ severity: 'critical', confidence: 1e-7 }]), 100);
  // 100 - 3 * 0.005 = 99.985 exactly; binary floating point would give 99.98.
  assert.equal(qualityScore([{ severity: 'minor', confidence: 0.005 }]), 99.99);
});

test('dedupeFindings drops repeated findings and keeps distinct locations', async () => {
  const { dedupeFindings } = await import('../index.js');
  const base = {
    antiPattern: 'RELIABILITY_BROAD_EXCEPTION',
    filePath: 'a.py',
    lineStart: 3,
    lineEnd: 3,
    explanation: 'Broad exception handler.',
  };
  const deduped = dedupeFindings([base, { ...base }, { ...base, lineStart: 10, lineEnd: 10 }]);
  assert.deepEqual(deduped.map((finding) => finding.lineStart), [3, 10]);
});

function actionHarness({ inputs = {}, files = [], contents = {} } = {}) {
  const calls = {
    failed: [],
    outputs: {},
    warnings: [],
    errors: [],
    notices: [],
    getContent: [],
  };
  const allInputs = {
    'api-url': 'https://localhost:8080',
    'api-key': 'cl_live_test',
    'github-token': 'github-token-test',
    'fail-threshold': '60',
    'fetch-timeout-ms': '30000',
    'max-files': '50',
    ...inputs,
  };
  const core = {
    getInput: (name) => allInputs[name] || '',
    setFailed: (message) => calls.failed.push(message),
    setOutput: (name, value) => { calls.outputs[name] = value; },
    info: () => {},
    warning: (...args) => calls.warnings.push(args),
    error: (...args) => calls.errors.push(args),
    notice: (...args) => calls.notices.push(args),
    setSecret: () => {},
  };
  calls.listFiles = [];
  const github = {
    context: {
      eventName: 'pull_request',
      payload: { pull_request: { number: 42, head: { sha: 'head-sha' } } },
      repo: { owner: 'tanmay-alpha', repo: 'automated-code-review-tool' },
    },
    getOctokit: (token) => {
      calls.githubToken = token;
      return {
        rest: {
          pulls: {
            listFiles: async (params) => {
              calls.listFiles.push(params);
              assert.ok(params.request.signal instanceof AbortSignal, 'listFiles must carry a timeout signal');
              const start = (params.page - 1) * params.per_page;
              return { data: files.slice(start, start + params.per_page) };
            },
          },
          repos: {
            getContent: async (params) => {
              calls.getContent.push(params);
              assert.ok(params.request.signal instanceof AbortSignal, 'getContent must carry a timeout signal');
              return {
                data: {
                  type: 'file',
                  encoding: 'base64',
                  content: Buffer.from(contents[params.path]).toString('base64'),
                },
              };
            },
          },
        },
      };
    },
  };
  return { calls, core, github };
}

function jsonResponse(body) {
  return { ok: true, json: async () => body };
}

test('action scans changed files and annotates findings on added lines', async () => {
  const { run } = await import('../index.js');
  const harness = actionHarness({
    inputs: { 'fail-threshold': '90' },
    files: [
      { filename: 'src/a.py', status: 'modified', patch: '@@ -1,2 +1,3 @@\n import os\n+password = "hunter2"\n x = 1' },
      { filename: 'web/b.ts', status: 'added', patch: '@@ -0,0 +1 @@\n+console.log(1)' },
      { filename: 'docs/c.md', status: 'modified', patch: '@@ -1 +1 @@\n-a\n+b' },
      { filename: 'old.py', status: 'removed', patch: '@@ -1 +0,0 @@\n-x = 1' },
      { filename: 'logo.png', status: 'added' },
    ],
    contents: {
      'src/a.py': 'import os\npassword = "hunter2"\nx = 1\n',
      'web/b.ts': 'console.log(1)\n',
    },
  });
  const requests = [];
  const fetch = async (url, init) => {
    const body = JSON.parse(init.body);
    requests.push({ url, init, body });
    if (body.filePath === 'src/a.py') {
      return jsonResponse({
        qualityScore: 0,
        findings: [
          // Line 1 is unchanged context, so this finding must be dropped.
          { antiPattern: 'READABILITY_MAGIC_NUMBER', severity: 'minor', confidence: 1, lineStart: 1, lineEnd: 1, explanation: 'Magic.' },
          { antiPattern: 'SECURITY_HARDCODED_SECRET', severity: 'critical', confidence: 1, lineStart: 2, lineEnd: 2, explanation: 'Secret.' },
        ],
      });
    }
    return jsonResponse({ qualityScore: 100, findings: [] });
  };

  await run({ ...harness, fetch });

  assert.equal(harness.calls.githubToken, 'github-token-test');
  assert.deepEqual(harness.calls.listFiles.map((params) => [params.pull_number, params.page]), [[42, 1]]);
  assert.deepEqual(
    harness.calls.getContent.map((params) => [params.path, params.ref]),
    [['src/a.py', 'head-sha'], ['web/b.ts', 'head-sha']],
  );
  assert.deepEqual(
    requests.map((request) => request.body).sort((a, b) => a.filePath.localeCompare(b.filePath)),
    [
      { content: 'import os\npassword = "hunter2"\nx = 1\n', language: 'python', filePath: 'src/a.py' },
      { content: 'console.log(1)\n', language: 'javascript', filePath: 'web/b.ts' },
    ],
  );
  for (const request of requests) {
    assert.equal(request.url, 'https://localhost:8080/api/scan/file');
    assert.equal(request.init.headers.Authorization, 'Bearer cl_live_test');
    assert.ok(request.init.signal instanceof AbortSignal, 'fetch must carry an AbortSignal for the timeout');
  }

  assert.equal(harness.calls.errors.length, 1);
  assert.deepEqual(harness.calls.errors[0][1], {
    file: 'src/a.py',
    startLine: 2,
    endLine: 2,
    title: 'SECURITY_HARDCODED_SECRET (critical)',
  });
  assert.equal(harness.calls.notices.length, 0);
  assert.equal(harness.calls.outputs['findings-count'], '1');
  assert.equal(harness.calls.outputs['critical-count'], '1');
  assert.equal(harness.calls.outputs['quality-score'], '80');
  assert.deepEqual(harness.calls.failed, ['Quality score 80/100 is below threshold 90/100']);
});

test('action pages through listFiles until a short page', async () => {
  const { run } = await import('../index.js');
  const files = [];
  for (let i = 0; i < 100; i++) {
    files.push({ filename: `docs/${i}.md`, status: 'added', patch: '@@ -0,0 +1 @@\n+text' });
  }
  files.push({ filename: 'late.py', status: 'added', patch: '@@ -0,0 +1 @@\n+x = 1' });
  const harness = actionHarness({ files, contents: { 'late.py': 'x = 1\n' } });
  const scanned = [];
  const fetch = async (_url, init) => {
    scanned.push(JSON.parse(init.body).filePath);
    return jsonResponse({ findings: [] });
  };

  await run({ ...harness, fetch });

  assert.deepEqual(harness.calls.listFiles.map((params) => params.page), [1, 2]);
  assert.deepEqual(scanned, ['late.py']);
  assert.deepEqual(harness.calls.failed, []);
});

test('action honours max-files and runs at most four scans at once', async () => {
  const { run } = await import('../index.js');
  const files = [];
  const contents = {};
  for (let i = 0; i < 10; i++) {
    files.push({ filename: `f${i}.py`, status: 'added', patch: '@@ -0,0 +1 @@\n+x = 1' });
    contents[`f${i}.py`] = 'x = 1\n';
  }
  const harness = actionHarness({ inputs: { 'max-files': '8' }, files, contents });
  let inFlight = 0;
  let maxInFlight = 0;
  let scanned = 0;
  const fetch = async () => {
    inFlight++;
    maxInFlight = Math.max(maxInFlight, inFlight);
    await new Promise((resolve) => setTimeout(resolve, 5));
    inFlight--;
    scanned++;
    return jsonResponse({ findings: [] });
  };

  await run({ ...harness, fetch });

  assert.equal(scanned, 8);
  assert.equal(maxInFlight, 4);
  assert.match(harness.calls.warnings[0][0], /first 8 of 10 eligible files/);
  assert.equal(harness.calls.outputs['quality-score'], '100');
  assert.deepEqual(harness.calls.failed, []);
});

test('action reports API errors with the file path', async () => {
  const { run } = await import('../index.js');
  const harness = actionHarness({
    files: [{ filename: 'a.py', status: 'added', patch: '@@ -0,0 +1 @@\n+x = 1' }],
    contents: { 'a.py': 'x = 1\n' },
  });
  const fetch = async () => ({ ok: false, status: 503, text: async () => 'down' });

  await run({ ...harness, fetch });

  assert.deepEqual(harness.calls.failed, [
    'automated-code-review-tool API returned HTTP 503 for a.py: down',
  ]);
});

test('action skips the API when no supported file adds lines', async () => {
  const { run } = await import('../index.js');
  const harness = actionHarness({
    files: [{ filename: 'README.md', status: 'modified', patch: '@@ -1 +1 @@\n-a\n+b' }],
  });

  await run({ ...harness, fetch: async () => { throw new Error('unexpected fetch'); } });

  assert.deepEqual(harness.calls.failed, []);
  assert.equal(harness.calls.outputs['quality-score'], '');
  assert.equal(harness.calls.outputs['findings-count'], '0');
});

test('action requires an explicit GitHub token', async () => {
  const { run } = await import('../index.js');
  const harness = actionHarness({ inputs: { 'github-token': '' } });

  await run({ ...harness, fetch: async () => { throw new Error('unexpected fetch'); } });

  assert.match(harness.calls.failed[0], /github-token/);
});

test('action rejects plain-http api-url (HTTPS only)', async () => {
  const { run } = await import('../index.js');
  const harness = actionHarness({ inputs: { 'api-url': 'http://insecure.example.com' } });
  let fetched = false;
  const fetch = async () => { fetched = true; return jsonResponse({}); };

  await run({ ...harness, fetch });

  assert.equal(fetched, false, 'fetch must NOT be called when api-url is plain http');
  assert.match(harness.calls.failed[0], /https:\/\//);
});

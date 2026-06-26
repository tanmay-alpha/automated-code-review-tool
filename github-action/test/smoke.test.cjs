// Lightweight smoke test for the CodeLens GitHub Action.
// Verifies that the action's source files are well-formed and that the
// declared inputs / outputs are present.
//
// Run with: `npm test`

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');

test('index.js is a valid module (syntax check only)', () => {
  // We can't `require` index.js directly because it auto-invokes run()
  // on import and would attempt to read GITHUB_* env vars. Instead, use
  // Node's built-in `--check` semantics by spawning `node --check`,
  // which is safe and never executes the source.
  const { execFileSync } = require('node:child_process');
  execFileSync(process.execPath, ['--check', path.join(ROOT, 'index.js')], {
    stdio: 'pipe',
  });
});

test('action.yml is well-formed YAML with required fields', () => {
  const p = path.join(ROOT, 'action.yml');
  const txt = fs.readFileSync(p, 'utf8');
  assert.ok(txt.length > 0, 'action.yml must not be empty');
  for (const required of ['name:', 'description:', 'inputs:', 'runs:']) {
    assert.ok(
      txt.includes(required),
      `action.yml must contain "${required}"`,
    );
  }
  // Required inputs (api-url, api-key) must be declared.
  for (const requiredInput of ['api-url:', 'api-key:']) {
    assert.ok(
      txt.includes(requiredInput),
      `action.yml must declare input "${requiredInput}"`,
    );
  }
});

test('package.json declares required dependencies', () => {
  const pkg = JSON.parse(
    fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8'),
  );
  assert.ok(pkg.dependencies, 'package.json must have a "dependencies" field');
  assert.ok(
    pkg.dependencies['@actions/core'],
    'must depend on @actions/core',
  );
  assert.ok(
    pkg.dependencies['@actions/github'],
    'must depend on @actions/github',
  );
  assert.equal(pkg.main, 'index.js', 'main must be index.js');
});

function actionHarness(overrides = {}) {
  const calls = { failed: [], outputs: {}, warnings: [], errors: [], notices: [] };
  const inputs = {
    'api-url': 'http://localhost:8080',
    'api-key': 'cl_live_test',
    language: 'python',
    'fail-threshold': '60',
    ...overrides.inputs,
  };
  const core = {
    getInput: (name) => inputs[name] || '',
    setFailed: (message) => calls.failed.push(message),
    setOutput: (name, value) => { calls.outputs[name] = value; },
    info: () => {},
    warning: (...args) => calls.warnings.push(args),
    error: (...args) => calls.errors.push(args),
    notice: (...args) => calls.notices.push(args),
  };
  const github = {
    context: {
      eventName: 'pull_request',
      payload: { pull_request: { number: 42 } },
      repo: { owner: 'tanmay-alpha', repo: 'codelens' },
    },
    getOctokit: () => ({
      rest: { pulls: { get: async () => ({ data: 'diff --git a/a.py b/a.py\n+x = 1' }) } },
    }),
  };
  return { calls, core, github };
}

test('action submits the PR diff to the file scan endpoint', async () => {
  const { run } = await import('../index.js');
  const harness = actionHarness();
  let request;
  const fetch = async (url, init) => {
    request = { url, init };
    return {
      ok: true,
      json: async () => ({ findings: [], qualityScore: 100 }),
    };
  };

  await run({ ...harness, fetch });

  assert.equal(request.url, 'http://localhost:8080/api/scan/file');
  assert.deepEqual(JSON.parse(request.init.body), {
    content: 'diff --git a/a.py b/a.py\n+x = 1',
    language: 'python',
    filePath: 'tanmay-alpha/codelens#42',
  });
  assert.equal(request.init.headers.Authorization, 'Bearer cl_live_test');
  assert.deepEqual(harness.calls.failed, []);
  assert.equal(harness.calls.outputs['quality-score'], '100');
});

test('action annotates findings and fails a score below threshold', async () => {
  const { run } = await import('../index.js');
  const harness = actionHarness();
  const fetch = async () => ({
    ok: true,
    json: async () => ({
      qualityScore: 40,
      findings: [{
        antiPattern: 'PERFORMANCE_N_PLUS_1',
        severity: 'critical',
        confidence: 0.91,
        explanation: 'Query inside loop',
        filePath: 'a.py',
        lineStart: 5,
        lineEnd: 6,
      }],
    }),
  });

  await run({ ...harness, fetch });

  assert.equal(harness.calls.errors.length, 1);
  assert.equal(harness.calls.outputs['findings-count'], '1');
  assert.equal(harness.calls.outputs['critical-count'], '1');
  assert.match(harness.calls.failed[0], /below threshold 60/);
});

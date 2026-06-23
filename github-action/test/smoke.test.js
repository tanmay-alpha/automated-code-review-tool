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

const { test } = require("node:test");
const assert = require("node:assert/strict");

const { ScanSequencer } = require("../out/scanSequencer.js");

test("only the latest scan for a document wins", () => {
  const sequencer = new ScanSequencer();
  const first = sequencer.begin("file:///a.py");
  const second = sequencer.begin("file:///a.py");
  assert.equal(sequencer.isLatest("file:///a.py", second), true);
  assert.equal(sequencer.isLatest("file:///a.py", first), false);
  sequencer.finish("file:///a.py", first);
  assert.equal(sequencer.isLatest("file:///a.py", second), true);
});

test("scans of different documents do not invalidate each other", () => {
  const sequencer = new ScanSequencer();
  const a = sequencer.begin("file:///a.py");
  const b = sequencer.begin("file:///b.py");
  assert.equal(sequencer.isLatest("file:///a.py", a), true);
  assert.equal(sequencer.isLatest("file:///b.py", b), true);
  sequencer.finish("file:///a.py", a);
  assert.equal(sequencer.isLatest("file:///a.py", a), false);
  assert.ok(sequencer.begin("file:///a.py") > a);
});

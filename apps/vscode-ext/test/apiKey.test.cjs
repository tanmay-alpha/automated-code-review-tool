const { test } = require("node:test");
const assert = require("node:assert/strict");

const { resolveApiKey } = require("../out/apiKey.js");

test("prefers the SecretStorage key over the deprecated setting", () => {
  assert.deepEqual(resolveApiKey("secret-key", "legacy-key"), {
    key: "secret-key",
    source: "secretStorage",
  });
});

test("falls back to the deprecated setting when no secret is stored", () => {
  assert.deepEqual(resolveApiKey(undefined, " legacy-key "), {
    key: "legacy-key",
    source: "legacySetting",
  });
  assert.deepEqual(resolveApiKey("   ", "legacy-key"), {
    key: "legacy-key",
    source: "legacySetting",
  });
});

test("reports no key when neither source is set", () => {
  assert.deepEqual(resolveApiKey(undefined, undefined), { key: "", source: "none" });
  assert.deepEqual(resolveApiKey("", "  "), { key: "", source: "none" });
});

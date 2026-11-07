const { test } = require("node:test");
const assert = require("node:assert/strict");
const Module = require("node:module");

function loadConfig({ existingSecret, globalKey, workspaceKey, folderKeys = [], failStore = false } = {}) {
  const state = { secret: existingSecret, globalKey, workspaceKey, folderKeys: [...folderKeys], failStore };
  const folders = folderKeys.map((_, index) => ({ uri: { index } }));
  const vscode = {
    ConfigurationTarget: { Global: 1, Workspace: 2, WorkspaceFolder: 3 },
    workspace: {
      workspaceFolders: folders,
      getConfiguration: (_namespace, resource) => ({
        get: () => state.folderKeys[resource?.index] ?? state.workspaceKey ?? state.globalKey,
        inspect: () => ({
          globalValue: state.globalKey, workspaceValue: state.workspaceKey,
          workspaceFolderValue: resource === undefined ? undefined : state.folderKeys[resource.index],
        }),
        update: async (_name, value, target) => {
          if (target === 1) state.globalKey = value;
          else if (target === 2) state.workspaceKey = value;
          else state.folderKeys[resource.index] = value;
        },
      }),
    },
    window: {
      showWarningMessage: async () => "Move to secure storage",
      showInformationMessage: () => Promise.resolve(),
    },
  };
  const filename = require.resolve("../out/config.js");
  delete require.cache[filename];
  const originalLoad = Module._load;
  Module._load = function (name, ...args) {
    return name === "vscode" ? vscode : originalLoad.call(this, name, ...args);
  };
  let config;
  try { config = require(filename); } finally { Module._load = originalLoad; }
  const context = {
    secrets: {
      get: async () => state.secret,
      store: async (_name, key) => {
        if (state.failStore) throw new Error("keychain unavailable");
        state.secret = key;
      },
    },
  };
  return { config, context, state };
}

test("migration secures the effective key and removes every plaintext scope", async () => {
  const { config, context, state } = loadConfig({
    globalKey: "user-key", workspaceKey: "workspace-key", folderKeys: ["folder-a", "folder-b"],
  });
  await config.offerApiKeyMigration(context);
  assert.equal(state.secret, "workspace-key");
  assert.equal(state.globalKey, undefined);
  assert.equal(state.workspaceKey, undefined);
  assert.deepEqual(state.folderKeys, [undefined, undefined]);
});

test("migration detects folder-only plaintext without replacing an existing secret", async () => {
  const { config, context, state } = loadConfig({ existingSecret: "secure-key", folderKeys: ["old-key"] });
  await config.offerApiKeyMigration(context);
  assert.equal(state.secret, "secure-key");
  assert.deepEqual(state.folderKeys, [undefined]);
});

test("failed secret storage preserves plaintext and allows migration to be retried", async () => {
  const { config, context, state } = loadConfig({ globalKey: "old-key", failStore: true });
  await assert.rejects(config.offerApiKeyMigration(context), /keychain unavailable/);
  assert.equal(state.globalKey, "old-key");
  state.failStore = false;
  await config.offerApiKeyMigration(context);
  assert.equal(state.secret, "old-key");
  assert.equal(state.globalKey, undefined);
});

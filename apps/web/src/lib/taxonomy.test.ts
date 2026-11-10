// @vitest-environment node
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { TAXONOMY_RULES, ruleDisplayName } from "@/lib/taxonomy";

const YAML_PATH = path.resolve(__dirname, "../../../../taxonomy/anti_patterns.yaml");

/** Minimal reader for the flat `- id:` / `key: value` entries of the taxonomy file. */
function readTaxonomy(): { id: string; displayName: string; category: string; defaultSeverity: string }[] {
  const text = fs.readFileSync(YAML_PATH, "utf8");
  const entries: Record<string, string>[] = [];
  for (const line of text.split(/\r?\n/)) {
    const start = /^ {2}- id:\s*(\S+)\s*$/.exec(line);
    if (start) {
      entries.push({ id: start[1] });
      continue;
    }
    const field = /^ {4}(display_name|category|default_severity):\s*"?([^"]*?)"?\s*$/.exec(line);
    if (field && entries.length > 0) entries[entries.length - 1][field[1]] = field[2];
  }
  return entries.map((e) => ({
    id: e.id,
    displayName: e.display_name,
    category: e.category,
    defaultSeverity: e.default_severity,
  }));
}

describe("taxonomy mirror", () => {
  it("matches taxonomy/anti_patterns.yaml exactly and in order", () => {
    expect(TAXONOMY_RULES).toEqual(readTaxonomy());
  });

  it("falls back to a readable name for unknown IDs", () => {
    expect(ruleDisplayName("SECURITY_SQL_INJECTION")).toBe("SQL Injection");
    expect(ruleDisplayName("CUSTOM_NEW_RULE")).toBe("Custom New Rule");
  });
});

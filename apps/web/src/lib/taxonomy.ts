// Rule IDs from taxonomy/anti_patterns.yaml, used by the repository settings
// form to choose `enabledRules`. The API has no taxonomy endpoint, so the list
// is mirrored here; `taxonomy.test.ts` fails if it drifts from the YAML.

import type { ReviewLanguage, SeverityLevel } from "@/lib/types";

export interface TaxonomyRule {
  id: string;
  displayName: string;
  category: string;
  defaultSeverity: SeverityLevel;
}

export const TAXONOMY_RULES: readonly TaxonomyRule[] = [
  { id: "SECURITY_HARDCODED_SECRET", displayName: "Hardcoded Secret", category: "SECURITY", defaultSeverity: "critical" },
  { id: "SECURITY_SQL_INJECTION", displayName: "SQL Injection", category: "SECURITY", defaultSeverity: "major" },
  { id: "SECURITY_WEAK_CRYPTO", displayName: "Weak Cryptography", category: "SECURITY", defaultSeverity: "major" },
  { id: "PERFORMANCE_N_PLUS_ONE", displayName: "N+1 Query", category: "PERFORMANCE", defaultSeverity: "major" },
  { id: "PERFORMANCE_QUADRATIC_LOOP", displayName: "Quadratic Loop", category: "PERFORMANCE", defaultSeverity: "major" },
  { id: "RELIABILITY_BROAD_EXCEPTION", displayName: "Broad Exception", category: "RELIABILITY", defaultSeverity: "major" },
  { id: "RELIABILITY_MISSING_TIMEOUT", displayName: "Missing Timeout", category: "RELIABILITY", defaultSeverity: "minor" },
  { id: "READABILITY_MAGIC_NUMBER", displayName: "Magic Number", category: "READABILITY", defaultSeverity: "minor" },
  { id: "READABILITY_LONG_METHOD", displayName: "Long Statement", category: "READABILITY", defaultSeverity: "minor" },
  { id: "MAINTAINABILITY_DUPLICATE_CODE", displayName: "Duplicate Code", category: "MAINTAINABILITY", defaultSeverity: "minor" },
  { id: "MAINTAINABILITY_COMMENTED_CODE", displayName: "Commented-Out Code", category: "MAINTAINABILITY", defaultSeverity: "minor" },
  { id: "MAINTAINABILITY_PRINT_STATEMENT", displayName: "Print Statement", category: "MAINTAINABILITY", defaultSeverity: "minor" },
];

/** Display name for a rule ID; unknown IDs fall back to a title-cased ID. */
export function ruleDisplayName(id: string): string {
  const rule = TAXONOMY_RULES.find((r) => r.id === id);
  if (rule) return rule.displayName;
  return id
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map((w) => w[0].toUpperCase() + w.slice(1))
    .join(" ");
}

export const SEVERITY_LEVELS: readonly SeverityLevel[] = ["minor", "major", "critical"];

export const REVIEW_LANGUAGES: readonly { id: ReviewLanguage; label: string }[] = [
  { id: "python", label: "Python" },
  { id: "javascript", label: "JavaScript" },
  { id: "typescript", label: "TypeScript" },
  { id: "java", label: "Java" },
];

import { BookOpen } from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { severityClasses } from "@/lib/utils";

/**
 * Human-readable mirror of taxonomy/anti_patterns.yaml for the dashboard.
 * The root YAML remains authoritative for inference, training, and persistence.
 */
type Severity = "critical" | "major" | "minor";
interface AntiPattern {
  category: string;
  id: string;
  name: string;
  severity: Severity;
  catches: string;
  example: { language: "python" | "javascript" | "java"; code: string };
}

const PATTERNS: AntiPattern[] = [
  {
    category: "Performance",
    id: "PERFORMANCE_N_PLUS_ONE",
    name: "N+1 Query",
    severity: "major",
    catches:
      "Loop performs a separate DB / API call per item instead of batching.",
    example: {
      language: "python",
      code: `for user_id in user_ids:\n    user = db.query("SELECT * FROM users WHERE id = ?", user_id)`,
    },
  },
  {
    category: "Performance",
    id: "PERFORMANCE_QUADRATIC_LOOP",
    name: "Quadratic Loop",
    severity: "major",
    catches: "Nested iteration creates O(n²) or worse work.",
    example: {
      language: "javascript",
      code: `for (let i = 0; i < arr.length; i++) {\n  arr[i] = expensiveLookup(arr[i]);\n}`,
    },
  },
  {
    category: "Reliability",
    id: "RELIABILITY_BROAD_EXCEPTION",
    name: "Broad Exception",
    severity: "major",
    catches:
      "catch block silently absorbs the error — no log, no rethrow, no metric.",
    example: {
      language: "java",
      code: `try {\n    openFile(path);\n} catch (IOException e) {\n    // ignore\n}`,
    },
  },
  {
    category: "Reliability",
    id: "RELIABILITY_MISSING_TIMEOUT",
    name: "Missing Timeout",
    severity: "minor",
    catches: "An external call can block indefinitely because no timeout is set.",
    example: {
      language: "python",
      code: `response = requests.get(url)  # no timeout`,
    },
  },
  {
    category: "Security",
    id: "SECURITY_WEAK_CRYPTO",
    name: "Weak Cryptography",
    severity: "major",
    catches: "Code uses a weak or broken cryptographic primitive.",
    example: {
      language: "java",
      code: `MessageDigest digest = MessageDigest.getInstance("MD5");`,
    },
  },
  {
    category: "Security",
    id: "SECURITY_SQL_INJECTION",
    name: "SQL Injection",
    severity: "major",
    catches: "User input concatenated into a SQL string.",
    example: {
      language: "python",
      code: `db.execute("SELECT * FROM users WHERE name = '" + name + "'")`,
    },
  },
  {
    category: "Readability",
    id: "READABILITY_MAGIC_NUMBER",
    name: "Magic Number",
    severity: "minor",
    catches: "An unexplained numeric literal obscures the code's intent.",
    example: {
      language: "javascript",
      code: `if (attempts > 7) { retryLater(); }`,
    },
  },
  {
    category: "Security",
    id: "SECURITY_HARDCODED_SECRET",
    name: "Hardcoded Secret",
    severity: "critical",
    catches: "API key / password / token committed to the repo.",
    example: {
      language: "python",
      code: `API_KEY = "sk-live-abc123def456"`,
    },
  },
  {
    category: "Readability",
    id: "READABILITY_LONG_METHOD",
    name: "Long Statement",
    severity: "minor",
    catches: "A very long statement should be split into readable steps.",
    example: {
      language: "java",
      code: `const result = users.filter(active).map(normalize).sort(compare).reduce(group, {});`,
    },
  },
  {
    category: "Maintainability",
    id: "MAINTAINABILITY_DUPLICATE_CODE",
    name: "Duplicate Code",
    severity: "minor",
    catches: "Near-identical blocks repeated across functions / files.",
    example: {
      language: "javascript",
      code: `function a(x){return x.map(v=>v*2).filter(v=>v>0)}\nfunction b(x){return x.map(v=>v*2).filter(v=>v>0)}`,
    },
  },
  {
    category: "Maintainability",
    id: "MAINTAINABILITY_COMMENTED_CODE",
    name: "Commented-Out Code",
    severity: "minor",
    catches: "A block of obsolete source code remains commented out.",
    example: {
      language: "python",
      code: `# old_total = subtotal + legacy_tax\n# return old_total`,
    },
  },
  {
    category: "Maintainability",
    id: "MAINTAINABILITY_PRINT_STATEMENT",
    name: "Print Statement",
    severity: "minor",
    catches: "A bare print or console statement remains in production code.",
    example: {
      language: "javascript",
      code: `console.log("request payload", payload);`,
    },
  },
];

function SeverityPill({ s }: { s: Severity }) {
  return (
    <Badge variant="outline" className={`border ${severityClasses(s)}`}>
      {s.toUpperCase()}
    </Badge>
  );
}

function TaxonomyContent() {
  return (
    <div className="mx-auto max-w-6xl space-y-6 p-8">
      <header>
        <div className="flex items-center gap-2">
          <BookOpen className="h-5 w-5 text-muted-foreground" />
          <h1 className="text-3xl font-bold tracking-tight">Taxonomy</h1>
        </div>
        <p className="mt-2 text-muted-foreground">
          The {PATTERNS.length} anti-patterns in the canonical taxonomy.
          Trainable model labels and deterministic-only rules are both shown.
        </p>
      </header>

      <Separator />

      <div className="grid gap-3 md:grid-cols-3">
        {(["critical", "major", "minor"] as const).map((s) => {
          const count = PATTERNS.filter((p) => p.severity === s).length;
          return (
            <Card key={s}>
              <CardHeader className="pb-2">
                <CardDescription>Severity</CardDescription>
                <CardTitle className="text-base">
                  <SeverityPill s={s} /> · {count} pattern
                  {count === 1 ? "" : "s"}
                </CardTitle>
              </CardHeader>
            </Card>
          );
        })}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>All anti-patterns</CardTitle>
          <CardDescription>
            Click a row to expand the example.
          </CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-40">Category</TableHead>
                <TableHead>Pattern</TableHead>
                <TableHead className="w-28">Severity</TableHead>
                <TableHead>What it catches</TableHead>
                <TableHead className="w-80">Example</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {PATTERNS.map((p) => (
                <TableRow key={p.id}>
                  <TableCell className="text-muted-foreground">
                    {p.category}
                  </TableCell>
                  <TableCell>
                    <div className="font-medium">{p.name}</div>
                    <div className="font-mono text-xs text-muted-foreground">
                      {p.id}
                    </div>
                  </TableCell>
                  <TableCell>
                    <SeverityPill s={p.severity} />
                  </TableCell>
                  <TableCell className="text-sm">{p.catches}</TableCell>
                  <TableCell>
                    <pre className="overflow-x-auto rounded-md bg-muted px-2 py-1.5 text-xs">
                      <code>{p.example.code}</code>
                    </pre>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}

export default function TaxonomyPage() {
  return (
    <AuthShell>
      <TaxonomyContent />
    </AuthShell>
  );
}

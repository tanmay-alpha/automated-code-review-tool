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
 * Static page enumerating the 13 anti-patterns the ML model was trained
 * to detect. No API calls — the content is hard-coded from the plan
 * (ENGINEERING_PLAN.md.md §3 — label taxonomy).
 *
 * This page is the canonical reference for what CodeLens can catch; we
 * embed code snippets directly so the dashboard works offline and the
 * taxonomy never drifts from the model without an explicit update.
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
    id: "PERF_N_PLUS_1",
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
    id: "PERF_INEFFICIENT_LOOP",
    name: "Inefficient Loop",
    severity: "minor",
    catches:
      "Loop calls O(n) work per iteration where O(1) or batch would do.",
    example: {
      language: "javascript",
      code: `for (let i = 0; i < arr.length; i++) {\n  arr[i] = expensiveLookup(arr[i]);\n}`,
    },
  },
  {
    category: "Reliability",
    id: "RELIABILITY_SWALLOWED_EXCEPTION",
    name: "Swallowed Exception",
    severity: "critical",
    catches:
      "catch block silently absorbs the error — no log, no rethrow, no metric.",
    example: {
      language: "java",
      code: `try {\n    openFile(path);\n} catch (IOException e) {\n    // ignore\n}`,
    },
  },
  {
    category: "Reliability",
    id: "RELIABILITY_BROKEN_RACE_CONDITION",
    name: "Race Condition",
    severity: "critical",
    catches:
      "Concurrent read-modify-write without locking or atomic primitive.",
    example: {
      language: "python",
      code: `if counter.value < LIMIT:\n    counter.value += 1  # TOCTOU`,
    },
  },
  {
    category: "Reliability",
    id: "RELIABILITY_RESOURCE_LEAK",
    name: "Resource Leak",
    severity: "major",
    catches: "File / socket / cursor opened but never closed on error path.",
    example: {
      language: "java",
      code: `FileInputStream in = new FileInputStream(path);\nprocess(in);  // may throw — in not closed`,
    },
  },
  {
    category: "Security",
    id: "SECURITY_SQL_INJECTION",
    name: "SQL Injection",
    severity: "critical",
    catches: "User input concatenated into a SQL string.",
    example: {
      language: "python",
      code: `db.execute("SELECT * FROM users WHERE name = '" + name + "'")`,
    },
  },
  {
    category: "Security",
    id: "SECURITY_XSS",
    name: "Cross-Site Scripting",
    severity: "critical",
    catches: "Unescaped user input rendered as HTML.",
    example: {
      language: "javascript",
      code: `element.innerHTML = userInput;`,
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
    category: "Maintainability",
    id: "MAINTAINABILITY_GOD_CLASS",
    name: "God Class",
    severity: "major",
    catches: "Single class owns too many responsibilities (SRP violation).",
    example: {
      language: "java",
      code: `class UserService {\n  void auth() { ... }\n  void sendEmail() { ... }\n  void exportCsv() { ... }\n}`,
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
    id: "MAINTAINABILITY_LONG_METHOD",
    name: "Long Method",
    severity: "minor",
    catches: "Function exceeds ~50 lines or >3 levels of nesting.",
    example: {
      language: "python",
      code: `def process(order):\n    # ... 80 lines of validation,\n    # pricing, tax, shipping, persistence`,
    },
  },
  {
    category: "Correctness",
    id: "CORRECTNESS_OFF_BY_ONE",
    name: "Off-by-One",
    severity: "major",
    catches: "Boundary condition uses < vs <= (or vice versa) incorrectly.",
    example: {
      language: "javascript",
      code: `for (let i = 0; i <= arr.length; i++) { console.log(arr[i]); }`,
    },
  },
  {
    category: "Correctness",
    id: "CORRECTNESS_NULL_DEREF",
    name: "Null Dereference",
    severity: "major",
    catches:
      "Property access / method call on a value that may be null/undefined.",
    example: {
      language: "java",
      code: `User u = repo.find(id);\nreturn u.getName();  // u may be null`,
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
          The {PATTERNS.length} anti-patterns the CodeLens model was trained
          to detect. Severity ratings follow{" "}
          <span className="font-mono">ENGINEERING_PLAN.md.md §3</span>.
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

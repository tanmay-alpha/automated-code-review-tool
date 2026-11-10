import { BookOpen, ExternalLink } from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { TAXONOMY_RULES } from "@/lib/taxonomy";
import { titleCase } from "@/lib/utils";

const TAXONOMY_URL =
  "https://github.com/tanmay-alpha/automated-code-review-tool/blob/main/taxonomy/anti_patterns.yaml";

/**
 * Lists the rule IDs (mirrored from the canonical YAML and checked against it
 * by `lib/taxonomy.test.ts`) and links to the YAML for full descriptions.
 * AuthShell already renders the page's <main>.
 */
export default function TaxonomyPage() {
  return (
    <AuthShell>
      <div className="mx-auto max-w-4xl space-y-6 p-4 md:p-8">
        <header className="flex items-center gap-2">
          <BookOpen className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
          <h1 className="text-2xl font-bold tracking-tight md:text-3xl">Taxonomy</h1>
        </header>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg">
              <h2>Canonical anti-pattern definitions</h2>
            </CardTitle>
            <CardDescription>
              The anti-pattern IDs, categories and default severities live
              in one YAML file. The ML worker loads and caches it, and the
              API&apos;s database migration seeds the same IDs. Repository
              settings choose from these rules.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="rounded-lg border">
              <Table scrollLabel="Anti-pattern rules">
                <TableHeader>
                  <TableRow>
                    <TableHead>Rule</TableHead>
                    <TableHead>ID</TableHead>
                    <TableHead>Category</TableHead>
                    <TableHead>Default severity</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {TAXONOMY_RULES.map((rule) => (
                    <TableRow key={rule.id}>
                      <TableCell className="font-medium">{rule.displayName}</TableCell>
                      <TableCell className="font-mono text-xs">{rule.id}</TableCell>
                      <TableCell>{titleCase(rule.category)}</TableCell>
                      <TableCell>{titleCase(rule.defaultSeverity)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <a
              className="inline-flex items-center gap-2 text-sm font-medium text-primary underline underline-offset-4"
              href={TAXONOMY_URL}
              target="_blank"
              rel="noreferrer"
            >
              View anti_patterns.yaml <ExternalLink className="h-4 w-4" aria-hidden="true" />
              <span className="sr-only">(opens GitHub in a new tab)</span>
            </a>
          </CardContent>
        </Card>
      </div>
    </AuthShell>
  );
}

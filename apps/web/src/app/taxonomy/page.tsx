import { BookOpen, ExternalLink } from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

const TAXONOMY_URL =
  "https://github.com/tanmay-alpha/automated-code-review-tool/blob/main/taxonomy/anti_patterns.yaml";

/** The canonical taxonomy is YAML, not a hand-maintained frontend copy. */
export default function TaxonomyPage() {
  return (
    <AuthShell>
      <main className="mx-auto max-w-3xl space-y-6 p-8">
        <header className="flex items-center gap-2">
          <BookOpen className="h-5 w-5 text-muted-foreground" />
          <h1 className="text-3xl font-bold tracking-tight">Taxonomy</h1>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>Canonical anti-pattern definitions</CardTitle>
            <CardDescription>
              The shared YAML taxonomy is the sole source for training,
              fallback detection, checkpoint validation, and persistence.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <a
              className="inline-flex items-center gap-2 text-sm font-medium text-primary underline-offset-4 hover:underline"
              href={TAXONOMY_URL}
              target="_blank"
              rel="noreferrer"
            >
              View anti_patterns.yaml <ExternalLink className="h-4 w-4" />
            </a>
          </CardContent>
        </Card>
      </main>
    </AuthShell>
  );
}

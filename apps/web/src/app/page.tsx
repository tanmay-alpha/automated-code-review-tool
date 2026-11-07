import { Github, Boxes, LineChart, ArrowRight } from "lucide-react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { BASE_URL } from "@/lib/api";

const TAXONOMY_URL =
  "https://github.com/tanmay-alpha/automated-code-review-tool/blob/main/taxonomy/anti_patterns.yaml";

const FEATURES = [
  {
    icon: Boxes,
    title: "VS Code extension",
    body: "Scans a file on save and shows findings as editor diagnostics, using the same API as the PR reviews.",
  },
  {
    icon: Github,
    title: "GitHub webhook",
    body: "Connect a repository and each opened or updated pull request is reviewed and gets a summary comment.",
  },
  {
    icon: LineChart,
    title: "Quality dashboard",
    body: "Per-repo quality score, PR history, the most frequent anti-pattern, and a 7/30/90-day trend chart.",
  },
] as const;

/**
 * Public landing page (`/`). After GitHub sign-in the API's OAuth callback
 * redirects to /dashboard.
 */
export default function LandingPage() {
  return (
    <div className="min-h-screen bg-background">
      <header className="mx-auto flex max-w-6xl items-center justify-between px-6 py-5">
        <div className="text-lg font-semibold tracking-tight">automated-code-review-tool</div>
      </header>

      <Separator />

      <main className="mx-auto max-w-6xl px-6 py-16">
        <section className="max-w-2xl space-y-6">
          <h1 className="text-4xl font-bold tracking-tight md:text-5xl">
            Automated review for pull requests.
          </h1>
          <p className="text-lg text-muted-foreground">
            Flags anti-patterns such as hardcoded secrets, SQL injection, N+1
            queries, weak crypto and broad exception handlers —
            and explains each finding. Detection runs on a rule-based
            scanner; a CodeBERT classifier can be plugged in when a trained
            checkpoint is configured.
          </p>
          <div className="flex flex-wrap items-center gap-3">
            <Button asChild size="lg">
              {/* Spring Boot serves /api/auth/github as a 302 to GitHub. */}
              <a href={`${BASE_URL}/api/auth/github`}>
                <Github className="mr-2 h-4 w-4" />
                Login with GitHub
              </a>
            </Button>
            <Button asChild variant="ghost" size="lg">
              <a href={TAXONOMY_URL} target="_blank" rel="noreferrer">
                See the taxonomy
                <ArrowRight className="ml-2 h-4 w-4" />
              </a>
            </Button>
          </div>
        </section>

        <section className="mt-24">
          <h2 className="text-2xl font-semibold tracking-tight">
            What&apos;s included
          </h2>
          <div className="mt-8 grid gap-6 md:grid-cols-3">
            {FEATURES.map(({ icon: Icon, title, body }) => (
              <Card key={title}>
                <CardHeader>
                  <Icon className="h-6 w-6 text-muted-foreground" />
                  <CardTitle className="text-lg">{title}</CardTitle>
                </CardHeader>
                <CardContent>
                  <CardDescription>{body}</CardDescription>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>
      </main>

      <Separator />

      <footer className="mx-auto max-w-6xl px-6 py-8 text-sm text-muted-foreground">
        automated-code-review-tool · MIT · Spring Boot, FastAPI and Next.js.
      </footer>
    </div>
  );
}

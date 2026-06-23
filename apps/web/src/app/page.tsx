import Link from "next/link";
import {
  Github,
  Boxes,
  Eye,
  LineChart,
  ArrowRight,
} from "lucide-react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";

const FEATURES = [
  {
    icon: Boxes,
    title: "VS Code extension",
    body: "Inline squiggles as you write. Powered by the same model that reviews your PRs.",
  },
  {
    icon: Github,
    title: "GitHub bot",
    body: "Posts a review comment on every PR within seconds of opening. Zero CI changes.",
  },
  {
    icon: LineChart,
    title: "Quality dashboard",
    body: "Per-repo score, anti-pattern frequency, and a 30-day trend line.",
  },
] as const;

/**
 * Public landing page (`/`). Unauthenticated only — once the user signs
 * in, the OAuth callback redirects them to /dashboard.
 */
export default function LandingPage() {
  return (
    <div className="min-h-screen bg-background">
      <header className="mx-auto flex max-w-6xl items-center justify-between px-6 py-5">
        <div className="text-lg font-semibold tracking-tight">CodeLens</div>
        <Badge variant="outline">Beta</Badge>
      </header>

      <Separator />

      <main className="mx-auto max-w-6xl px-6 py-16">
        <section className="grid gap-10 md:grid-cols-2 md:items-center">
          <div className="space-y-6">
            <Badge variant="secondary">Semantic code review</Badge>
            <h1 className="text-4xl font-bold tracking-tight md:text-5xl">
              Semantic code review. Catches what linters miss.
            </h1>
            <p className="text-lg text-muted-foreground">
              CodeLens is a fine-tuned CodeBERT model that recognises the
              13 anti-patterns most likely to ship as tech debt — N+1
              queries, swallowed exceptions, race conditions, and more —
              and explains each finding in plain English.
            </p>
            <div className="flex flex-wrap items-center gap-3">
              <Button asChild size="lg">
                {/* Spring Boot serves /api/auth/github as a 302 to GitHub. */}
                <a href="http://localhost:8080/api/auth/github">
                  <Github className="mr-2 h-4 w-4" />
                  Login with GitHub
                </a>
              </Button>
              <Button asChild variant="ghost" size="lg">
                <Link href="/taxonomy">
                  See the taxonomy
                  <ArrowRight className="ml-2 h-4 w-4" />
                </Link>
              </Button>
            </div>
          </div>

          {/* Demo GIF placeholder. Swap for a real screencast when ready. */}
          <div className="aspect-video w-full overflow-hidden rounded-lg border bg-muted">
            <div className="flex h-full w-full items-center justify-center text-sm text-muted-foreground">
              <Eye className="mr-2 h-5 w-5" />
              Demo GIF placeholder
            </div>
          </div>
        </section>

        <section className="mt-24">
          <h2 className="text-2xl font-semibold tracking-tight">
            Three surfaces, one engine.
          </h2>
          <p className="mt-2 text-muted-foreground">
            Sign in once. The same review powers your editor, your PRs, and
            your dashboards.
          </p>
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
        CodeLens · MIT · Built on Spring Boot + Next.js + CodeBERT.
      </footer>
    </div>
  );
}

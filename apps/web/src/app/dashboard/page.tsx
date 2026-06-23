"use client";

import { useState } from "react";
import useSWR from "swr";
import { Plus, FolderGit2, GitPullRequest, Award } from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { RepoCard } from "@/components/RepoCard";
import { ConnectRepoModal } from "@/components/ConnectRepoModal";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Separator } from "@/components/ui/separator";
import { getRepos } from "@/lib/api";
import type { RepoSummaryResponse } from "@/lib/types";

/**
 * Top-level dashboard. Three sections:
 *   1. Stats bar (repos connected, PRs reviewed, average quality score).
 *   2. "Connect Repository" button → modal.
 *   3. Grid of RepoCard components.
 *
 * Uses SWR for auto-revalidation. The connect modal mutates the cache
 * optimistically so the new repo shows up instantly.
 */
function DashboardContent() {
  const { data: repos, error, isLoading, mutate } = useSWR<RepoSummaryResponse[]>(
    "repos",
    getRepos,
    { revalidateOnFocus: true },
  );
  const [modalOpen, setModalOpen] = useState(false);

  const stats = computeStats(repos);

  return (
    <div className="mx-auto max-w-6xl space-y-8 p-8">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Dashboard</h1>
          <p className="mt-1 text-muted-foreground">
            Connected repositories and their code quality trends.
          </p>
        </div>
        <Button onClick={() => setModalOpen(true)}>
          <Plus className="mr-2 h-4 w-4" />
          Connect Repository
        </Button>
      </header>

      <Separator />

      {/* Stats bar */}
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard
          icon={FolderGit2}
          label="Repositories connected"
          value={stats.totalRepos}
          loading={isLoading}
        />
        <StatCard
          icon={GitPullRequest}
          label="PRs reviewed"
          value={stats.totalPrs}
          loading={isLoading}
        />
        <StatCard
          icon={Award}
          label="Average quality score"
          value={stats.avgScore == null ? "—" : stats.avgScore.toFixed(0)}
          loading={isLoading}
        />
      </section>

      {error ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn’t load repositories</AlertTitle>
          <AlertDescription>
            {(error as Error).message ?? "Unknown error."}
          </AlertDescription>
        </Alert>
      ) : null}

      {/* Repo grid */}
      <section>
        <h2 className="mb-4 text-xl font-semibold tracking-tight">
          Your repositories
        </h2>
        {isLoading ? (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-48 w-full" />
            ))}
          </div>
        ) : !repos || repos.length === 0 ? (
          <Card>
            <CardHeader>
              <CardTitle>No repositories yet</CardTitle>
              <CardDescription>
                Connect a GitHub repository to start getting AI-powered
                code review comments on every PR.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <Button onClick={() => setModalOpen(true)}>
                <Plus className="mr-2 h-4 w-4" />
                Connect your first repository
              </Button>
            </CardContent>
          </Card>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {repos.map((repo) => (
              <RepoCard key={repo.id} repo={repo} />
            ))}
          </div>
        )}
      </section>

      <ConnectRepoModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        onSuccess={(newRepo) => {
          // Optimistic insert; SWR revalidates the list in the background.
          mutate(
            (current) =>
              current ? [...current, newRepo] : [newRepo],
            { revalidate: true },
          );
        }}
      />
    </div>
  );
}

function StatCard({
  icon: Icon,
  label,
  value,
  loading,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string | number;
  loading: boolean;
}) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
        <CardTitle className="text-sm font-medium text-muted-foreground">
          {label}
        </CardTitle>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </CardHeader>
      <CardContent>
        {loading ? (
          <Skeleton className="h-8 w-16" />
        ) : (
          <div className="text-2xl font-bold">{value}</div>
        )}
      </CardContent>
    </Card>
  );
}

function computeStats(repos: RepoSummaryResponse[] | undefined) {
  if (!repos || repos.length === 0) {
    return { totalRepos: 0, totalPrs: 0, avgScore: null as number | null };
  }
  const totalRepos = repos.length;
  const totalPrs = repos.reduce((acc, r) => acc + r.totalPrsReviewed, 0);
  const scoresWithData = repos
    .map((r) => r.latestQualityScore)
    .filter((s): s is number => s != null);
  const avgScore =
    scoresWithData.length > 0
      ? scoresWithData.reduce((a, b) => a + b, 0) / scoresWithData.length
      : null;
  return { totalRepos, totalPrs, avgScore };
}

export default function DashboardPage() {
  return (
    <AuthShell>
      <DashboardContent />
    </AuthShell>
  );
}

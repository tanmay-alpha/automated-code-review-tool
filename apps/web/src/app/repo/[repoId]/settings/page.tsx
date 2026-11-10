"use client";

import Link from "next/link";
import useSWR from "swr";
import { useParams } from "next/navigation";
import { ChevronLeft } from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { RepoSettingsForm } from "@/components/RepoSettingsForm";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, getRepoDetail, getRepoSettings, updateRepoSettings } from "@/lib/api";
import type { RepoResponse, RepoSettings } from "@/lib/types";

function RepoSettingsContent() {
  const { repoId } = useParams<{ repoId: string }>();
  const { data: repo } = useSWR<RepoResponse>(repoId ? ["repo", repoId] : null, () =>
    getRepoDetail(repoId),
  );
  const { data: settings, error, mutate } = useSWR<RepoSettings>(
    repoId ? ["repo-settings", repoId] : null,
    () => getRepoSettings(repoId),
    { revalidateOnFocus: false },
  );

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-4 md:p-8">
      <Link
        href={`/repo/${repoId}`}
        className="inline-flex items-center text-sm text-muted-foreground hover:text-foreground"
      >
        <ChevronLeft className="mr-1 h-4 w-4" aria-hidden="true" />
        {repo?.fullName ?? "Back to repository"}
      </Link>
      <header>
        <h1 className="text-2xl font-bold tracking-tight md:text-3xl">Review settings</h1>
        <p className="mt-1 text-muted-foreground">
          What the reviewer reports for{" "}
          <span className="font-medium text-foreground">{repo?.fullName ?? "this repository"}</span>{" "}
          and how it comments on pull requests. Changes apply to the next review.
        </p>
      </header>

      {error ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn’t load settings</AlertTitle>
          <AlertDescription>
            {error instanceof ApiError && error.status === 404
              ? "Repository not found or not one of yours."
              : (error as Error).message}
          </AlertDescription>
        </Alert>
      ) : !settings ? (
        <div className="space-y-3" role="status" aria-label="Loading settings">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      ) : (
        <Card>
          <CardContent className="p-4 md:p-6">
            <RepoSettingsForm
              initial={settings}
              onSave={async (next) => {
                const saved = await updateRepoSettings(repoId, next);
                await mutate(saved, { revalidate: false });
                return saved;
              }}
            />
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export default function RepoSettingsPage() {
  return (
    <AuthShell>
      <RepoSettingsContent />
    </AuthShell>
  );
}

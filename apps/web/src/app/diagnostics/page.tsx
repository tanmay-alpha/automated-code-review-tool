"use client";

import useSWR from "swr";
import { Activity, RefreshCw } from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { DiagnosticsView } from "@/components/DiagnosticsView";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { getDiagnostics } from "@/lib/api";
import type { DiagnosticsResponse } from "@/lib/types";

function DiagnosticsContent() {
  const { data, error, isLoading, isValidating, mutate } = useSWR<DiagnosticsResponse>(
    "diagnostics",
    getDiagnostics,
    { refreshInterval: 30_000, revalidateOnFocus: true },
  );

  return (
    <div className="mx-auto max-w-5xl space-y-6 p-4 md:p-8">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold tracking-tight md:text-3xl">
            <Activity className="h-6 w-6 text-muted-foreground" aria-hidden="true" />
            Diagnostics
          </h1>
          <p className="mt-1 text-muted-foreground">
            Health of the services that review your pull requests. Refreshes every 30 seconds.
          </p>
        </div>
        <Button
          type="button"
          variant="outline"
          onClick={() => void mutate()}
          disabled={isValidating}
        >
          <RefreshCw className={`mr-2 h-4 w-4 ${isValidating ? "animate-spin" : ""}`} aria-hidden="true" />
          {isValidating ? "Checking…" : "Check now"}
        </Button>
      </header>

      {error && !data ? (
        <Alert variant="destructive">
          <AlertTitle>Couldn’t load diagnostics</AlertTitle>
          <AlertDescription>{(error as Error).message}</AlertDescription>
        </Alert>
      ) : isLoading || !data ? (
        <div className="space-y-4" role="status" aria-label="Loading diagnostics">
          <Skeleton className="h-16 w-full" />
          <div className="grid gap-4 sm:grid-cols-2">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-28 w-full" />
            ))}
          </div>
        </div>
      ) : (
        <>
          {error ? (
            <Alert variant="warning">
              <AlertTitle>Showing the last successful check</AlertTitle>
              <AlertDescription>{(error as Error).message}</AlertDescription>
            </Alert>
          ) : null}
          <DiagnosticsView data={data} />
        </>
      )}
    </div>
  );
}

export default function DiagnosticsPage() {
  return (
    <AuthShell>
      <DiagnosticsContent />
    </AuthShell>
  );
}

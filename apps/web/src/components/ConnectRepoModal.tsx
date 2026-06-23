"use client";

import { useState, type FormEvent } from "react";
import { X, ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { connectRepo } from "@/lib/api";
import type { RepoSummaryResponse } from "@/lib/types";

export interface ConnectRepoModalProps {
  open: boolean;
  onClose: () => void;
  onSuccess: (repo: RepoSummaryResponse) => void;
}

/**
 * Modal dialog for connecting a new GitHub repository. Posts to
 * /api/repos/connect with the `owner/repo` form. On success the parent
 * dashboard can revalidate its SWR list.
 */
export function ConnectRepoModal({
  open,
  onClose,
  onSuccess,
}: ConnectRepoModalProps) {
  const [fullName, setFullName] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    // Basic shape check — Spring Boot enforces the same regex server-side
    // via @Pattern("^[\\w.-]+/[\\w.-]+$").
    if (!/^[\w.-]+\/[\w.-]+$/.test(fullName.trim())) {
      setError("Repository must be in 'owner/repo' format.");
      return;
    }

    try {
      setSubmitting(true);
      const repo = await connectRepo(fullName.trim());
      onSuccess(repo);
      setFullName("");
      onClose();
    } catch (err) {
      const msg =
        err instanceof Error ? err.message : "Failed to connect repository.";
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      role="dialog"
      aria-modal="true"
      aria-labelledby="connect-repo-title"
    >
      <div className="w-full max-w-md rounded-lg border bg-background p-6 shadow-lg">
        <div className="mb-4 flex items-start justify-between">
          <div>
            <h2
              id="connect-repo-title"
              className="text-lg font-semibold tracking-tight"
            >
              Connect a repository
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              Install the CodeLens webhook on a GitHub repo you own.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1 text-muted-foreground hover:bg-accent"
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label
              htmlFor="fullName"
              className="mb-1 block text-sm font-medium"
            >
              Repository
            </label>
            <input
              id="fullName"
              type="text"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              placeholder="octocat/hello-world"
              autoComplete="off"
              autoFocus
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
            <p className="mt-1 text-xs text-muted-foreground">
              e.g.{" "}
              <a
                href="https://github.com/vercel/next.js"
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 underline"
              >
                vercel/next.js
                <ExternalLink className="h-3 w-3" />
              </a>
            </p>
          </div>

          {error ? (
            <Alert variant="destructive">
              <AlertTitle>Couldn’t connect</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}

          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="ghost"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={submitting || !fullName.trim()}>
              {submitting ? "Connecting…" : "Connect"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}

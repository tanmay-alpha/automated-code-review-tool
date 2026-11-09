"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { connectRepo } from "@/lib/api";
import type { RepoResponse } from "@/lib/types";

export interface ConnectRepoModalProps {
  open: boolean;
  onClose: () => void;
  onSuccess: (repo: RepoResponse) => void;
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
    <Dialog open onOpenChange={(next) => !next && !submitting && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Connect a repository</DialogTitle>
          <DialogDescription>
            Install the automated-code-review-tool webhook on a GitHub repo you own.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="fullName" className="mb-1 block text-sm font-medium">
              Repository
            </label>
            <Input
              id="fullName"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              placeholder="octocat/hello-world"
              autoComplete="off"
              aria-describedby="fullName-hint"
            />
            <p id="fullName-hint" className="mt-1 text-xs text-muted-foreground">
              Format: owner/repo. Creating the webhook needs admin access to
              the repository.
            </p>
          </div>

          {error ? (
            <Alert variant="destructive">
              <AlertTitle>Couldn’t connect</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose} disabled={submitting}>
              Cancel
            </Button>
            <Button type="submit" disabled={submitting || !fullName.trim()}>
              {submitting ? "Connecting…" : "Connect"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

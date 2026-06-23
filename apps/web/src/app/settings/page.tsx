"use client";

import { useState } from "react";
import useSWR from "swr";
import {
  Key,
  Trash2,
  Copy,
  AlertTriangle,
  RefreshCw,
  Github,
  FolderGit2,
} from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { getMe, getRepos, disconnectRepo, regenerateApiKey } from "@/lib/api";
import { timeAgo } from "@/lib/utils";

function SettingsContent() {
  const { data: user, isLoading: userLoading } = useSWR("me", getMe);
  const { data: repos, mutate } = useSWR("repos", getRepos);

  const [regenerated, setRegenerated] = useState<string | null>(null);
  const [regenLoading, setRegenLoading] = useState(false);
  const [regenError, setRegenError] = useState<string | null>(null);

  const handleRegenerate = async () => {
    setRegenLoading(true);
    setRegenError(null);
    try {
      const { apiKey } = await regenerateApiKey();
      setRegenerated(apiKey);
    } catch (e) {
      setRegenError(e instanceof Error ? e.message : "Unknown error");
    } finally {
      setRegenLoading(false);
    }
  };

  const handleDisconnect = async (id: string, fullName: string) => {
    if (!confirm(`Disconnect ${fullName}? The webhook will be removed.`)) {
      return;
    }
    try {
      await disconnectRepo(id);
      mutate();
    } catch (e) {
      alert(e instanceof Error ? e.message : "Failed to disconnect");
    }
  };

  return (
    <div className="mx-auto max-w-4xl space-y-8 p-8">
      <header>
        <h1 className="text-3xl font-bold tracking-tight">Settings</h1>
        <p className="mt-1 text-muted-foreground">
          Manage connected repositories, your API key, and your account.
        </p>
      </header>

      <Separator />

      {/* Section 1: Connected repositories */}
      <section>
        <div className="mb-3 flex items-center gap-2">
          <FolderGit2 className="h-5 w-5 text-muted-foreground" />
          <h2 className="text-xl font-semibold">Connected repositories</h2>
        </div>
        {!repos ? (
          <div className="space-y-2">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : repos.length === 0 ? (
          <Card>
            <CardContent className="p-6 text-sm text-muted-foreground">
              No repositories connected yet.{" "}
              <a href="/dashboard" className="underline">
                Connect one from the dashboard.
              </a>
            </CardContent>
          </Card>
        ) : (
          <div className="divide-y rounded-lg border">
            {repos.map((r) => (
              <div
                key={r.id}
                className="flex items-center justify-between gap-3 px-4 py-3"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{r.fullName}</div>
                  <div className="text-xs text-muted-foreground">
                    Connected {timeAgo(r.createdAt)} · {r.totalPrsReviewed}{" "}
                    PR{r.totalPrsReviewed === 1 ? "" : "s"} reviewed
                  </div>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleDisconnect(r.id, r.fullName)}
                >
                  <Trash2 className="mr-1 h-3.5 w-3.5" />
                  Disconnect
                </Button>
              </div>
            ))}
          </div>
        )}
      </section>

      <Separator />

      {/* Section 2: API key */}
      <section>
        <div className="mb-3 flex items-center gap-2">
          <Key className="h-5 w-5 text-muted-foreground" />
          <h2 className="text-xl font-semibold">API key</h2>
        </div>
        <Card>
          <CardHeader>
            <CardDescription>
              Use this key in the VS Code extension (Command Palette →{" "}
              <span className="font-mono">CodeLens: Set API Key</span>). It
              authenticates requests as your account.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center gap-3">
              <code className="flex-1 truncate rounded-md bg-muted px-3 py-2 font-mono text-sm">
                {user?.apiKeyPrefix ?? "No key generated"}
              </code>
              <Button
                variant="default"
                onClick={handleRegenerate}
                disabled={regenLoading}
              >
                <RefreshCw
                  className={`mr-2 h-4 w-4 ${regenLoading ? "animate-spin" : ""}`}
                />
                Regenerate
              </Button>
            </div>
            <Alert>
              <AlertTriangle className="h-4 w-4" />
              <AlertTitle>Keep it secret</AlertTitle>
              <AlertDescription>
                Treat your API key like a password. Anyone with it can
                impersonate you to the CodeLens API.
              </AlertDescription>
            </Alert>
            {regenError ? (
              <Alert variant="destructive">
                <AlertTitle>Regenerate failed</AlertTitle>
                <AlertDescription>{regenError}</AlertDescription>
              </Alert>
            ) : null}
          </CardContent>
        </Card>
      </section>

      <Separator />

      {/* Section 3: Account */}
      <section>
        <div className="mb-3 flex items-center gap-2">
          <Github className="h-5 w-5 text-muted-foreground" />
          <h2 className="text-xl font-semibold">Account</h2>
        </div>
        <Card>
          <CardContent className="divide-y p-0">
            <Row label="Username">
              {userLoading ? (
                <Skeleton className="h-4 w-32" />
              ) : (
                <span className="font-mono">@{user?.githubUsername}</span>
              )}
            </Row>
            <Row label="User ID">
              {userLoading ? (
                <Skeleton className="h-4 w-48" />
              ) : (
                <span className="font-mono text-xs text-muted-foreground">
                  {user?.id}
                </span>
              )}
            </Row>
            <Row label="Joined">
              {userLoading ? (
                <Skeleton className="h-4 w-32" />
              ) : (
                <span>{timeAgo(user?.createdAt)}</span>
              )}
            </Row>
            <Row label="API key prefix">
              {userLoading ? (
                <Skeleton className="h-4 w-40" />
              ) : (
                <Badge variant="outline" className="font-mono">
                  {user?.apiKeyPrefix ?? "—"}
                </Badge>
              )}
            </Row>
          </CardContent>
        </Card>
      </section>

      <ApiKeyModal
        apiKey={regenerated}
        onClose={() => setRegenerated(null)}
      />
    </div>
  );
}

function Row({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex items-center justify-between px-4 py-3">
      <span className="text-sm text-muted-foreground">{label}</span>
      <span className="text-sm">{children}</span>
    </div>
  );
}

function ApiKeyModal({
  apiKey,
  onClose,
}: {
  apiKey: string | null;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  if (!apiKey) return null;
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(apiKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // ignore — UI will just not flash the checkmark.
    }
  };
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-lg rounded-lg border bg-background p-6 shadow-lg">
        <h2 className="text-lg font-semibold">New API key</h2>
        <Alert variant="warning" className="my-4">
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>This key will not be shown again.</AlertTitle>
          <AlertDescription>
            Copy it now and paste it into your VS Code settings or CI
            secret store. After you close this dialog we only keep the
            prefix.
          </AlertDescription>
        </Alert>
        <div className="flex items-center gap-2">
          <code className="flex-1 break-all rounded-md bg-muted px-3 py-2 font-mono text-xs">
            {apiKey}
          </code>
          <Button onClick={handleCopy} variant="outline" size="sm">
            <Copy className="mr-1 h-3.5 w-3.5" />
            {copied ? "Copied!" : "Copy"}
          </Button>
        </div>
        <div className="mt-4 flex justify-end">
          <Button onClick={onClose}>Done</Button>
        </div>
      </div>
    </div>
  );
}

export default function SettingsPage() {
  return (
    <AuthShell>
      <SettingsContent />
    </AuthShell>
  );
}

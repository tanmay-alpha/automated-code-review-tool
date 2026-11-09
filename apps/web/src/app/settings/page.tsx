"use client";

import { useState, useEffect, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import {
  Key,
  Trash2,
  Copy,
  AlertTriangle,
  Plus,
  Github,
  FolderGit2,
  Settings2,
} from "lucide-react";
import { AuthShell } from "@/components/AuthShell";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
} from "@/components/ui/card";
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
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import {
  createApiKey,
  disconnectRepo,
  getMe,
  getRepos,
  listApiKeys,
  logoutAll,
  revokeApiKey,
} from "@/lib/api";
import { timeAgo } from "@/lib/utils";

function SettingsContent() {
  const { data: user, isLoading: userLoading } = useSWR("me", getMe);
  const { data: repos, error: reposError, mutate: mutateRepos } = useSWR(
    "repos",
    getRepos,
  );
  const {
    data: keys,
    error: keysError,
    mutate: mutateKeys,
  } = useSWR("api-keys", listApiKeys);

  const [newKey, setNewKey] = useState<string | null>(null);
  const [label, setLabel] = useState("");
  const [creating, setCreating] = useState(false);
  const [keyActionError, setKeyActionError] = useState<string | null>(null);
  const [repoActionError, setRepoActionError] = useState<string | null>(null);
  const [signingOut, setSigningOut] = useState(false);
  const router = useRouter();

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setCreating(true);
    setKeyActionError(null);
    try {
      const created = await createApiKey(label.trim());
      setNewKey(created.key);
      setLabel("");
      mutateKeys();
    } catch (err) {
      setKeyActionError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setCreating(false);
    }
  };

  const handleRevoke = async (id: string, keyLabel: string) => {
    if (!confirm(`Revoke the key "${keyLabel}"? Clients using it will stop working.`)) {
      return;
    }
    setKeyActionError(null);
    try {
      await revokeApiKey(id);
      mutateKeys();
    } catch (err) {
      setKeyActionError(err instanceof Error ? err.message : "Unknown error");
    }
  };

  const handleDisconnect = async (id: string, fullName: string) => {
    if (!confirm(`Disconnect ${fullName}? The webhook will be removed.`)) {
      return;
    }
    setRepoActionError(null);
    try {
      await disconnectRepo(id);
      mutateRepos();
    } catch (e) {
      setRepoActionError(e instanceof Error ? e.message : "Failed to disconnect");
    }
  };

  return (
    <div className="mx-auto max-w-4xl space-y-8 p-4 md:p-8">
      <header>
        <h1 className="text-2xl font-bold tracking-tight md:text-3xl">Settings</h1>
        <p className="mt-1 text-muted-foreground">
          Manage connected repositories, your API keys, and your account.
        </p>
      </header>

      <Separator />

      {/* Section 1: Connected repositories */}
      <section>
        <div className="mb-3 flex items-center gap-2">
          <FolderGit2 className="h-5 w-5 text-muted-foreground" />
          <h2 className="text-xl font-semibold">Connected repositories</h2>
        </div>
        {repoActionError ? (
          <Alert variant="destructive" className="mb-3">
            <AlertTitle>Couldn’t disconnect the repository</AlertTitle>
            <AlertDescription>{repoActionError}</AlertDescription>
          </Alert>
        ) : null}
        {reposError ? (
          <Alert variant="destructive">
            <AlertTitle>Couldn’t load repositories</AlertTitle>
            <AlertDescription>{(reposError as Error).message}</AlertDescription>
          </Alert>
        ) : !repos ? (
          <div className="space-y-2">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : repos.length === 0 ? (
          <Card>
            <CardContent className="p-6 text-sm text-muted-foreground">
              No repositories connected yet.{" "}
              <Link href="/dashboard" className="underline">
                Connect one from the dashboard.
              </Link>
            </CardContent>
          </Card>
        ) : (
          <div className="divide-y rounded-lg border">
            {repos.map((r) => (
              <div
                key={r.id}
                className="flex flex-wrap items-center justify-between gap-3 px-4 py-3"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{r.fullName}</div>
                  <div className="text-xs text-muted-foreground">
                    Connected {timeAgo(r.createdAt)} · {r.totalPrsReviewed}{" "}
                    PR{r.totalPrsReviewed === 1 ? "" : "s"} reviewed
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <Button asChild variant="outline" size="sm">
                    <Link
                      href={`/repo/${r.id}/settings`}
                      aria-label={`Review settings for ${r.fullName}`}
                    >
                      <Settings2 className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                      Review settings
                    </Link>
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleDisconnect(r.id, r.fullName)}
                    aria-label={`Disconnect ${r.fullName}`}
                  >
                    <Trash2 className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                    Disconnect
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <Separator />

      {/* Section 2: API keys */}
      <section>
        <div className="mb-3 flex items-center gap-2">
          <Key className="h-5 w-5 text-muted-foreground" />
          <h2 className="text-xl font-semibold">API keys</h2>
        </div>
        <Card>
          <CardHeader>
            <CardDescription>
              API keys authenticate the VS Code extension (run the command{" "}
              <span className="font-mono">automated-code-review-tool: Set API Key</span>;
              the key is kept in VS Code&apos;s SecretStorage) and the GitHub
              Action as your account. Treat them like passwords.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <form onSubmit={handleCreate} className="flex flex-col gap-2 sm:flex-row sm:items-center">
              <Input
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                placeholder="Label, e.g. work laptop"
                maxLength={60}
                aria-label="New key label"
                className="flex-1"
              />
              <Button type="submit" disabled={creating || !label.trim()}>
                <Plus className="mr-2 h-4 w-4" />
                {creating ? "Creating…" : "Create key"}
              </Button>
            </form>

            {keyActionError ? (
              <Alert variant="destructive">
                <AlertTitle>API key request failed</AlertTitle>
                <AlertDescription>{keyActionError}</AlertDescription>
              </Alert>
            ) : null}

            {keysError ? (
              <Alert variant="destructive">
                <AlertTitle>Couldn’t load API keys</AlertTitle>
                <AlertDescription>{(keysError as Error).message}</AlertDescription>
              </Alert>
            ) : !keys ? (
              <Skeleton className="h-12 w-full" />
            ) : keys.length === 0 ? (
              <p className="text-sm text-muted-foreground">No API keys yet.</p>
            ) : (
              <div className="divide-y rounded-lg border">
                {keys.map((k) => (
                  <div
                    key={k.id}
                    className="flex items-center justify-between gap-3 px-4 py-3"
                  >
                    <div className="min-w-0">
                      <div className="truncate font-medium">{k.label}</div>
                      <div className="text-xs text-muted-foreground">
                        <span className="font-mono">{k.prefix}…</span> · created{" "}
                        {timeAgo(k.createdAt)} · last used{" "}
                        {k.lastUsedAt ? timeAgo(k.lastUsedAt) : "never"}
                      </div>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleRevoke(k.id, k.label)}
                      aria-label={`Revoke ${k.label}`}
                    >
                      <Trash2 className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
                      Revoke
                    </Button>
                  </div>
                ))}
              </div>
            )}
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
            <Row label="Sessions">
              <Button
                variant="outline"
                size="sm"
                disabled={signingOut}
                onClick={async () => {
                  if (!confirm("Sign out on every device, including this one?")) return;
                  setSigningOut(true);
                  try {
                    await logoutAll();
                    router.replace("/");
                  } catch (err) {
                    setSigningOut(false);
                    alert(err instanceof Error ? err.message : "Could not sign out everywhere.");
                  }
                }}
              >
                {signingOut ? "Signing out…" : "Sign out everywhere"}
              </Button>
            </Row>
          </CardContent>
        </Card>
      </section>

      <ApiKeyModal apiKey={newKey} onClose={() => setNewKey(null)} />
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
    <div className="flex items-center justify-between gap-3 px-4 py-3">
      <span className="text-sm text-muted-foreground">{label}</span>
      <span className="text-sm">{children}</span>
    </div>
  );
}

/** Shows a freshly created key once, then masks it after 30 seconds. */
function ApiKeyModal({
  apiKey,
  onClose,
}: {
  apiKey: string | null;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  const [displayKey, setDisplayKey] = useState<string | null>(apiKey);
  const [countdown, setCountdown] = useState(30);

  useEffect(() => {
    setDisplayKey(apiKey);
    setCountdown(30);
    if (!apiKey) return;

    const interval = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(interval);
          setDisplayKey("••••••••••••••••••••••••••••••••");
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [apiKey]);

  if (!apiKey) return null;
  const handleCopy = async () => {
    if (!displayKey || displayKey.startsWith("•")) return;
    try {
      await navigator.clipboard.writeText(displayKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard access denied; the key is still visible to copy by hand.
    }
  };
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New API key</DialogTitle>
          <DialogDescription>
            Copy the key now; it is shown only once.
          </DialogDescription>
        </DialogHeader>
        <Alert variant="warning">
          <AlertTriangle className="h-4 w-4" aria-hidden="true" />
          <AlertTitle>This key will not be shown again.</AlertTitle>
          <AlertDescription>
            Run{" "}
            <span className="font-mono">automated-code-review-tool: Set API Key</span>{" "}
            in VS Code or paste it into your CI secret store.{" "}
            {countdown > 0 ? `Key will be hidden in ${countdown}s…` : "Key hidden."}
          </AlertDescription>
        </Alert>
        <div className="flex items-center gap-2">
          <code
            className="flex-1 break-all rounded-md bg-muted px-3 py-2 font-mono text-xs"
            data-testid="new-api-key"
          >
            {displayKey}
          </code>
          <Button
            onClick={handleCopy}
            variant="outline"
            size="sm"
            disabled={!displayKey || displayKey.startsWith("•")}
          >
            <Copy className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
            {copied ? "Copied!" : "Copy"}
          </Button>
        </div>
        <DialogFooter>
          <Button onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export default function SettingsPage() {
  return (
    <AuthShell>
      <SettingsContent />
    </AuthShell>
  );
}

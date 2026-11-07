"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ApiError, getMe, logout } from "@/lib/api";
import type { UserResponse } from "@/lib/types";

/**
 * Wraps every authenticated page:
 *   1. Calls /api/auth/me on mount and shows a skeleton while waiting.
 *   2. On 401 → redirects to `/`, which hosts the "Login with GitHub" button.
 *   3. On any other failure (API down, 5xx) → shows the error instead of
 *      pretending the user is logged out.
 *   4. On success → renders Sidebar + children.
 */
export interface AuthShellProps {
  children: React.ReactNode;
}

export function AuthShell({ children }: AuthShellProps) {
  const router = useRouter();
  const [user, setUser] = useState<UserResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getMe()
      .then((u) => {
        if (!cancelled) setUser(u);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 401) {
          router.replace("/");
        } else {
          setError(err instanceof Error ? err.message : "Unknown error");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [router]);

  if (error) {
    return (
      <div className="flex h-screen w-screen items-center justify-center bg-background p-8">
        <Alert variant="destructive" className="max-w-lg">
          <AlertTitle>Couldn’t reach the API</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      </div>
    );
  }

  if (!user) {
    return (
      <div className="flex h-screen w-screen items-center justify-center bg-background">
        <div className="space-y-3">
          <Skeleton className="h-4 w-48" />
          <Skeleton className="h-4 w-64" />
          <Skeleton className="h-4 w-56" />
        </div>
      </div>
    );
  }

  const handleLogout = async () => {
    try {
      await logout();
    } finally {
      router.replace("/");
    }
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-background">
      <Sidebar user={user} onLogout={handleLogout} />
      <main className="flex-1 overflow-auto">{children}</main>
    </div>
  );
}

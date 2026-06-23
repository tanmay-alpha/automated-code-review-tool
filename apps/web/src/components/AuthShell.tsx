"use client";

import { useEffect, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { getMe, logout } from "@/lib/api";
import type { UserResponse } from "@/lib/types";

/**
 * Wraps every authenticated page. Behaviour:
 *   1. Hit /api/auth/me on mount via SWR-free direct call.
 *   2. Show a full-bleed skeleton (no sidebar) while loading.
 *   3. On 401 → push to / (landing page hosts the "Login with GitHub" button).
 *   4. On 200 → render Sidebar + children, passing the user down.
 *
 * The landing route `/` skips this shell entirely (see app/page.tsx).
 */
export interface AuthShellProps {
  children: React.ReactNode;
}

export function AuthShell({ children }: AuthShellProps) {
  const router = useRouter();
  const pathname = usePathname();
  const [user, setUser] = useState<UserResponse | null>(null);
  const [state, setState] = useState<"loading" | "authed" | "unauthed">(
    "loading",
  );

  useEffect(() => {
    let cancelled = false;
    setState("loading");
    getMe()
      .then((u) => {
        if (cancelled) return;
        setUser(u);
        setState("authed");
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        // Any non-2xx → bounce to landing page.
        // 401 is the common case; treat everything else (network etc.) the same.
        void err;
        setState("unauthed");
        router.replace("/");
      });
    return () => {
      cancelled = true;
    };
  }, [pathname, router]);

  if (state !== "authed") {
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

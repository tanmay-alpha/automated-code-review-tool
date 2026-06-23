"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  FolderGit2,
  Settings,
  BookOpen,
  LogOut,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Separator } from "@/components/ui/separator";
import type { UserResponse } from "@/lib/types";

const NAV_ITEMS = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "/repositories", label: "Repositories", icon: FolderGit2 },
  { href: "/settings", label: "Settings", icon: Settings },
  { href: "/taxonomy", label: "Taxonomy", icon: BookOpen },
] as const;

export interface SidebarProps {
  user: UserResponse | null;
  onLogout?: () => void;
}

/**
 * Left-rail navigation. The four routes match the issue spec:
 * Dashboard / Repositories / Settings / Taxonomy. The user card at the
 * bottom-left shows the avatar + username returned by /api/auth/me.
 */
export function Sidebar({ user, onLogout }: SidebarProps) {
  const pathname = usePathname();

  return (
    <aside className="flex h-screen w-60 shrink-0 flex-col border-r bg-card">
      <div className="flex h-14 items-center px-4 font-semibold tracking-tight">
        <span className="text-lg">CodeLens</span>
      </div>

      <Separator />

      <nav className="flex-1 space-y-1 p-2">
        {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
          const active =
            pathname === href || pathname?.startsWith(`${href}/`);
          return (
            <Link
              key={href}
              href={href}
              className={cn(
                "flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors",
                active
                  ? "bg-accent text-accent-foreground"
                  : "text-muted-foreground hover:bg-accent/50 hover:text-foreground",
              )}
            >
              <Icon className="h-4 w-4" />
              {label}
            </Link>
          );
        })}
      </nav>

      <Separator />

      {user ? (
        <div className="flex items-center gap-3 p-3">
          {user.avatarUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={user.avatarUrl}
              alt={user.githubUsername}
              className="h-9 w-9 rounded-full border"
            />
          ) : (
            <div className="h-9 w-9 rounded-full bg-muted" />
          )}
          <div className="flex-1 min-w-0">
            <div className="truncate text-sm font-medium">
              {user.githubUsername}
            </div>
            <div className="truncate text-xs text-muted-foreground">
              Signed in
            </div>
          </div>
          {onLogout ? (
            <button
              type="button"
              onClick={onLogout}
              title="Log out"
              className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground"
            >
              <LogOut className="h-4 w-4" />
            </button>
          ) : null}
        </div>
      ) : (
        <div className="p-3 text-xs text-muted-foreground">Loading…</div>
      )}
    </aside>
  );
}

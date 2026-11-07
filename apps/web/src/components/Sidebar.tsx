"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LayoutDashboard, Settings, BookOpen, LogOut } from "lucide-react";
import { cn } from "@/lib/utils";
import { Separator } from "@/components/ui/separator";
import type { UserResponse } from "@/lib/types";

const NAV_ITEMS = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "/settings", label: "Settings", icon: Settings },
  { href: "/taxonomy", label: "Taxonomy", icon: BookOpen },
] as const;

export interface SidebarProps {
  user: UserResponse;
  onLogout: () => void;
}

/**
 * Left-rail navigation plus the signed-in user's avatar and a logout button.
 */
export function Sidebar({ user, onLogout }: SidebarProps) {
  const pathname = usePathname();

  return (
    <aside className="flex h-screen w-60 shrink-0 flex-col border-r bg-card">
      <div className="flex h-14 items-center px-4 font-semibold tracking-tight">
        <span className="text-lg">automated-code-review-tool</span>
      </div>

      <Separator />

      <nav className="flex-1 space-y-1 p-2">
        {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
          const active =
            pathname === href || pathname?.startsWith(`${href}/`);
          return (
            <Link
              key={label}
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

      <div className="flex items-center gap-3 p-3">
        {user.avatarUrl ? (
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
        <button
          type="button"
          onClick={onLogout}
          title="Log out"
          aria-label="Log out"
          className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <LogOut className="h-4 w-4" />
        </button>
      </div>
    </aside>
  );
}

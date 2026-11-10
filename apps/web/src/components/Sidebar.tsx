"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Activity, BookOpen, LayoutDashboard, LogOut, Settings } from "lucide-react";
import { cn } from "@/lib/utils";
import { Separator } from "@/components/ui/separator";
import type { UserResponse } from "@/lib/types";

const NAV_ITEMS = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "/diagnostics", label: "Diagnostics", icon: Activity },
  { href: "/settings", label: "Settings", icon: Settings },
  { href: "/taxonomy", label: "Taxonomy", icon: BookOpen },
] as const;

export interface SidebarProps {
  user: UserResponse;
  onLogout: () => void;
}

const FOCUS_RING =
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2";

function NavLinks({ pathname, compact }: { pathname: string | null; compact: boolean }) {
  return (
    <>
      {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
        const active = pathname === href || pathname?.startsWith(`${href}/`);
        return (
          <Link
            key={label}
            href={href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex shrink-0 items-center gap-2 rounded-md text-sm font-medium transition-colors",
              compact ? "px-3 py-2" : "gap-3 px-3 py-2",
              active
                ? "bg-accent text-accent-foreground"
                : "text-muted-foreground hover:bg-accent/50 hover:text-foreground",
              FOCUS_RING,
            )}
          >
            <Icon className="h-4 w-4" aria-hidden="true" />
            {label}
          </Link>
        );
      })}
    </>
  );
}

function LogoutButton({ onLogout }: { onLogout: () => void }) {
  return (
    <button
      type="button"
      onClick={onLogout}
      title="Log out"
      aria-label="Log out"
      className={cn(
        "rounded-md p-2 text-muted-foreground hover:bg-accent hover:text-foreground",
        FOCUS_RING,
      )}
    >
      <LogOut className="h-4 w-4" aria-hidden="true" />
    </button>
  );
}

/**
 * Navigation plus the signed-in user and a logout button: a left rail from
 * the `md` breakpoint up, a top bar with a horizontally scrollable link row
 * on small screens.
 */
export function Sidebar({ user, onLogout }: SidebarProps) {
  const pathname = usePathname();

  return (
    <>
      <header className="sticky top-0 z-40 border-b bg-card md:hidden">
        <div className="flex h-12 items-center justify-between gap-2 px-3">
          <span className="truncate font-semibold tracking-tight">automated-code-review-tool</span>
          <div className="flex items-center gap-1">
            <span className="max-w-[10ch] truncate text-xs text-muted-foreground">
              {user.githubUsername}
            </span>
            <LogoutButton onLogout={onLogout} />
          </div>
        </div>
        <nav aria-label="Main" className="flex gap-1 overflow-x-auto px-2 pb-2">
          <NavLinks pathname={pathname} compact />
        </nav>
      </header>

      <aside className="hidden h-screen w-60 shrink-0 flex-col border-r bg-card md:flex">
        <div className="flex h-14 items-center px-4 font-semibold tracking-tight">
          <span className="text-lg">automated-code-review-tool</span>
        </div>

        <Separator />

        <nav aria-label="Main" className="flex-1 space-y-1 p-2">
          <NavLinks pathname={pathname} compact={false} />
        </nav>

        <Separator />

        <div className="flex items-center gap-3 p-3">
          {user.avatarUrl ? (
            <img
              src={user.avatarUrl}
              alt=""
              className="h-9 w-9 rounded-full border"
            />
          ) : (
            <div className="h-9 w-9 rounded-full bg-muted" aria-hidden="true" />
          )}
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-medium">{user.githubUsername}</div>
            <div className="truncate text-xs text-muted-foreground">Signed in</div>
          </div>
          <LogoutButton onLogout={onLogout} />
        </div>
      </aside>
    </>
  );
}

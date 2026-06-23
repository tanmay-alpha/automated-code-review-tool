import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "CodeLens — semantic code review",
  description:
    "Catches what linters miss. AI-powered PR review, repo-level quality trends, and inline anti-pattern annotations.",
};

/**
 * Root layout. Authenticated routes are wrapped in <AuthShell> at the page
 * level (so we don't pay the cost on the public landing page).
 *
 * NB: We do NOT use a top-level `Sidebar` here — instead, every page under
 * (the default tree) decides whether to render <AuthShell>. The landing
 * page is the only fully-public route, so it omits the shell.
 */
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="min-h-screen bg-background font-sans antialiased">
        {children}
      </body>
    </html>
  );
}

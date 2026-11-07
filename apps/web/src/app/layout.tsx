import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "automated-code-review-tool",
  description:
    "Automated pull-request review: rule-based anti-pattern detection, per-repo quality trends, and VS Code diagnostics.",
};

/**
 * Root layout. Authenticated pages wrap themselves in <AuthShell>; the
 * public landing page does not.
 */
export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-background font-sans antialiased">
        {children}
      </body>
    </html>
  );
}

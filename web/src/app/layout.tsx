import type { Metadata } from "next";
import Link from "next/link";
import { ThemeProvider } from "@/components/theme-provider";
import { SiteNav } from "@/components/ui/site-nav";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import "./globals.css";

export const metadata: Metadata = {
  title: "우리집 비서",
  description: "가족이 함께 쓰는 개인 AI 비서",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko" suppressHydrationWarning>
      <body className="flex h-dvh flex-col overflow-hidden bg-background text-foreground">
        <ThemeProvider>
          <header className="flex shrink-0 items-center gap-2 border-b border-border px-3 py-2 text-sm sm:gap-4 sm:px-4">
            <Link href="/" className="shrink-0 font-semibold" aria-label="우리집 비서 홈">
              <span className="sm:hidden">비서</span><span className="hidden sm:inline">우리집 비서</span>
            </Link>
            <SiteNav />
            <div className="ml-auto shrink-0"><ThemeToggle /></div>
          </header>
          <main className="mx-auto min-h-0 w-full flex-1 overflow-y-auto px-4 py-5">
            {children}
          </main>
        </ThemeProvider>
      </body>
    </html>
  );
}

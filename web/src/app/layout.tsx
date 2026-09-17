import type { Metadata } from "next";
import Link from "next/link";
import { ThemeProvider } from "@/components/theme-provider";
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
          <header className="flex shrink-0 items-center gap-4 border-b border-border px-4 py-3 text-sm">
            <Link href="/" className="font-semibold">
              우리집 비서
            </Link>
            <Link href="/usage" className="text-muted">
              사용량
            </Link>
            <Link href="/admin/agents" className="text-muted">
              에이전트 관리
            </Link>
            <ThemeToggle />
          </header>
          <main className="mx-auto min-h-0 w-full max-w-3xl flex-1 overflow-y-auto px-4 py-4">
            {children}
          </main>
        </ThemeProvider>
      </body>
    </html>
  );
}

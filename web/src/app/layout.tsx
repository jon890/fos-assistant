import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "우리집 비서",
  description: "가족이 함께 쓰는 개인 AI 비서",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ko">
      <body className="min-h-screen">
        <header
          className="flex items-center gap-4 border-b px-4 py-3 text-sm"
          style={{ borderColor: "var(--border)" }}
        >
          <Link href="/" className="font-semibold">
            우리집 비서
          </Link>
          <Link href="/usage" style={{ color: "var(--muted)" }}>
            사용량
          </Link>
          <Link href="/admin/agents" style={{ color: "var(--muted)" }}>
            에이전트 관리
          </Link>
        </header>
        <main className="mx-auto w-full max-w-3xl px-4 py-6">{children}</main>
      </body>
    </html>
  );
}

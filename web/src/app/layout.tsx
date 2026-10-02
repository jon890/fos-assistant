import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import { ThemeProvider } from "@/components/theme-provider";
import { AppShell } from "@/components/shell/app-shell";
import { readMe } from "@/lib/me";
import "./globals.css";

const pretendard = localFont({
  src: "../../node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2",
  display: "swap",
  variable: "--font-pretendard",
  weight: "45 920",
});

export const metadata: Metadata = {
  title: "우리집 비서",
  description: "함께 쓰는 AI 비서",
};

// 가상 키보드가 올라오면 레이아웃 뷰포트(h-dvh)가 줄어 입력창이 키보드 위에 남는다. 지원하지 않는 브라우저는 기본 동작 그대로다.
export const viewport: Viewport = {
  interactiveWidget: "resizes-content",
};

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const me = await readMe();

  return (
    <html lang="ko" className={pretendard.variable} suppressHydrationWarning>
      <body className="flex h-dvh overflow-hidden bg-background text-foreground">
        <ThemeProvider>
          <AppShell isAdmin={me?.role === "ADMIN"} displayName={me?.displayName}>
            {children}
          </AppShell>
        </ThemeProvider>
      </body>
    </html>
  );
}

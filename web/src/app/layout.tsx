import type { Metadata } from "next";
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
  description: "가족이 함께 쓰는 개인 AI 비서",
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

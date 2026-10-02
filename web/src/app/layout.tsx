import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import { ThemeProvider } from "@/components/theme-provider";
import { AppShell } from "@/components/shell/app-shell";
import { connection } from "next/server";
import { appName } from "@/lib/app-name";
import { readMe } from "@/lib/me";
import "./globals.css";

const pretendard = localFont({
  src: "../../node_modules/pretendard/dist/web/variable/woff2/PretendardVariable.woff2",
  display: "swap",
  variable: "--font-pretendard",
  weight: "45 920",
});

// 앱 이름은 실행할 때의 환경에서 읽는다. 빌드할 때 굳지 않게 요청마다 만든다.
export async function generateMetadata(): Promise<Metadata> {
  await connection();
  return { title: appName(), description: "함께 쓰는 AI 비서" };
}

// 가상 키보드가 올라오면 레이아웃 뷰포트(h-dvh)가 줄어 입력창이 키보드 위에 남는다. 지원하지 않는 브라우저는 기본 동작 그대로다.
export const viewport: Viewport = {
  interactiveWidget: "resizes-content",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const me = await readMe();

  return (
    <html lang="ko" className={pretendard.variable} suppressHydrationWarning>
      <body className="flex h-dvh overflow-hidden bg-background text-foreground">
        <ThemeProvider>
          <AppShell
            role={me?.role ?? null}
            displayName={me?.displayName}
            appName={appName()}
          >
            {children}
          </AppShell>
        </ThemeProvider>
      </body>
    </html>
  );
}

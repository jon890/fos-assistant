"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { createContext, useContext, useEffect, useState, type Dispatch, type SetStateAction } from "react";
import { ConversationsProvider, useConversations } from "./conversations-provider";
import { Sidebar } from "./sidebar";

const TitleContext = createContext<Dispatch<SetStateAction<string>> | null>(null);

export function useShellTitle(title: string | null): void {
  const setTitle = useContext(TitleContext);
  useEffect(() => {
    setTitle?.(title || "우리집 비서");
    return () => setTitle?.("우리집 비서");
  }, [setTitle, title]);
}

function ShellBody({ isAdmin, displayName, children, signedIn }: {
  isAdmin: boolean;
  displayName?: string;
  children: React.ReactNode;
  signedIn: boolean;
}) {
  const pathname = usePathname();
  const { startNew } = useConversations();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [title, setTitle] = useState("우리집 비서");

  useEffect(() => setDrawerOpen(false), [pathname]);
  useEffect(() => {
    if (!drawerOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const close = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.isComposing || event.defaultPrevented) return;
      event.preventDefault();
      event.stopPropagation();
      setDrawerOpen(false);
    };
    window.addEventListener("keydown", close);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", close);
    };
  }, [drawerOpen]);

  if (!signedIn) {
    return <main className="mx-auto min-h-0 w-full flex-1 overflow-y-auto px-4 py-5">{children}</main>;
  }

  return (
    <TitleContext.Provider value={setTitle}>
      <div className="flex h-full min-h-0 min-w-0 flex-1">
        <button type="button" aria-label="사이드바 닫기" onClick={() => setDrawerOpen(false)}
          tabIndex={drawerOpen ? 0 : -1}
          className={`fixed inset-0 z-30 bg-foreground/35 md:hidden ${drawerOpen ? "" : "pointer-events-none opacity-0"}`} />
        <aside aria-label="사이드바"
          className={`fixed inset-y-0 left-0 z-40 w-72 shrink-0 border-r border-border bg-surface transition-transform md:static md:w-64 md:translate-x-0 ${
            drawerOpen ? "translate-x-0" : "-translate-x-full"
          }`}>
          <Sidebar isAdmin={isAdmin} displayName={displayName} onNavigate={() => setDrawerOpen(false)} />
        </aside>
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          <header className="flex h-14 shrink-0 flex-nowrap items-center gap-3 border-b border-border px-4 md:hidden">
            <button type="button" aria-label="사이드바 열기" onClick={() => setDrawerOpen(true)}
              className="shrink-0 rounded-md px-2 py-1.5 text-xl hover:bg-surface">☰</button>
            <span className="min-w-0 flex-1 truncate text-center text-sm font-medium">{title}</span>
            <Link href="/" aria-label="새 대화" onClick={startNew}
              className="shrink-0 rounded-md px-2 py-1.5 text-xl hover:bg-surface">✎</Link>
          </header>
          <main className="mx-auto min-h-0 w-full flex-1 overflow-y-auto px-4 py-5">
            {children}
          </main>
        </div>
      </div>
    </TitleContext.Provider>
  );
}

export function AppShell({ isAdmin, displayName, children }: {
  isAdmin: boolean;
  displayName?: string;
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  const signedIn = pathname !== "/signin";
  return (
    <ConversationsProvider enabled={signedIn}>
      <ShellBody isAdmin={isAdmin} displayName={displayName} signedIn={signedIn}>
        {children}
      </ShellBody>
    </ConversationsProvider>
  );
}

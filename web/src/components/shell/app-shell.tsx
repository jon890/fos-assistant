"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type Dispatch, type SetStateAction } from "react";
import { ConversationsProvider, useConversations } from "./conversations-provider";
import { Sidebar } from "./sidebar";
import { useShortcuts } from "./use-shortcuts";

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
  const [collapsed, setCollapsed] = useState(false);
  const [title, setTitle] = useState("우리집 비서");
  const searchRef = useRef<HTMLInputElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);

  useEffect(() => {
    try { setCollapsed(localStorage.getItem("sidebar-collapsed") === "1"); } catch { /* 저장소를 막은 브라우저에서도 화면을 연다. */ }
  }, []);

  const updateCollapsed = useCallback((value: boolean) => {
    setCollapsed(value);
    try { localStorage.setItem("sidebar-collapsed", value ? "1" : "0"); } catch { /* 저장할 수 없어도 현재 화면은 바꾼다. */ }
  }, []);
  const toggleSidebar = useCallback(() => {
    if (window.matchMedia("(min-width: 768px)").matches) updateCollapsed(!collapsed);
    else setDrawerOpen((open) => !open);
  }, [collapsed, updateCollapsed]);
  const focusSearch = useCallback(() => {
    if (window.matchMedia("(min-width: 768px)").matches) updateCollapsed(false);
    else setDrawerOpen(true);
    requestAnimationFrame(() => searchRef.current?.focus());
  }, [updateCollapsed]);
  useShortcuts(signedIn, startNew, toggleSidebar, focusSearch);

  useEffect(() => setDrawerOpen(false), [pathname]);
  useEffect(() => {
    if (!drawerOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const close = (event: KeyboardEvent) => {
      if (event.key !== "Escape" || event.isComposing || event.defaultPrevented) return;
      // 메뉴·입력칸·확인 창은 Esc 를 직접 처리한다. 서랍은 그 밖에서 먼저 받는다.
      if (sidebarRef.current?.querySelector('[role="menu"], input[aria-label="대화 이름"], dialog[open]')) return;
      event.preventDefault();
      event.stopPropagation();
      setDrawerOpen(false);
    };
    window.addEventListener("keydown", close, true);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", close, true);
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
        <aside ref={sidebarRef} aria-label="사이드바"
          className={`fixed inset-y-0 left-0 z-40 w-72 shrink-0 border-r border-border bg-surface transition-transform md:static md:translate-x-0 ${collapsed ? "md:hidden" : "md:w-64"} ${
            drawerOpen ? "translate-x-0" : "-translate-x-full"
          }`}>
          <Sidebar isAdmin={isAdmin} displayName={displayName} onNavigate={() => setDrawerOpen(false)}
            searchRef={searchRef} onCollapse={() => updateCollapsed(true)} />
        </aside>
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {collapsed ? <header className="hidden h-14 shrink-0 items-center gap-3 border-b border-border px-4 md:flex">
            <button type="button" aria-label="사이드바 펴기" onClick={() => updateCollapsed(false)}
              className="rounded-md px-2 py-1.5 hover:bg-surface">☰</button>
            <Link href="/" onClick={startNew} className="rounded-md px-2 py-1.5 text-sm hover:bg-surface">새 대화</Link>
          </header> : null}
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

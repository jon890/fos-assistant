"use client";

import Link from "next/link";
import { useState, type RefObject } from "react";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ConversationNav } from "./conversation-nav";
import { MainNav } from "./main-nav";
import { useConversations } from "./conversations-provider";

export function Sidebar({ isAdmin, displayName, onNavigate, searchRef, onCollapse }: {
  isAdmin: boolean;
  displayName?: string;
  onNavigate(): void;
  searchRef: RefObject<HTMLInputElement | null>;
  onCollapse(): void;
}) {
  const { startNew } = useConversations();
  const [query, setQuery] = useState("");

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface px-3 py-4">
      <div className="mb-5 flex items-center justify-between gap-2 px-2">
        <Link href="/" aria-label="우리집 비서 홈" onClick={() => { startNew(); onNavigate(); }}
          className="truncate text-base font-semibold">우리집 비서</Link>
        <button type="button" aria-label="사이드바 접기" onClick={onCollapse}
          className="hidden rounded-md px-2 py-1 text-sm hover:bg-surface-raised md:block">◀</button>
      </div>
      <Link href="/" data-testid="new-conversation-link" onClick={() => { startNew(); onNavigate(); }}
        className="mb-4 rounded-md border border-border bg-background px-3 py-2 text-sm font-medium hover:bg-surface-raised">
        새 대화
      </Link>
      <input ref={searchRef} type="search" aria-label="대화 검색" value={query}
        onChange={(event) => setQuery(event.target.value)} placeholder="대화 검색"
        className="mb-4 w-full rounded-md border border-border bg-background px-3 py-2 text-sm" />
      <ConversationNav onNavigate={onNavigate} query={query} />
      <div className="border-t border-border pt-3">
        <MainNav isAdmin={isAdmin} onNavigate={onNavigate} />
        <div className="mt-3 flex items-center justify-between gap-2 px-3 py-2">
          {displayName ? <span className="min-w-0 truncate text-xs text-muted" title={displayName}>{displayName}</span> : null}
          <ThemeToggle />
        </div>
      </div>
    </div>
  );
}

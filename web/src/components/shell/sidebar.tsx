"use client";

import Link from "next/link";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ConversationNav } from "./conversation-nav";
import { MainNav } from "./main-nav";
import { useConversations } from "./conversations-provider";

export function Sidebar({ isAdmin, displayName, onNavigate }: {
  isAdmin: boolean;
  displayName?: string;
  onNavigate(): void;
}) {
  const { startNew } = useConversations();

  return (
    <div className="flex h-full min-h-0 flex-col bg-surface px-3 py-4">
      <Link href="/" aria-label="우리집 비서 홈" onClick={() => { startNew(); onNavigate(); }}
        className="mb-5 px-2 text-base font-semibold">우리집 비서</Link>
      <Link href="/" data-testid="new-conversation-link" onClick={() => { startNew(); onNavigate(); }}
        className="mb-4 rounded-md border border-border bg-background px-3 py-2 text-sm font-medium hover:bg-surface-raised">
        새 대화
      </Link>
      <ConversationNav onNavigate={onNavigate} />
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

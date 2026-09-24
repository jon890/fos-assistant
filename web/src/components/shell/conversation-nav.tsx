"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useConversations } from "./conversations-provider";
import { groupByDate } from "./group-by-date";
import { Skeleton } from "@/components/ui/skeleton";

export function ConversationNav({ onNavigate }: { onNavigate(): void }) {
  const pathname = usePathname();
  const { conversations, loading, error } = useConversations();

  return (
    <nav aria-label="대화 목록" className="min-h-0 flex-1 overflow-y-auto px-2">
      {loading ? (
        <div aria-label="대화 목록을 읽는 중" className="flex flex-col gap-2 px-1">
          <Skeleton className="h-10" /><Skeleton className="h-10" /><Skeleton className="h-10" />
        </div>
      ) : error ? (
        <p className="px-2 text-sm text-muted">{error}</p>
      ) : conversations.length === 0 ? (
        <p className="px-2 text-sm text-muted">아직 대화가 없다.</p>
      ) : (
        groupByDate(conversations, new Date()).map((group) => (
          <section key={group.label} className="mb-5">
            <h2 className="px-2 py-2 text-xs font-medium text-muted">{group.label}</h2>
            <ol className="flex flex-col gap-0.5">
              {group.items.map((conversation) => (
                <li key={conversation.id}>
                  <Link href={`/c/${conversation.id}`} onClick={onNavigate}
                    aria-current={pathname === `/c/${conversation.id}` ? "page" : undefined}
                    className={`block truncate rounded-md px-2 py-2 text-sm hover:bg-surface-raised ${
                      pathname === `/c/${conversation.id}` ? "bg-surface-raised font-medium" : ""
                    }`}
                    title={conversation.title || "새 대화"}
                  >{conversation.title || "새 대화"}</Link>
                </li>
              ))}
            </ol>
          </section>
        ))
      )}
    </nav>
  );
}

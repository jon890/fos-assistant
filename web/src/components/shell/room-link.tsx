"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { AgentBadge } from "@/components/agent/agent-badge";
import { requestRoom } from "@/lib/conversation-list";
import type { Conversation } from "./conversations-provider";
import { NavPending } from "./nav-pending";

/** 대화방의 에이전트를 아직 모를 때 보이는 이름이다. 기본 비서의 처음 이름과 같다. */
const FALLBACK_NAME = "비서";

/**
 * 사이드바 맨 위의 대화방 줄이다. 누르면 `/` 로 가고, 서버가 대화방을 열어 그 대화로 넘긴다.
 *
 * <p>대화 목록은 대화방을 빼므로 이 줄이 따로 읽는다. 화면을 옮길 때마다 다시 읽어 처음 연 대화방과 바뀐 에이전트 이름을 보인다.
 * 아직 열지 않았거나 읽지 못하면 기본 이름으로 그린다. 이름 바꾸기와 지우기 메뉴가 없다.
 */
export function RoomLink({ onNavigate }: { onNavigate(href: string): void }) {
  const pathname = usePathname();
  const [room, setRoom] = useState<Conversation | null>(null);
  useEffect(() => {
    let stale = false;
    void requestRoom()
      .then((response) =>
        response.ok ? (response.json() as Promise<Conversation>) : null,
      )
      .catch(() => null)
      .then((found) => {
        if (!stale && found) setRoom(found);
      });
    return () => {
      stale = true;
    };
  }, [pathname]);

  const name = room?.agentName ?? FALLBACK_NAME;
  const current = room !== null && pathname === `/chat/${room.id}`;
  return (
    <Link
      href="/"
      data-testid="room-link"
      aria-current={current ? "page" : undefined}
      onClick={() => onNavigate("/")}
      className={`mb-2 flex shrink-0 items-center gap-2 rounded-md px-3 py-2 text-sm hover:bg-accent hover:text-foreground ${
        current
          ? "bg-accent font-medium text-foreground"
          : "text-muted-foreground"
      }`}
    >
      <AgentBadge
        agent={{
          name,
          code: room?.agentCode ?? null,
          icon: room?.agentIcon,
          color: room?.agentColor,
        }}
        size="sm"
      />
      <span className="min-w-0 truncate">{name}</span>
      <NavPending />
    </Link>
  );
}

"use client";

import Link from "next/link";
import { useId } from "react";
import { ChevronDown, Lock, Settings } from "lucide-react";
import { Popover } from "radix-ui";
import type { AgentView } from "@/lib/agent";
import { capabilitiesLine } from "@/lib/agent-badge";
import { AgentBadge } from "@/components/agent/agent-badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";

type Props = {
  agents: AgentView[];
  selectedCode: string;
  onSelect(code: string): void;
  /** 사진이나 모델 단계를 먼저 골라 빈 대화가 생겼다. 그 대화의 에이전트는 이미 정해졌다 */
  locked: boolean;
};

/** 잠긴 이름표를 누르면 보이는 안내다(docs/features/app-shell/spec.md 의 DECISION22) */
export const LOCKED_AGENT_NOTICE =
  "이미 시작한 대화라 에이전트를 바꿀 수 없어요. 다른 에이전트는 새 대화에서 고를 수 있어요";

/** 에이전트를 읽는 동안 이름표 자리를 잡는 뼈대다. 읽은 뒤 단추가 같은 높이로 들어온다 */
export function AgentSwitcherSkeleton() {
  return (
    <div role="status" className="flex h-9 items-center">
      <span className="sr-only">에이전트를 읽는 중</span>
      <Skeleton className="h-7 w-32 rounded-full" />
    </div>
  );
}

/**
 * 새 대화 화면 위쪽의 이름표 단추다(docs/features/app-shell/spec.md 의 REQ9).
 *
 * <p>누르면 쓸 수 있는 에이전트의 목록이 열린다. 목록은 radio 묶음이라 방향키로 움직이고 `Enter` 로 고른다.
 * 대화가 이미 만들어졌으면 목록 대신 바꿀 수 없는 까닭을 보인다. 눌러도 반응이 없으면 고장으로 보이기 때문이다.
 */
export function AgentSwitcher({
  agents,
  selectedCode,
  onSelect,
  locked,
}: Props) {
  const descriptionId = useId();
  const lockedId = useId();
  const current =
    agents.find((agent) => agent.code === selectedCode) ?? agents[0];
  if (!current) return null;

  const trigger = (
    <Button
      type="button"
      variant="ghost"
      data-testid="agent-switcher"
      data-locked={locked || undefined}
      aria-label={`에이전트 고르기, 지금 ${current.name}`}
      // 잠겼으면 이름은 그대로 두고 바꿀 수 없다는 것을 설명으로 읽힌다.
      aria-describedby={locked ? lockedId : undefined}
      className="h-9 max-w-full min-w-0 gap-2 rounded-full px-2"
    >
      <AgentBadge agent={current} size="sm" />
      <span className="truncate text-sm font-medium">{current.name}</span>
      {locked ? (
        <>
          <Lock aria-hidden="true" className="text-muted-foreground" />
          <span id={lockedId} className="sr-only">
            이미 시작한 대화라 바꿀 수 없어요
          </span>
        </>
      ) : (
        <ChevronDown aria-hidden="true" className="text-muted-foreground" />
      )}
    </Button>
  );

  if (locked) {
    return (
      <Popover.Root>
        <Popover.Trigger asChild>{trigger}</Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            side="bottom"
            align="center"
            sideOffset={6}
            collisionPadding={8}
            aria-label="에이전트를 바꿀 수 없어요"
            data-testid="agent-switcher-locked"
            className="z-50 w-[min(20rem,calc(100vw-1rem))] rounded-lg border bg-popover p-3 text-sm text-popover-foreground shadow-md outline-none"
          >
            {LOCKED_AGENT_NOTICE}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    );
  }

  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
      <DropdownMenuContent
        align="center"
        collisionPadding={8}
        data-testid="agent-switcher-list"
        aria-label="에이전트"
        className="w-[min(20rem,calc(100vw-1rem))]"
      >
        <DropdownMenuRadioGroup
          value={current.code}
          onValueChange={(code) => onSelect(code)}
        >
          {agents.map((agent) => {
            const line = capabilitiesLine(agent.capabilities);
            const lineId = `${descriptionId}-${agent.code}`;
            return (
              <DropdownMenuRadioItem
                key={agent.code}
                value={agent.code}
                // 이름만 접근성 이름으로 둔다. 할 수 있는 일은 설명으로 읽힌다.
                aria-label={agent.name}
                aria-describedby={line ? lineId : undefined}
                className="gap-2 py-1.5"
              >
                <AgentBadge agent={agent} size="sm" />
                <span className="grid min-w-0 flex-1">
                  <span className="truncate font-medium">{agent.name}</span>
                  {line ? (
                    <span
                      id={lineId}
                      className="truncate text-xs text-muted-foreground"
                    >
                      {line}
                    </span>
                  ) : null}
                </span>
              </DropdownMenuRadioItem>
            );
          })}
        </DropdownMenuRadioGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link href="/agents">
            <Settings aria-hidden="true" />
            에이전트 관리
          </Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

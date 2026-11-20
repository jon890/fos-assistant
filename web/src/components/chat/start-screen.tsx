"use client";

import Link from "next/link";
import { createPortal } from "react-dom";
import type { AgentView } from "@/lib/agent";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { cn } from "cn";
import { Skeleton } from "@/components/ui/skeleton";
import { useShellTitleSlot } from "@/components/shell/title-slot";
import { AgentSwitcher, AgentSwitcherSkeleton } from "./agent-switcher";
import { AttentionLine } from "./attention-line";

/*
 * 새 대화 화면은 입력창 위와 아래 두 부품으로 나눈다. 입력창은 대화 화면이 한 자리에 두고 감싸는 요소의
 * 클래스만 바꿔 가운데에서 아래로 옮긴다. 이 화면이 입력창을 받아 제 안에 그리면 첫 메시지 뒤 입력창의
 * 부모가 바뀌어 새로 만들어지고, 그때 정리가 돌아 올려 둔 사진이 지워진다.
 */

type HeaderProps = {
  displayName: string | null;
  agents: AgentView[];
  loading: boolean;
  agentsError: boolean;
  onRetryAgents(): void;
};

/**
 * 입력창 위에 그리는 인사다. 에이전트는 화면 위쪽 이름표로 고르므로 여기에는 카드가 없다
 * (docs/features/app-shell/spec.md 의 REQ9). 목록을 읽지 못했거나 비었을 때의 안내만 남는다.
 */
export function StartScreenHeader({
  displayName,
  agents,
  loading,
  agentsError,
  onRetryAgents,
}: HeaderProps) {
  return (
    <div className="mx-auto mt-auto w-full max-w-3xl pb-2">
      <h1 className="mb-2 text-center text-2xl font-semibold">
        {displayName
          ? `${displayName}님, 무엇을 도와드릴까요?`
          : "무엇을 도와드릴까요?"}
      </h1>
      <AttentionLine />
      {loading ? null : agentsError ? (
        <div role="alert">
          <EmptyState
            title="에이전트 목록을 읽지 못했어요"
            description="잠시 뒤 다시 읽어 주세요."
            action={<Button onClick={onRetryAgents}>다시 읽기</Button>}
          />
        </div>
      ) : agents.length === 0 ? (
        // 사용자는 자기 에이전트를 직접 만들 수 있다. 관리자에게 미루지 않고 만드는 길로 보낸다.
        <div className="flex flex-col items-center gap-3">
          <Notice variant="info" className="text-center">
            아직 쓸 수 있는 에이전트가 없어요. 에이전트를 만들면 바로 대화할 수
            있어요.
          </Notice>
          <Button asChild>
            <Link href="/agents?new=1">에이전트 만들기</Link>
          </Button>
        </div>
      ) : null}
    </div>
  );
}

type SwitcherProps = {
  agents: AgentView[];
  loading: boolean;
  agentsError: boolean;
  selectedCode: string;
  onSelect(code: string): void;
  locked: boolean;
};

/**
 * 새 대화 화면의 이름표 단추를 두 자리에 둔다. 넓은 화면은 대화 영역 맨 위 머리 줄, 휴대폰은 위 막대 가운데다
 * (docs/features/app-shell/spec.md 의 REQ9). 폭은 CSS 로만 나눈다. 머리 줄은 좁은 폭에서 숨고 위 막대는 넓은
 * 폭에서 숨으므로 한 번에 하나만 보인다.
 */
export function StartAgentSwitcher({
  agents,
  loading,
  agentsError,
  selectedCode,
  onSelect,
  locked,
}: SwitcherProps) {
  const titleSlot = useShellTitleSlot();
  if (agentsError || (!loading && agents.length === 0)) return null;
  const switcher = loading ? (
    <AgentSwitcherSkeleton />
  ) : (
    <AgentSwitcher
      agents={agents}
      selectedCode={selectedCode}
      onSelect={onSelect}
      locked={locked}
    />
  );
  return (
    <>
      <div
        data-testid="start-agent-header"
        className="hidden min-w-0 items-center md:flex"
      >
        {switcher}
      </div>
      {titleSlot ? createPortal(switcher, titleSlot) : null}
    </>
  );
}

type PromptsProps = {
  prompts: string[];
  /** 추천을 읽는 중이다. 칩 자리에 뼈대를 그린다 */
  pending: boolean;
  disabled: boolean;
  onPrompt(text: string): void;
};

/**
 * 입력창 아래에 그리는 고른 에이전트의 추천 질문이다. 누르면 입력창을 거치지 않고 바로 보낸다.
 *
 * <p>추천은 에이전트를 고를 때마다 따로 읽어 늦게 도착한다. 이 요소가 위의 인사와 함께 입력창을 세로 가운데로
 * 모으는 여백을 갖고 있어서, 높이가 칩에 따라 변하면 칩이 나타날 때마다 입력창이 위로 밀린다. 그래서 읽는 중이든
 * 비었든 채워졌든 같은 최소 높이를 잡는다. 칩 네 개가 줄바꿈되는 정도가 폭마다 달라 좁은 폭은 세 줄, 넓은 폭은 두 줄을 잡는다.
 */
export function StarterPrompts({
  prompts,
  pending,
  disabled,
  onPrompt,
}: PromptsProps) {
  return (
    <div className="mx-auto mb-auto min-h-32 w-full max-w-3xl pt-3 sm:min-h-24">
      {pending ? (
        // 뼈대는 낭독기에서 숨겨져 있다. 읽는 중이라는 것은 status 안의 글로 알린다.
        <div role="status" className="flex flex-wrap justify-center gap-2">
          <span className="sr-only">추천 질문을 읽는 중</span>
          <Skeleton className="h-8 w-40 rounded-full" />
          <Skeleton className="h-8 w-32 rounded-full" />
          <Skeleton className="h-8 w-36 rounded-full" />
        </div>
      ) : prompts.length > 0 ? (
        <ul
          aria-label="추천 질문"
          className="flex flex-wrap justify-center gap-2"
        >
          {prompts.map((prompt, index) => (
            <li key={index} className="max-w-full">
              <Button
                variant="outline"
                disabled={disabled}
                onClick={() => onPrompt(prompt)}
                className={cn(
                  "max-w-full justify-start whitespace-normal text-left",
                  "rounded-full px-3 py-1.5 font-normal",
                )}
              >
                {prompt}
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

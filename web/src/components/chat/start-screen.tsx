"use client";

import { createPortal } from "react-dom";
import { primaryCode, type AgentView } from "@/lib/agent";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Notice } from "@/components/ui/notice";
import { cn } from "cn";
import { Skeleton } from "@/components/ui/skeleton";
import { useShellTitleSlot } from "@/components/shell/title-slot";
import { AgentBadge } from "@/components/agent/agent-badge";
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

/** 기본 비서가 꺼졌거나 없을 때 새 대화 화면에 보이는 안내다(docs/features/assistant-hub/spec.md 의 REQ4) */
export const PRIMARY_UNAVAILABLE_NOTICE =
  "비서를 쓸 수 없어요. 관리자에게 알려 주세요";

/**
 * 입력창 위에 그리는 인사다. 에이전트는 화면 위쪽 이름표에 보이므로 여기에는 카드가 없다. 목록을 읽지 못했거나
 * 기본 비서가 없을 때의 안내만 남는다.
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
      ) : primaryCode(agents) === "" ? (
        // 새 대화는 기본 비서와만 시작한다. 비서를 끄는 것은 관리자뿐이라 관리자에게 알리게 한다(스펙 assistant-hub DECISION15).
        <Notice
          variant="info"
          data-testid="primary-unavailable"
          className="text-center"
        >
          {PRIMARY_UNAVAILABLE_NOTICE}
        </Notice>
      ) : null}
    </div>
  );
}

type AgentTagProps = {
  agents: AgentView[];
  loading: boolean;
  agentsError: boolean;
  selectedCode: string;
};

/**
 * 새 대화 화면의 에이전트 이름표다. 새 대화는 늘 기본 비서와 시작하므로 고르는 목록 없이 이름만 보인다
 * (docs/features/assistant-hub/spec.md 의 REQ4). 넓은 화면은 대화 영역 맨 위 머리 줄, 휴대폰은 위 막대 가운데다.
 * 폭은 CSS 로만 나눈다. 머리 줄은 좁은 폭에서 숨고 위 막대는 넓은 폭에서 숨으므로 한 번에 하나만 보인다.
 */
export function StartAgentTag({
  agents,
  loading,
  agentsError,
  selectedCode,
}: AgentTagProps) {
  const current = agents.find((agent) => agent.code === selectedCode);
  // 이름표가 없으면 위 막대 가운데 자리를 차지하지 않는다. 그 자리의 홈 링크가 그대로 남는다.
  if (agentsError || (!loading && current === undefined)) return null;
  const tag = current ? (
    <span
      data-testid="start-agent-tag"
      className="flex h-9 min-w-0 items-center gap-2 px-2"
    >
      <AgentBadge agent={current} size="sm" />
      <span
        data-testid="start-agent-name"
        className="truncate text-sm font-medium"
      >
        {current.name}
      </span>
    </span>
  ) : (
    <div role="status" className="flex h-9 items-center">
      <span className="sr-only">에이전트를 읽는 중</span>
      <Skeleton className="h-7 w-32 rounded-full" />
    </div>
  );
  return (
    <>
      <div
        data-testid="start-agent-header"
        className="hidden min-w-0 items-center md:flex"
      >
        {tag}
      </div>
      <TitleSlotPortal>{tag}</TitleSlotPortal>
    </>
  );
}

/** 휴대폰 위 막대 가운데 자리를 차지하고 그 안에 그린다. 그릴 것이 있을 때만 이 부품을 둔다 */
function TitleSlotPortal({ children }: { children: React.ReactNode }) {
  const titleSlot = useShellTitleSlot();
  return titleSlot ? createPortal(children, titleSlot) : null;
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
                // 긴 주소처럼 끊을 자리가 없는 글도 단추 폭 안에서 줄을 바꾼다. 단추가 inline-flex 라 최소 폭 계산에도 들어가야 해 anywhere 를 쓴다.
                className={cn(
                  "h-auto max-w-full justify-start whitespace-normal text-left wrap-anywhere",
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

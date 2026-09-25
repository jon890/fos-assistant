"use client";

import type { AgentView } from "@/lib/agent";
import { Skeleton } from "@/components/ui/skeleton";
import { AGENT_CARD_HEIGHT, AgentPicker } from "./agent-picker";

/*
 * 새 대화 화면은 입력창 위와 아래 두 부품으로 나눈다. 입력창은 대화 화면이 한 자리에 두고 감싸는 요소의
 * 클래스만 바꿔 가운데에서 아래로 옮긴다. 이 화면이 입력창을 받아 제 안에 그리면 첫 메시지 뒤 입력창의
 * 부모가 바뀌어 새로 만들어지고, 그때 정리가 돌아 올려 둔 사진이 지워진다.
 */

type HeaderProps = {
  displayName: string | null;
  agents: AgentView[];
  loading: boolean;
  selectedCode: string;
  onSelect(code: string): void;
  /** 사진을 먼저 올려 빈 대화가 생겼다. 그 대화의 에이전트는 이미 정해졌다 */
  locked: boolean;
};

/** 입력창 위에 그리는 인사와 에이전트 카드다 */
export function StartScreenHeader({ displayName, agents, loading, selectedCode, onSelect, locked }: HeaderProps) {
  const only = agents.length === 1 ? agents[0] : undefined;
  return (
    <div className="mx-auto mt-auto w-full max-w-3xl pb-2">
      <h1 className="mb-6 text-center text-2xl font-semibold">
        {displayName ? `${displayName}님, 무엇을 도와줄까요` : "무엇을 도와줄까요"}
      </h1>
      {loading ? (
        // 뼈대는 낭독기에서 숨겨져 있다. 읽는 중이라는 것은 status 안의 글로 알린다.
        <div role="status" className="flex gap-2 overflow-hidden pb-1">
          <span className="sr-only">에이전트를 읽는 중</span>
          <Skeleton className={`${AGENT_CARD_HEIGHT} w-44 shrink-0`} />
          <Skeleton className={`${AGENT_CARD_HEIGHT} w-44 shrink-0`} />
          <Skeleton className={`${AGENT_CARD_HEIGHT} w-44 shrink-0`} />
        </div>
      ) : agents.length === 0 ? (
        <p className="rounded-md bg-surface px-3 py-2 text-center text-sm">
          쓸 수 있는 에이전트가 없다. 관리자에게 등록을 요청한다.
        </p>
      ) : only ? (
        <div className="text-center">
          <p className="text-sm font-medium">{only.name}</p>
          {only.tagline ? <p className="mt-1 text-sm text-muted">{only.tagline}</p> : null}
        </div>
      ) : (
        <AgentPicker agents={agents} selectedCode={selectedCode} onSelect={onSelect} disabled={locked} />
      )}
    </div>
  );
}

type PromptsProps = {
  prompts: string[];
  disabled: boolean;
  onPrompt(text: string): void;
};

/** 입력창 아래에 그리는 고른 에이전트의 추천 질문이다. 누르면 입력창을 거치지 않고 바로 보낸다 */
export function StarterPrompts({ prompts, disabled, onPrompt }: PromptsProps) {
  // 추천 질문이 없어도 자리는 둔다. 위의 인사와 함께 입력창을 세로 가운데로 모으는 여백이 이 요소에 붙는다.
  return (
    <div className="mx-auto mb-auto w-full max-w-3xl pt-3">
      {prompts.length > 0 ? (
        <ul aria-label="추천 질문" className="flex flex-wrap justify-center gap-2">
          {prompts.map((prompt, index) => (
            <li key={index} className="max-w-full">
              <button
                type="button"
                disabled={disabled}
                onClick={() => onPrompt(prompt)}
                className="max-w-full rounded-full border border-border px-3 py-1.5 text-left text-sm hover:bg-surface disabled:opacity-50"
              >
                {prompt}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

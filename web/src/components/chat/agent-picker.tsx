"use client";

import { useRef, type KeyboardEvent } from "react";
import type { AgentView } from "@/lib/agent";

type Props = {
  agents: AgentView[];
  selectedCode: string;
  onSelect(code: string): void;
  /** 대화의 에이전트가 이미 정해졌다. 사진을 먼저 올려 빈 대화가 생기면 참이다 */
  disabled?: boolean;
};

/** 카드 한 장의 높이다. 에이전트를 읽는 동안 그리는 뼈대도 같은 높이를 쓴다 */
export const AGENT_CARD_HEIGHT = "h-20";

/** 새 대화 화면의 에이전트 카드 묶음이다. 라디오 묶음처럼 방향키로 고름과 초점이 함께 옮겨 간다 */
export function AgentPicker({ agents, selectedCode, onSelect, disabled = false }: Props) {
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  const selectedIndex = Math.max(0, agents.findIndex((agent) => agent.code === selectedCode));

  function move(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const step = event.key === "ArrowRight" || event.key === "ArrowDown" ? 1
      : event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 0;
    if (step === 0 || disabled) return;
    event.preventDefault();
    const next = (index + step + agents.length) % agents.length;
    buttons.current[next]?.focus();
    onSelect(agents[next]!.code);
  }

  return (
    <div
      role="radiogroup"
      aria-label="에이전트"
      aria-disabled={disabled || undefined}
      className="flex gap-2 overflow-x-auto pb-1"
    >
      {agents.map((agent, index) => {
        const checked = index === selectedIndex;
        return (
          <button
            key={agent.code}
            ref={(element) => { buttons.current[index] = element; }}
            type="button"
            role="radio"
            aria-checked={checked}
            tabIndex={checked ? 0 : -1}
            disabled={disabled}
            onClick={() => onSelect(agent.code)}
            onKeyDown={(event) => move(event, index)}
            className={`flex ${AGENT_CARD_HEIGHT} w-44 shrink-0 flex-col items-start gap-1 overflow-hidden rounded-xl border px-3 py-2.5 text-left hover:bg-surface disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-transparent ${
              checked ? "border-brand" : "border-border"
            }`}
          >
            <span className="w-full truncate text-sm font-medium leading-5">{agent.name}</span>
            {agent.tagline ? (
              <span className="line-clamp-2 w-full text-xs leading-4 text-muted">{agent.tagline}</span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

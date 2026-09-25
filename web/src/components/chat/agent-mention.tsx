"use client";

import type { AgentView } from "@/lib/agent";

type Props = {
  /** 목록 요소의 id 다. 입력칸의 `aria-controls` 와 `aria-activedescendant` 가 이 값으로 가리킨다 */
  id: string;
  agents: AgentView[];
  query: string;
  activeIndex: number;
  onPick(code: string): void;
};

/**
 * 커서 앞에서 가장 가까운 `@` 와 그 뒤 글자를 찾는다.
 *
 * <p>`@` 가 글의 맨 앞이거나 바로 앞이 공백이고, `@` 와 커서 사이에 공백이 없을 때만 돌려준다.
 * 이메일 주소 속의 `@` 에서 목록이 뜨지 않게 하기 위해서다.
 */
export function findMention(value: string, caret: number): { start: number; query: string } | null {
  const before = value.slice(0, caret);
  const start = before.lastIndexOf("@");
  if (start < 0) return null;
  const query = before.slice(start + 1);
  if (/\s/.test(query)) return null;
  if (start > 0 && !/\s/.test(before[start - 1]!)) return null;
  return { start, query };
}

/** 이름에 `query` 가 들어 있는 에이전트다. 대소문자를 가리지 않고, 빈 `query` 는 전부다 */
export function filterAgents(agents: AgentView[], query: string): AgentView[] {
  const needle = query.toLowerCase();
  return agents.filter((agent) => agent.name.toLowerCase().includes(needle));
}

export function mentionOptionId(listId: string, index: number): string {
  return `${listId}-option-${index}`;
}

/** 입력창 위에 뜨는 에이전트 고르기 목록이다. 초점은 입력칸에 둔 채 방향키로 줄을 옮긴다 */
export function AgentMention({ id, agents, query, activeIndex, onPick }: Props) {
  const matches = filterAgents(agents, query);
  return (
    <ul
      id={id}
      role="listbox"
      aria-label="에이전트 고르기"
      className="absolute inset-x-0 bottom-full z-10 mb-2 max-h-60 overflow-y-auto rounded-2xl border border-border bg-background p-1 shadow"
    >
      {matches.length === 0 ? (
        <li role="option" aria-selected={false} aria-disabled="true" className="px-3 py-2 text-sm text-muted">
          맞는 에이전트가 없다
        </li>
      ) : (
        matches.map((agent, index) => (
          <li
            key={agent.code}
            id={mentionOptionId(id, index)}
            role="option"
            aria-selected={index === activeIndex}
            // 누르는 동안 입력칸이 초점을 잃으면 커서 자리를 알 수 없다.
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onPick(agent.code)}
            className={`cursor-pointer rounded-xl px-3 py-2 hover:bg-surface ${index === activeIndex ? "bg-surface" : ""}`}
          >
            <span className="block truncate text-sm font-medium">{agent.name}</span>
            {agent.tagline ? <span className="block truncate text-xs text-muted">{agent.tagline}</span> : null}
          </li>
        ))
      )}
    </ul>
  );
}

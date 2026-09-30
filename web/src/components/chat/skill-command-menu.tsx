"use client";

import { cn } from "cn";
import { SKILL_NAME_PATTERN, type SkillListView } from "@/lib/skill";

type Props = {
  /** 목록 요소의 id 다. 입력칸의 `aria-controls` 와 `aria-activedescendant` 가 이 값으로 가리킨다 */
  id: string;
  /** 이 에이전트에서 커맨드로 부를 수 있는 스킬 이름 전부다. 비었으면 스킬이 없다고 알린다 */
  names: string[];
  query: string;
  activeIndex: number;
  onPick(name: string): void;
};

/**
 * 메시지 맨 앞의 스킬 커맨드다. Control Plane 의 `SkillCommand` 판별식과 같다.
 *
 * <p>이름 뒤에 공백이나 끝이 올 때만 커맨드다. `/usr/bin` 은 커맨드가 아니다.
 */
const SKILL_COMMAND_PATTERN = /^\/([a-z0-9][a-z0-9-]{0,63})(\s|$)/;

/** 메시지가 스킬 커맨드이면 그 이름과 이름 뒤의 글을 돌려준다. 이름 뒤 공백 하나는 뺀다 */
export function parseSkillCommand(text: string): { name: string; rest: string } | null {
  const match = SKILL_COMMAND_PATTERN.exec(text);
  if (!match) return null;
  return { name: match[1]!, rest: text.slice(match[0].length) };
}

/**
 * 커맨드로 부를 수 있는 이름이다. `skills` toolset 이 꺼져 있으면 없다.
 *
 * <p>Hermes 기본 스킬에는 점이나 밑줄이 든 이름이 있다. Control Plane 은 그 이름을 커맨드로 보지 않으므로 뺀다.
 */
export function commandSkillNames(list: SkillListView): string[] {
  if (!list.skillsToolsetEnabled) return [];
  return list.skills.filter((skill) => skill.enabled && SKILL_NAME_PATTERN.test(skill.name)).map((skill) => skill.name);
}

/**
 * 글이 `/` 로 시작하고 커서가 첫 낱말 안에 있으면 `/` 와 커서 사이의 글자를 돌려준다.
 *
 * <p>커서 앞에 공백이 있으면 이름을 다 친 것으로 보고 목록을 닫는다.
 */
export function findSkillQuery(value: string, caret: number): string | null {
  if (!value.startsWith("/") || caret < 1) return null;
  const query = value.slice(1, caret);
  return /\s/.test(query) ? null : query;
}

/** 이름에 `query` 가 들어 있는 스킬이다. 빈 `query` 는 전부다 */
export function filterSkillNames(names: string[], query: string): string[] {
  const needle = query.toLowerCase();
  return names.filter((name) => name.includes(needle));
}

/** 고른 이름으로 글의 첫 낱말을 바꾼다. 커서는 돌려준 글의 `/이름 ` 바로 뒤에 둔다 */
export function withSkillCommand(value: string, name: string): { value: string; caret: number } {
  const tokenEnd = value.search(/\s/);
  const rest = tokenEnd < 0 ? "" : value.slice(tokenEnd).replace(/^\s+/, "");
  const command = `/${name} `;
  return { value: command + rest, caret: command.length };
}

export function skillOptionId(listId: string, index: number): string {
  return `${listId}-option-${index}`;
}

/** 입력창 위에 뜨는 스킬 고르기 목록이다. 초점은 입력칸에 둔 채 방향키로 줄을 옮긴다 */
export function SkillCommandMenu({ id, names, query, activeIndex, onPick }: Props) {
  const matches = filterSkillNames(names, query);
  return (
    <ul
      id={id}
      role="listbox"
      aria-label="스킬 고르기"
      className={cn("absolute inset-x-0 bottom-full z-10 mb-2 max-h-60 overflow-y-auto",
        "rounded-2xl border border-border bg-background p-1 shadow")}
    >
      {names.length === 0 ? (
        <li role="option" aria-selected={false} aria-disabled="true" className="px-3 py-2 text-sm text-muted-foreground">
          이 에이전트에는 스킬이 없어요
        </li>
      ) : (
        matches.map((name, index) => (
          <li
            key={name}
            id={skillOptionId(id, index)}
            role="option"
            aria-selected={index === activeIndex}
            // 누르는 동안 입력칸이 초점을 잃으면 커서 자리를 알 수 없다.
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onPick(name)}
            className="cursor-pointer rounded-xl px-3 py-2 hover:bg-muted aria-selected:bg-muted"
          >
            <span className="block truncate text-sm font-medium">/{name}</span>
          </li>
        ))
      )}
    </ul>
  );
}

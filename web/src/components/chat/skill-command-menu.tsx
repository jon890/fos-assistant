"use client";

import { cn } from "cn";
import { filterSkillNames, skillOptionId } from "./skill-command";

type Props = {
  /** 목록 요소의 id 다. 입력칸의 `aria-controls` 와 `aria-activedescendant` 가 이 값으로 가리킨다 */
  id: string;
  /** 이 에이전트에서 커맨드로 부를 수 있는 스킬 이름 전부다. 비었으면 스킬이 없다고 알린다 */
  names: string[];
  query: string;
  activeIndex: number;
  onPick(name: string): void;
};

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

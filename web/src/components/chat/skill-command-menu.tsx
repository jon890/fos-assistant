"use client";

import { cn } from "cn";
import {
  filterSkillNames,
  skillDescriptionLine,
  skillOptionId,
} from "./skill-command";

type Props = {
  /** 목록 요소의 id 다. 입력칸의 `aria-controls` 와 `aria-activedescendant` 가 이 값으로 가리킨다 */
  id: string;
  /** 이 에이전트에서 커맨드로 부를 수 있는 스킬 이름 전부다. 비었으면 스킬이 없다고 알린다 */
  names: string[];
  /** 이름마다의 스킬 설명이다. 있으면 이름 아래 한 줄로 줄여 보인다 */
  descriptions?: Readonly<Record<string, string>>;
  query: string;
  activeIndex: number;
  onPick(name: string): void;
};

/**
 * 입력창 위에 뜨는 스킬 고르기 목록이다. 초점은 입력칸에 둔 채 방향키로 줄을 옮긴다.
 *
 * <p>줄마다 `/이름` 아래에 스킬 설명을 한 줄로 보이고 넘치면 말줄임으로 자른다. 설명이 없으면 이름만 보인다.
 */
export function SkillCommandMenu({
  id,
  names,
  descriptions,
  query,
  activeIndex,
  onPick,
}: Props) {
  const matches = filterSkillNames(names, query);
  return (
    <ul
      id={id}
      role="listbox"
      aria-label="스킬 고르기"
      className={cn(
        "absolute inset-x-0 bottom-full z-10 mb-2 max-h-60 overflow-y-auto",
        "rounded-lg border border-border bg-popover p-1 shadow",
      )}
    >
      {names.length === 0 ? (
        <li
          role="option"
          aria-selected={false}
          aria-disabled="true"
          className="px-3 py-2 text-sm text-muted-foreground"
        >
          이 에이전트에는 스킬이 없어요
        </li>
      ) : (
        matches.map((name, index) => {
          const description = skillDescriptionLine(descriptions?.[name]);
          return (
            <li
              key={name}
              id={skillOptionId(id, index)}
              role="option"
              aria-selected={index === activeIndex}
              // 누르는 동안 입력칸이 초점을 잃으면 커서 자리를 알 수 없다.
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => onPick(name)}
              className="cursor-pointer rounded-md px-3 py-2 hover:bg-muted aria-selected:bg-muted"
            >
              <span
                data-testid="skill-option-name"
                className="block truncate text-sm font-medium"
              >
                /{name}
              </span>
              {description ? (
                <span
                  data-testid="skill-option-description"
                  className="block truncate text-xs text-muted-foreground"
                >
                  {description}
                </span>
              ) : null}
            </li>
          );
        })
      )}
    </ul>
  );
}

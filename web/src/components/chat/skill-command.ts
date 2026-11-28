// node --test 가 이 파일을 직접 읽으므로 `@/` 별칭 대신 확장자를 붙인 상대 경로로 가져온다.
import { SKILL_NAME_PATTERN, type SkillListView } from "../../lib/skill.ts";

/**
 * 스킬 커맨드의 이름 뒤에 올 수 있는 공백이다. Control Plane(Java) 정규식의 `\s` 와 같은 여섯 글자다.
 *
 * <p>JavaScript 의 `\s` 는 전각 공백과 NBSP 같은 유니코드 공백까지 받는다. 그대로 쓰면 Control Plane 이 커맨드로
 * 보지 않는 글에 칩이 붙고 `/` 목록이 닫혀 둘이 어긋난다.
 */
const COMMAND_SPACE = /[ \t\n\v\f\r]/;
const LEADING_COMMAND_SPACE = /^[ \t\n\v\f\r]+/;

/**
 * 메시지 맨 앞의 스킬 커맨드다. Control Plane 의 `SkillCommand` 판별식과 같다.
 *
 * <p>이름 뒤에 공백이나 끝이 올 때만 커맨드다. `/usr/bin` 은 커맨드가 아니다.
 */
const SKILL_COMMAND_PATTERN = /^\/([a-z0-9][a-z0-9-]{0,63})([ \t\n\v\f\r]|$)/;

/** 메시지가 스킬 커맨드이면 그 이름과 이름 뒤의 글을 돌려준다. 이름 뒤 공백 하나는 뺀다 */
export function parseSkillCommand(
  text: string,
): { name: string; rest: string } | null {
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
  return list.skills
    .filter((skill) => skill.enabled && SKILL_NAME_PATTERN.test(skill.name))
    .map((skill) => skill.name);
}

/**
 * 글이 `/` 로 시작하고 커서가 첫 낱말 안에 있으면 `/` 와 커서 사이의 글자를 돌려준다.
 *
 * <p>커서 앞에 공백이 있으면 이름을 다 친 것으로 보고 목록을 닫는다.
 */
export function findSkillQuery(value: string, caret: number): string | null {
  if (!value.startsWith("/") || caret < 1) return null;
  const query = value.slice(1, caret);
  return COMMAND_SPACE.test(query) ? null : query;
}

/** 이름에 `query` 가 들어 있는 스킬이다. 빈 `query` 는 전부다 */
export function filterSkillNames(names: string[], query: string): string[] {
  const needle = query.toLowerCase();
  return names.filter((name) => name.includes(needle));
}

/** 커맨드로 부를 수 있는 스킬마다의 설명이다. `/` 목록이 이름 아래 한 줄로 보인다 */
export function commandSkillDescriptions(
  list: SkillListView,
): Record<string, string> {
  return Object.fromEntries(
    list.skills.map((skill) => [skill.name, skill.description ?? ""]),
  );
}

/**
 * 목록 줄에 보일 설명 한 줄이다. 줄바꿈과 겹친 공백을 한 칸으로 줄인다. 길면 화면이 말줄임으로 자른다.
 */
export function skillDescriptionLine(description: string | undefined): string {
  return (description ?? "").replace(/\s+/g, " ").trim();
}

/** 고른 이름으로 글의 첫 낱말을 바꾼다. 커서는 돌려준 글의 `/이름 ` 바로 뒤에 둔다 */
export function withSkillCommand(
  value: string,
  name: string,
): { value: string; caret: number } {
  const tokenEnd = value.search(COMMAND_SPACE);
  const rest =
    tokenEnd < 0
      ? ""
      : value.slice(tokenEnd).replace(LEADING_COMMAND_SPACE, "");
  const command = `/${name} `;
  return { value: command + rest, caret: command.length };
}

export function skillOptionId(listId: string, index: number): string {
  return `${listId}-option-${index}`;
}

/** 맡길 수 있는 전문 에이전트와 그 켜진 스킬이다. Control Plane 의 목록 API 가 준다(docs/features/assistant-hub/spec.md 의 REQ10). */
export type SkillDelegationTarget = {
  agentCode: string;
  agentName: string;
  skills: string[];
  /** 스킬 이름마다의 설명이다. 이 칸이 없는 옛 응답이면 목록에 이름만 보인다 */
  skillDescriptions?: Record<string, string>;
};

/**
 * `/` 목록에 비서 스킬 뒤로 붙일 「에이전트 이름 · 스킬」 이다. 고르면 입력창에 `/에이전트 이름 · 스킬 ` 이 들어간다.
 * 예약 작업 지시의 꼴과 같다.
 */
export function delegationLabels(
  targets: readonly SkillDelegationTarget[],
): string[] {
  return targets.flatMap((target) =>
    target.skills.map((skill) => `${target.agentName} · ${skill}`),
  );
}

/** 「에이전트 이름 · 스킬」 항목마다의 스킬 설명이다 */
export function delegationDescriptions(
  targets: readonly SkillDelegationTarget[],
): Record<string, string> {
  return Object.fromEntries(
    targets.flatMap((target) =>
      target.skills.map((skill) => [
        `${target.agentName} · ${skill}`,
        target.skillDescriptions?.[skill] ?? "",
      ]),
    ),
  );
}

/** Control Plane 의 `DelegatedSkillCommand` 판별식과 같다. 이름에 줄바꿈과 가운뎃점이 없다. */
const DELEGATED_PATTERN =
  /^\/([^\n·]+?) · ([a-z0-9][a-z0-9-]{0,63})([ \t\n\v\f\r]|$)/;

/**
 * 글이 목록에 있는 「에이전트 · 스킬」 로 시작하면 맡을 에이전트를 돌려준다. 아니면 null 이고 보통 보내기로 간다.
 *
 * <p>Control Plane 이 이름이 겹치거나 이 꼴로 다시 읽히지 않는 에이전트를 목록에서 빼므로, 글 하나는 에이전트 하나로 정해진다.
 */
export function delegatedTargetOf(
  text: string,
  targets: readonly SkillDelegationTarget[],
): SkillDelegationTarget | null {
  const match = DELEGATED_PATTERN.exec(text);
  if (!match) return null;
  const name = match[1]!.trim();
  const skill = match[2]!;
  return (
    targets.find(
      (target) => target.agentName === name && target.skills.includes(skill),
    ) ?? null
  );
}

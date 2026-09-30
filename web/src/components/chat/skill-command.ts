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
  return COMMAND_SPACE.test(query) ? null : query;
}

/** 이름에 `query` 가 들어 있는 스킬이다. 빈 `query` 는 전부다 */
export function filterSkillNames(names: string[], query: string): string[] {
  const needle = query.toLowerCase();
  return names.filter((name) => name.includes(needle));
}

/** 고른 이름으로 글의 첫 낱말을 바꾼다. 커서는 돌려준 글의 `/이름 ` 바로 뒤에 둔다 */
export function withSkillCommand(value: string, name: string): { value: string; caret: number } {
  const tokenEnd = value.search(COMMAND_SPACE);
  const rest = tokenEnd < 0 ? "" : value.slice(tokenEnd).replace(LEADING_COMMAND_SPACE, "");
  const command = `/${name} `;
  return { value: command + rest, caret: command.length };
}

export function skillOptionId(listId: string, index: number): string {
  return `${listId}-option-${index}`;
}

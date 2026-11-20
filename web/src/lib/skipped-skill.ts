/**
 * 붙이기가 그 에이전트에 설치하지 못한 에이전트용 스킬 하나다. `reasons` 는 대시보드가 정한 까닭 낱말이다.
 * 원본 계약은 `docs/features/agent-skill/spec.md` 의 REQ8, REQ9 다.
 */
export type SkippedSkill = { name: string; reasons: string[] };

const NAME_TAKEN_MESSAGE =
  "같은 이름의 스킬이 이미 있어요. 그 스킬의 이름을 바꾸거나 지우면 함께 설치돼요.";
const NO_SANDBOX_MESSAGE =
  "이 에이전트에는 이 스킬을 돌릴 실행 환경이 없어요. 관리자에게 문의해 주세요.";
const TERMINAL_OFF_MESSAGE = "터미널을 켜면 잠시 뒤 함께 설치돼요.";

/**
 * 빠진 스킬 하나의 안내 문구를 정한 순서(이름 겹침, 실행 환경, 터미널)로 모두 돌려준다.
 * 터미널 문구는 까닭이 터미널 꺼짐뿐일 때만 낸다. 다른 까닭이 함께 있으면 터미널만 켜서는 설치되지 않는다.
 * 모르는 낱말은 문구를 만들지 않는다.
 */
export function skippedSkillMessages(reasons: readonly string[]): string[] {
  const messages: string[] = [];
  if (reasons.includes("name_taken")) messages.push(NAME_TAKEN_MESSAGE);
  if (
    reasons.includes("sandbox_unregistered") ||
    reasons.includes("sandbox_env_missing")
  ) {
    messages.push(NO_SANDBOX_MESSAGE);
  }
  if (
    reasons.length > 0 &&
    reasons.every((reason) => reason === "terminal_off")
  ) {
    messages.push(TERMINAL_OFF_MESSAGE);
  }
  return messages;
}

/** 붙이면 실제로 설치되는 스킬이다. 설치할 수 있는 스킬에서 빠진 스킬을 뺀다. */
export function installedSkills(
  skills: readonly string[],
  skipped: readonly SkippedSkill[],
): string[] {
  const missing = new Set(skipped.map((item) => item.name));
  return skills.filter((name) => !missing.has(name));
}

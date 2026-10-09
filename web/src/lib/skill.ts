/** 스킬 이름 형식이다. 백엔드의 `SkillStore` 가 두는 64자 상한과 같다. `new` 는 백엔드가 따로 거절한다. */
export const SKILL_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

/**
 * Hermes 가 가진 스킬까지 포함한 스킬 이름 형식이다. 켜고 끄기처럼 Hermes 기본 스킬도 다루는 경로가 쓴다.
 * Hermes 는 소문자, 숫자, 점, 밑줄, 붙임표로 64자까지 받는다(`hermes/docs/hermes-contract.md`). 첫 글자를
 * 영문 소문자나 숫자로 묶어 `.` 과 `..` 같은 이름이 경로에 들어가지 않게 한다. 백엔드 `SkillService` 와 같다.
 */
export const HERMES_SKILL_NAME_PATTERN = /^[a-z0-9][a-z0-9._-]{0,63}$/;

/** 스킬 하나의 호출 수와 마지막 호출 시각이다. 호출이 없으면 `lastInvokedAt` 은 null 이다. */
export type SkillUsageView = {
  count: number;
  lastInvokedAt: string | null;
};

/** 스킬 목록의 한 줄이다. `usage` 는 관리하는 사람에게만 온다. */
export type SkillItemView = {
  name: string;
  description: string;
  /** `UPLOADED` 는 이 화면에서 올린 스킬이고 `HERMES` 는 Hermes 기본 스킬이다. */
  source: "UPLOADED" | "HERMES";
  enabled: boolean;
  usage?: SkillUsageView;
};

export type SkillListView = {
  skills: SkillItemView[];
  /** 요청자가 스킬을 올리고 지울 수 있다. */
  editable: boolean;
  /** 그 에이전트의 API 실행에 `skills` toolset 이 켜져 있다. */
  skillsToolsetEnabled: boolean;
  /** 올릴 수 있는 스킬 수의 한도. 새 스킬을 만들 때만 본다. */
  uploadLimit: number;
};

/** 참고 파일의 경로와 UTF-8 바이트 크기다. */
export type SkillFileView = {
  path: string;
  size: number;
  content: string;
};

/** 올린 스킬 하나다. `body` 는 앞머리를 포함한 `SKILL.md` 원문이다. */
export type SkillDetailView = {
  name: string;
  description: string;
  body: string;
  files: SkillFileView[];
};

/**
 * 내가 부른 스킬의 호출 이력 한 줄이다. 에이전트와 스킬 이름마다 하나다.
 *
 * <p>`lastConversationId` 는 마지막 호출이 속한 대화의 공개 식별자이고, 그 대화를 지웠으면 null 이다.
 * `agentCode` 는 관리자에게만 오고 그 밖의 사용자에게는 null 이다.
 * 행이 없는 에이전트는 `agentName` 이 null 이다.
 */
export type SkillUsageRow = {
  agentCode: string | null;
  agentName: string | null;
  skillName: string;
  count: number;
  lastInvokedAt: string;
  lastConversationId: string | null;
};

/**
 * 새 스킬의 description 이 넘을 수 없는 글자 수다. Hermes 가 새 스킬을 만들 때 거는 한도다.
 * backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java 의 같은 이름 상수와 함께 고친다.
 */
export const MAX_NEW_DESCRIPTION_CHARS = 60;

/**
 * description 이 넘을 수 없는 글자 수다. Hermes 가 저장할 때마다 거는 한도다.
 * backend/src/main/java/com/bifos/assistant/skill/application/SkillService.java 의 같은 이름 상수와 함께 고친다.
 */
export const MAX_DESCRIPTION_CHARS = 1024;

/**
 * 새 스킬의 60자 한도에 견주는 description 글자 수다. 앞뒤 공백을 뺀 뒤 양 끝의 `'` 와 `"` 를 몇 개든 빼고,
 * 이모지가 둘로 세어지지 않게 code point 로 센다. 백엔드 `SkillFrontmatter.indexedDescriptionLength` 와 함께 고친다.
 */
export function indexedDescriptionLength(value: string): number {
  return Array.from(
    value
      .trim()
      .replace(/^['"]+/, "")
      .replace(/['"]+$/, ""),
  ).length;
}

/**
 * 화면이 description 글자 수를 세도 되는 값인지 본다. 여러 줄이면 서버가 값을 이어 붙이고, 백슬래시나 `''` 는
 * YAML 이 따옴표 안에서 한 글자로 줄이며, 여는 따옴표가 남았으면 뒤에 주석이 붙어 따옴표를 못 벗긴 것이다.
 * 이런 값을 화면이 세면 서버보다 많이 세어 저장할 수 있는 값을 막으므로, 세지 않고 서버에 맡긴다.
 */
export function isCountableDescription(
  value: string,
  multiline: boolean,
): boolean {
  return !multiline && !/[\\]|''|^['"]/.test(value);
}

/** 앞머리 뒤의 글이 공백뿐이 아니면 참이다. 백엔드는 본문이 빈 스킬을 거절한다. */
export function hasBodyAfterFrontmatter(rest: string): boolean {
  return rest.trim() !== "";
}

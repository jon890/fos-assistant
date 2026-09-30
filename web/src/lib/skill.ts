/** 스킬 이름 형식이다. 백엔드의 `SkillStore` 가 두는 64자 상한과 같다. `new` 는 백엔드가 따로 거절한다. */
export const SKILL_NAME_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

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
};

/** 참고 파일의 경로와 UTF-8 바이트 크기다. */
export type SkillFileView = {
  path: string;
  size: number;
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
 */
export type SkillUsageRow = {
  agentCode: string;
  agentName: string;
  skillName: string;
  count: number;
  lastInvokedAt: string;
  lastConversationId: string | null;
};

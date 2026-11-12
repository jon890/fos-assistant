export type AdminAgent = {
  id: number;
  code: string;
  name: string;
  hermesProfile: string;
  apiBaseUrl: string;
  costMode: string;
  credentialScope: string;
  visibility: "PRIVATE" | "GROUP";
  ownerUserId: number | null;
  enabled: boolean;
  /** 「먼저 살펴보기에 쓰기 도구 허용」. 관리자만 바꾸고 관리자 응답에만 온다(ADR-082) */
  proactiveCheckWritesAllowed: boolean;
  /** 한 줄 소개, 아이콘, 색. 관리자 영역 상세가 남의 비공개 에이전트의 소개를 이 값으로 그린다 */
  description?: string | null;
  icon?: string | null;
  color?: string | null;
};

/** 에이전트가 할 수 있는 일이다. 붙어 쓸 수 있는 플러그인 이름과 올린 스킬 이름이다(docs/features/agent-team.md 「소개와 할 수 있는 일」). */
export type AgentCapabilities = {
  connectors: string[];
  skills: string[];
};

/** 사용자가 쓸 수 있는 에이전트 한 줄이다. 관리 화면의 `AdminAgent` 보다 정보가 적다. */
export type AgentView = {
  code: string;
  name: string;
  visibility: "PRIVATE" | "GROUP";
  /** 이 에이전트의 대화에 사진을 붙일 수 있다. 사진을 받는다고 선언하지 않은 연결용 에이전트는 거짓이다 */
  acceptsAttachments: boolean;
  /** 요청자가 이 에이전트를 관리할 수 있다. 주인이거나 `ADMIN` 이다 */
  editable: boolean;
  /** 요청자가 이 에이전트의 주인이다 */
  ownedByMe: boolean;
  /** 이 에이전트가 요청자의 기본 비서다. 기본 비서는 지울 수 없다. 옛 응답에는 없을 수 있다 */
  primary?: boolean;
  /** 한 줄 소개. 없으면 null 이다 */
  description?: string | null;
  /** 아이콘 목록(`agent-badge.ts` 의 `AGENT_ICONS`)의 이모지. 없으면 이름 첫 글자를 그린다 */
  icon?: string | null;
  /** 색 이름. 없으면 `code` 로 정한 자동 색이다 */
  color?: string | null;
  /** 할 수 있는 일. 옛 응답에는 없을 수 있다 */
  capabilities?: AgentCapabilities;
};

/** 한 에이전트의 성격이다. 본문은 데이터베이스가 아니라 Hermes 의 `SOUL.md` 가 갖는다 */
export type PersonaView = {
  body: string;
  bodyHash: string;
  editable: boolean;
  maxChars: number;
};

/** 에이전트 코드 형식이다. 백엔드의 `AgentDtos` 와 `HermesProfileName` 이 두는 64자 상한을 같이 둔다. */
export const AGENT_CODE_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

/** 주소 쿼리의 에이전트 번호를 읽는다. 하나뿐이고 형식이 맞을 때만 돌려준다. */
export function agentCodeParam(
  value: string | string[] | null | undefined,
): string | null {
  return typeof value === "string" && AGENT_CODE_PATTERN.test(value)
    ? value
    : null;
}

/**
 * 새 대화 화면에 보일 추천 질문이다. `GENERATING` 은 Control Plane 이 만드는 중이라 `prompts` 가 비어 있다.
 */
export type StartersView = {
  prompts: string[];
  status: "READY" | "GENERATING" | "NONE";
};

/** 에이전트 도구 선택 화면의 한 줄이다. 등급과 변경 가능 여부는 Control Plane 이 계산한다. */
export type ToolsetView = {
  name: string;
  label: string;
  description: string;
  tier: "OWNER" | "ADMIN";
  enabled: boolean;
  editable: boolean;
  requiresPrivate: boolean;
  hidden?: boolean;
};

/** profile 에서 읽은 도구와 등급 표에 없는 켜진 도구다. */
export type AgentToolsView = {
  toolsets: ToolsetView[];
  unclassifiedEnabled: string[];
  /** 숨김과 무관한 실제 활성 상태다. 연결 안내가 사용한다. */
  shellOrFileEnabled: boolean;
  skillsEnabled: boolean;
};

export const PRIVATE_VISIBILITY: AdminAgent["visibility"] = "PRIVATE";
export const GROUP_VISIBILITY: AdminAgent["visibility"] = "GROUP";

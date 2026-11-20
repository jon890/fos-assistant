/**
 * 작업 과정의 접힌 줄에 잇는 「쓴 것의 종류」 다(docs/features/app-shell/spec.md REQ13, REQ14).
 *
 * <p>지금 문장 표(`tool-label.ts`)의 도구를 사람 말 종류로 묶는다. 플러그인 도구는 서비스 이름이 종류다.
 * 이 파일은 `tool-label.ts` 밖의 모듈을 부르지 않는다. 단위 테스트가 `node --test` 로 직접 불러서다.
 */
import { bareName, type ConnectorToolName } from "./tool-label.ts";

/** 아이콘을 고르는 종류 번호다. 이름은 화면 글자가 아니다. */
export type ToolKindId =
  | "search"
  | "page"
  | "memory"
  | "files"
  | "image"
  | "artifact"
  | "follow-up"
  | "skill"
  | "draft"
  | "order-limit"
  | "approval"
  | "image-gen"
  | "video-gen"
  | "browser"
  | "computer"
  | "schedule"
  | "todo"
  | "clarify"
  | "speech"
  | "history"
  | "discord"
  | "agents"
  | "connections"
  | "tool-search"
  | "helper"
  | "connector"
  | "other";

export type ToolKind = { id: ToolKindId; name: string };

const KINDS: Record<
  Exclude<ToolKindId, "connector" | "other">,
  { name: string; tools: string[] }
> = {
  search: { name: "웹 검색", tools: ["web_search"] },
  page: { name: "페이지 읽기", tools: ["web_extract"] },
  memory: {
    name: "기억",
    tools: [
      "memory_read",
      "memory_search",
      "memory_remember",
      "memory_link",
      "memory_forget",
    ],
  },
  files: {
    name: "파일 작업",
    tools: [
      "terminal",
      "process_manage",
      "execute_code",
      "read_file",
      "write_file",
      "patch",
      "search_files",
    ],
  },
  image: {
    name: "사진 보기",
    tools: [
      "vision_analyze",
      "attachment_inspect",
      "list_media_observations",
      "record_media_observation",
    ],
  },
  artifact: {
    name: "결과물 저장",
    tools: ["artifact_write", "file_space_put"],
  },
  "follow-up": {
    name: "할 일",
    tools: ["follow_up_propose", "follow_up_list", "follow_up_done"],
  },
  skill: { name: "스킬", tools: ["skill_view", "skills_list", "skill_manage"] },
  draft: {
    name: "원고",
    tools: [
      "content_draft_write",
      "content_draft_read",
      "content_draft_image",
      "content_draft_preview",
      "content_draft_save",
    ],
  },
  "order-limit": {
    name: "주문 상한",
    tools: ["order_limit_read", "order_limit_change"],
  },
  approval: { name: "승인한 작업", tools: ["approval_resume"] },
  "image-gen": { name: "그림 만들기", tools: ["image_generate"] },
  "video-gen": {
    name: "영상 만들기",
    tools: ["video_generate", "xai_video_edit", "xai_video_extend"],
  },
  browser: {
    name: "브라우저",
    tools: ["browser_exec", "browser_cdp", "browser_dialog", "browser_vault"],
  },
  computer: { name: "화면 조작", tools: ["computer_use"] },
  schedule: {
    name: "예약 작업",
    tools: [
      "cronjob_manage",
      "task_list",
      "task_create",
      "task_update",
      "task_set_state",
      "task_delete",
    ],
  },
  todo: { name: "작업 목록", tools: ["todo_list"] },
  clarify: { name: "되묻기", tools: ["clarify"] },
  speech: { name: "음성 만들기", tools: ["text_to_speech"] },
  history: { name: "지난 대화 찾기", tools: ["session_search"] },
  discord: { name: "Discord", tools: ["discord", "discord_admin"] },
  agents: {
    name: "다른 에이전트",
    tools: ["agent_list", "agent_delegate", "agent_status", "agent_stop"],
  },
  connections: {
    name: "외부 서비스 연결",
    tools: ["connector_list", "connector_attach"],
  },
  "tool-search": {
    name: "도구 찾기",
    tools: ["tool_search", "tool_describe", "tool_call"],
  },
  // 하위 에이전트 사건(`SUBAGENT`)과 그것을 만드는 도구가 같은 종류다.
  helper: { name: "도우미", tools: ["delegate_task"] },
};

const BY_TOOL = new Map<string, ToolKind>(
  Object.entries(KINDS).flatMap(([id, kind]) =>
    kind.tools.map(
      (tool) => [tool, { id: id as ToolKindId, name: kind.name }] as const,
    ),
  ),
);

export const HELPER_KIND: ToolKind = { id: "helper", name: KINDS.helper.name };

/** 도구 하나의 종류다. */
export function toolKind(
  toolName: string | null,
  connector?: ConnectorToolName | null,
): ToolKind {
  if (connector) return { id: "connector", name: connector.service };
  const name = bareName(toolName);
  const known = name === null ? undefined : BY_TOOL.get(name);
  if (known) return known;
  if (toolName?.startsWith("mcp__"))
    return { id: "connector", name: "연결된 서비스" };
  return { id: "other", name: "그 밖의 도구" };
}

/** 쓴 것 하나다. 메시지 조회의 `activity.uses` 와 흘러온 항목이 같은 꼴로 바뀐다. */
export type ActivityUse =
  | {
      kind: "TOOL";
      toolName: string | null;
      connector?: ConnectorToolName | null;
    }
  | { kind: "SUBAGENT" };

export type ActivityOutcome = "done" | "stopped" | "failed";

const HEADS: Record<ActivityOutcome, string | null> = {
  done: null,
  stopped: "하다가 멈췄어요",
  failed: "끝까지 하지 못했어요",
};

/** 접힌 줄에 잇는 종류의 수 상한이다. 넘으면 「외 N」 이다. */
const SHOWN_KINDS = 3;

/**
 * 끝난 블록의 접힌 한 줄이다.
 *
 * <p>종류를 처음 쓴 차례로 셋까지 가운뎃점으로 잇는다. 같은 종류는 한 번만 세고 횟수는 적지 않는다.
 * 중지와 실패는 머리말 뒤에 잇고, 쓴 것이 하나도 없으면 「차례로 정리했어요」 다.
 */
export function activitySummaryLabel(
  uses: ActivityUse[],
  outcome: ActivityOutcome,
): string {
  const names: string[] = [];
  for (const use of uses) {
    const name =
      use.kind === "SUBAGENT"
        ? HELPER_KIND.name
        : toolKind(use.toolName, use.connector).name;
    if (!names.includes(name)) names.push(name);
  }
  const head = HEADS[outcome];
  if (names.length === 0) return head ?? "차례로 정리했어요";
  const rest = names.length - SHOWN_KINDS;
  const list =
    names.slice(0, SHOWN_KINDS).join(" · ") + (rest > 0 ? ` 외 ${rest}` : "");
  return head ? `${head} · ${list}` : list;
}

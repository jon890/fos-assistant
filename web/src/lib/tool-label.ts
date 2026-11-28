/**
 * 도구 이름을 사람 말 문장으로 옮긴다.
 *
 * <p>도는 줄과 끝난 줄의 말이 다르다. 표에 없는 도구와 이름이 없는 도구는 일반 문장으로 보인다.
 * 플러그인 도구는 Control Plane 이 붙여 보낸 서비스 이름과 도구 제목(`connector`)으로 「서비스 이름 · 도구 제목」 이다.
 * `agent_delegate` 는 Control Plane 이 맡김 대상에서 정한 에이전트 이름이 있으면 「이름에게 맡기고 있어요」 다. 모델이 준 인자는 쓰지 않는다.
 * 이 파일은 다른 모듈을 부르지 않는다. 단위 테스트가 `node --test` 로 직접 불러서다.
 */

/** 플러그인 도구의 서비스 이름과 도구 제목이다. Control Plane 이 플러그인 선언에서 찾아 붙인다. */
export type ConnectorToolName = { service: string; title: string | null };

type Label = { running: string; done: string };

const LABELS: Record<string, Label> = {
  terminal: { running: "작업하고 있어요", done: "작업을 했어요" },
  read_file: { running: "파일을 읽고 있어요", done: "파일을 읽었어요" },
  write_file: { running: "파일을 쓰고 있어요", done: "파일을 썼어요" },
  patch: { running: "파일을 고치고 있어요", done: "파일을 고쳤어요" },
  search_files: { running: "파일에서 찾고 있어요", done: "파일에서 찾았어요" },
  skill_view: {
    running: "스킬 안내를 읽고 있어요",
    done: "스킬 안내를 읽었어요",
  },
  vision_analyze: { running: "사진을 보고 있어요", done: "사진을 봤어요" },
  web_search: { running: "검색하고 있어요", done: "검색했어요" },
  web_extract: {
    running: "웹 페이지를 읽고 있어요",
    done: "웹 페이지를 읽었어요",
  },
  product_page_read: {
    running: "상품 페이지를 읽고 있어요",
    done: "상품 페이지를 읽었어요",
  },
  artifact_write: {
    running: "결과물을 저장하고 있어요",
    done: "결과물을 저장했어요",
  },
  file_space_put: {
    running: "파일 공간에 넣고 있어요",
    done: "파일 공간에 넣었어요",
  },
  memory_read: { running: "기억을 떠올리고 있어요", done: "기억을 떠올렸어요" },
  memory_search: { running: "기억을 찾고 있어요", done: "기억을 찾았어요" },
  memory_remember: {
    running: "기억해 두고 있어요",
    done: "기억해 뒀어요",
  },
  // 대체는 제안만 남긴다. 바꾼 것처럼 읽히지 않게 쓴다.
  memory_link: {
    running: "기억을 서로 잇고 있어요",
    done: "기억을 서로 이었어요",
  },
  // 사용자가 시킨 것은 바로 적고 스스로 판단한 것은 제안한다. 둘 다 맞게 읽히도록 쓴다.
  follow_up_propose: {
    running: "할 일을 남기고 있어요",
    done: "할 일을 남겼어요",
  },
  follow_up_list: {
    running: "할 일을 보고 있어요",
    done: "할 일을 봤어요",
  },
  // 대상이 여럿이거나 사람이 시키지 않은 실행이면 끝내지 않는다. 끝낸 것처럼 읽히지 않게 쓴다.
  follow_up_done: {
    running: "끝낼 할 일을 찾고 있어요",
    done: "끝낼 할 일을 확인했어요",
  },
  // 지우기는 승인 카드만 만든다. 지운 것처럼 읽히지 않게 쓴다.
  memory_forget: {
    running: "기억 삭제 승인을 요청하고 있어요",
    done: "기억 삭제 승인을 요청했어요",
  },
  content_draft_preview: {
    running: "원고 미리보기를 만들고 있어요",
    done: "원고 미리보기를 만들었어요",
  },
  // 승인 카드만 만들고 저장은 하지 않는다. 저장한 것처럼 읽히지 않게 쓴다.
  content_draft_save: {
    running: "임시저장 승인을 요청하고 있어요",
    done: "임시저장 승인을 요청했어요",
  },
  order_limit_read: {
    running: "주문 상한을 읽고 있어요",
    done: "주문 상한을 읽었어요",
  },
  // 올리기는 승인 카드만 만든다. 바꾼 것처럼 읽히지 않게 쓴다.
  order_limit_change: {
    running: "주문 상한 변경을 요청하고 있어요",
    done: "주문 상한 변경을 요청했어요",
  },
  approval_resume: {
    running: "승인한 작업을 이어 가고 있어요",
    done: "승인한 작업을 이어 갔어요",
  },
  // 아래부터는 켜는 도구 묶음(toolset)과 Control Plane MCP 의 나머지 도구다.
  // 빠진 도구는 `test/repo/tool-label-coverage.test.ts` 가 도구를 등록하는 곳과 대조해 잡는다.
  process_manage: {
    running: "실행 중인 작업을 살피고 있어요",
    done: "실행 중인 작업을 살폈어요",
  },
  execute_code: {
    running: "코드를 실행하고 있어요",
    done: "코드를 실행했어요",
  },
  skills_list: {
    running: "스킬 목록을 보고 있어요",
    done: "스킬 목록을 봤어요",
  },
  // fos-ctx 가 늘 막는 도구다. 고친 것처럼 읽히지 않게 쓴다.
  skill_manage: {
    running: "스킬 고치기를 시도하고 있어요",
    done: "스킬 고치기를 시도했어요",
  },
  attachment_inspect: {
    running: "사진 원본을 보고 있어요",
    done: "사진 원본을 봤어요",
  },
  list_media_observations: {
    running: "사진 관찰 기록을 읽고 있어요",
    done: "사진 관찰 기록을 읽었어요",
  },
  record_media_observation: {
    running: "사진 관찰을 남기고 있어요",
    done: "사진 관찰을 남겼어요",
  },
  image_generate: {
    running: "그림을 만들고 있어요",
    done: "그림을 만들었어요",
  },
  video_generate: {
    running: "영상을 만들고 있어요",
    done: "영상을 만들었어요",
  },
  xai_video_edit: { running: "영상을 고치고 있어요", done: "영상을 고쳤어요" },
  xai_video_extend: {
    running: "영상을 늘이고 있어요",
    done: "영상을 늘였어요",
  },
  browser_exec: {
    running: "브라우저를 다루고 있어요",
    done: "브라우저를 다뤘어요",
  },
  browser_cdp: {
    running: "브라우저를 다루고 있어요",
    done: "브라우저를 다뤘어요",
  },
  browser_dialog: {
    running: "브라우저 창에 답하고 있어요",
    done: "브라우저 창에 답했어요",
  },
  browser_vault: {
    running: "브라우저 로그인 정보를 쓰고 있어요",
    done: "브라우저 로그인 정보를 썼어요",
  },
  computer_use: {
    running: "화면을 조작하고 있어요",
    done: "화면을 조작했어요",
  },
  cronjob_manage: {
    running: "예약 실행을 다루고 있어요",
    done: "예약 실행을 다뤘어요",
  },
  task_list: {
    running: "예약 작업을 보고 있어요",
    done: "예약 작업을 봤어요",
  },
  task_create: {
    running: "예약 작업을 만들고 있어요",
    done: "예약 작업을 만들었어요",
  },
  task_update: {
    running: "예약 작업을 고치고 있어요",
    done: "예약 작업을 고쳤어요",
  },
  task_set_state: {
    running: "예약 작업의 상태를 바꾸고 있어요",
    done: "예약 작업의 상태를 바꿨어요",
  },
  // 지우기는 승인 카드만 만든다. 지운 것처럼 읽히지 않게 쓴다.
  task_delete: {
    running: "예약 작업 삭제 승인을 요청하고 있어요",
    done: "예약 작업 삭제 승인을 요청했어요",
  },
  todo_list: {
    running: "작업 목록을 정리하고 있어요",
    done: "작업 목록을 정리했어요",
  },
  clarify: { running: "되묻고 있어요", done: "되물었어요" },
  text_to_speech: {
    running: "음성으로 바꾸고 있어요",
    done: "음성으로 바꿨어요",
  },
  session_search: {
    running: "지난 대화를 찾고 있어요",
    done: "지난 대화를 찾았어요",
  },
  discord: { running: "Discord 를 쓰고 있어요", done: "Discord 를 썼어요" },
  discord_admin: {
    running: "Discord 를 관리하고 있어요",
    done: "Discord 를 관리했어요",
  },
  delegate_task: {
    running: "도우미에게 맡기고 있어요",
    done: "도우미에게 맡겼어요",
  },
  agent_list: {
    running: "맡길 에이전트를 찾고 있어요",
    done: "맡길 에이전트를 찾았어요",
  },
  agent_delegate: {
    running: "다른 에이전트에게 맡기고 있어요",
    done: "다른 에이전트에게 맡겼어요",
  },
  agent_status: {
    running: "맡긴 일을 확인하고 있어요",
    done: "맡긴 일을 확인했어요",
  },
  agent_stop: {
    running: "맡긴 일을 멈추고 있어요",
    done: "맡긴 일을 멈췄어요",
  },
  content_draft_write: { running: "원고를 쓰고 있어요", done: "원고를 썼어요" },
  content_draft_read: {
    running: "원고를 읽고 있어요",
    done: "원고를 읽었어요",
  },
  content_draft_image: {
    running: "원고에 그림을 넣고 있어요",
    done: "원고에 그림을 넣었어요",
  },
  connector_list: {
    running: "연결한 서비스를 확인하고 있어요",
    done: "연결한 서비스를 확인했어요",
  },
  // 붙이기는 승인 카드만 만든다. 붙인 것처럼 읽히지 않게 쓴다.
  connector_attach: {
    running: "붙이기 승인을 요청하고 있어요",
    done: "붙이기 승인을 요청했어요",
  },
  browser_tabs: {
    running: "내 브라우저의 탭을 보고 있어요",
    done: "내 브라우저의 탭을 봤어요",
  },
  // 닫기와 열기는 승인 카드만 만든다. 한 것처럼 읽히지 않게 쓴다.
  browser_tab_close: {
    running: "탭 닫기 승인을 요청하고 있어요",
    done: "탭 닫기 승인을 요청했어요",
  },
  browser_tab_open: {
    running: "탭 열기 승인을 요청하고 있어요",
    done: "탭 열기 승인을 요청했어요",
  },
  tool_search: {
    running: "쓸 도구를 찾고 있어요",
    done: "쓸 도구를 찾았어요",
  },
  tool_describe: {
    running: "도구 설명을 읽고 있어요",
    done: "도구 설명을 읽었어요",
  },
  tool_call: {
    running: "찾은 도구를 쓰고 있어요",
    done: "찾은 도구를 썼어요",
  },
};

/** 문장 표에 있는 도구 이름이다. 등록되지 않은 이름이 표에 남지 않는지 `test/repo/tool-label-coverage.test.ts` 가 본다. */
export const LABELED_TOOLS: readonly string[] = Object.keys(LABELS);

/** 표에 없는 MCP 도구다. 도구 이름 원문을 보이지 않고 연결된 서비스를 썼다고만 알린다. */
const CONNECTED: Label = {
  running: "연결된 서비스를 쓰고 있어요",
  done: "연결된 서비스를 썼어요",
};

const UNKNOWN: Label = { running: "도구를 쓰고 있어요", done: "도구를 썼어요" };

const MCP_PREFIX = "mcp__";

/** MCP 도구는 `mcp__{서버}__{도구}` 로 온다. 마지막 `__` 뒤의 이름을 낸다. */
export function bareName(toolName: string | null): string | null {
  return toolName !== null && toolName.startsWith(MCP_PREFIX)
    ? toolName.slice(toolName.lastIndexOf("__") + 2)
    : toolName;
}

/** 플러그인 도구의 이름이다. 제목을 선언하지 않은 도구는 서비스 이름만이다. */
export function connectorLabel(connector: ConnectorToolName): string {
  return connector.title
    ? `${connector.service} · ${connector.title}`
    : connector.service;
}

export function toolLabel(
  toolName: string | null,
  running: boolean,
  connector?: ConnectorToolName | null,
  delegatedAgentName?: string | null,
): string {
  // 플러그인 도구는 도는 중이든 끝났든 같은 이름이다(spec REQ15).
  if (connector) return connectorLabel(connector);
  const name = bareName(toolName);
  // 맡은 에이전트를 알면 그 이름을 쓴다(스펙 assistant-hub DECISION30). 모르면 아래 표의 문장이다.
  const agent = delegatedAgentName?.trim();
  if (name === "agent_delegate" && agent)
    return running ? `${agent}에게 맡기고 있어요` : `${agent}에게 맡겼어요`;
  const label =
    name !== null && Object.hasOwn(LABELS, name)
      ? LABELS[name]!
      : toolName !== null && toolName.startsWith(MCP_PREFIX)
        ? CONNECTED
        : UNKNOWN;
  return running ? label.running : label.done;
}

/** 줄에 바로 보여도 되는 `detail` 인가. 검색어와 사진 질문만 사람 말이다. */
export function isReadableDetail(toolName: string | null): boolean {
  const name = bareName(toolName);
  return name === "web_search" || name === "vision_analyze";
}

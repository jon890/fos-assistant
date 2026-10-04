/**
 * 도구 이름을 사람 말 문장으로 옮긴다.
 *
 * <p>도는 줄과 끝난 줄의 말이 다르다. 표에 없는 도구와 이름이 없는 도구는 일반 문장으로 보인다.
 * 이 파일은 다른 모듈을 부르지 않는다. 단위 테스트가 `node --test` 로 직접 불러서다.
 */
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
  artifact_write: {
    running: "결과물을 저장하고 있어요",
    done: "결과물을 저장했어요",
  },
  memory_read: { running: "기억을 떠올리고 있어요", done: "기억을 떠올렸어요" },
  follow_up_propose: {
    running: "할 일을 제안하고 있어요",
    done: "할 일을 제안했어요",
  },
};

/** 표에 없는 MCP 도구다. 도구 이름 원문을 보이지 않고 연결된 서비스를 썼다고만 알린다. */
const CONNECTED: Label = {
  running: "연결된 서비스를 쓰고 있어요",
  done: "연결된 서비스를 썼어요",
};

const UNKNOWN: Label = { running: "도구를 쓰고 있어요", done: "도구를 썼어요" };

const MCP_PREFIX = "mcp__";

/** MCP 도구는 `mcp__{서버}__{도구}` 로 온다. 마지막 `__` 뒤의 이름을 낸다. */
function bareName(toolName: string | null): string | null {
  return toolName !== null && toolName.startsWith(MCP_PREFIX)
    ? toolName.slice(toolName.lastIndexOf("__") + 2)
    : toolName;
}

export function toolLabel(toolName: string | null, running: boolean): string {
  const name = bareName(toolName);
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

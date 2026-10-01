/**
 * 도구 이름을 사람 말 문장으로 옮긴다.
 *
 * <p>도는 줄과 끝난 줄의 말이 다르다. 표에 없는 도구와 이름이 없는 도구는 일반 문장으로 보인다.
 * 이 파일은 다른 모듈을 부르지 않는다. 단위 테스트가 `node --test` 로 직접 불러서다.
 */
const LABELS: Record<string, { running: string; done: string }> = {
  terminal: { running: "작업을 실행하는 중", done: "작업 실행" },
  read_file: { running: "파일을 읽는 중", done: "파일 읽기" },
  write_file: { running: "파일을 쓰는 중", done: "파일 쓰기" },
  patch: { running: "파일을 고치는 중", done: "파일 고치기" },
  search_files: { running: "파일에서 찾는 중", done: "파일에서 찾기" },
  skill_view: { running: "스킬 안내를 읽는 중", done: "스킬 안내 읽기" },
  vision_analyze: { running: "사진을 보는 중", done: "사진 보기" },
  web_search: { running: "검색하는 중", done: "검색" },
  web_extract: { running: "웹 페이지를 읽는 중", done: "웹 페이지 읽기" },
  artifact_write: { running: "결과물을 저장하는 중", done: "결과물 저장" },
  memory_read: { running: "기억을 읽는 중", done: "기억 읽기" },
};

const UNKNOWN = { running: "도구를 쓰는 중", done: "도구 사용" };

export function toolLabel(toolName: string | null, running: boolean): string {
  // MCP 도구는 `mcp__{서버}__{도구}` 로 온다. 마지막 `__` 뒤의 이름으로 표를 찾는다.
  const name =
    toolName !== null && toolName.startsWith("mcp__")
      ? toolName.slice(toolName.lastIndexOf("__") + 2)
      : toolName;
  const label =
    (name !== null && Object.hasOwn(LABELS, name) ? LABELS[name] : undefined) ??
    UNKNOWN;
  return running ? label.running : label.done;
}

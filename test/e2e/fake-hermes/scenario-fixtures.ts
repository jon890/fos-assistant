import { join } from "node:path";
import { mkdirSync, writeFileSync, existsSync, readdirSync, readFileSync } from "node:fs";

/** Control Plane 이 스킬 커맨드를 바꿔 보낸 입력에 든 `skill_view` 호출이다. 이름 규칙은 올린 스킬과 같다. */
export const SKILL_VIEW_CALL = /skill_view\(name="([a-z0-9][a-z0-9-]{0,63})"\)/;

/**
 * Hermes 가 스스로 가진 스킬이다. 올린 스킬과 이름이 같으면 올린 것이 가려지므로 Control Plane 이 같은
 * 이름을 거절해야 한다. 목록의 `HERMES` 출처도 이것으로 본다.
 */
export const BUILTIN_SKILLS = [
  { name: "hermes-help", description: "Hermes 사용법을 안내한다" },
  // Hermes 이름 규칙은 올린 스킬 규칙과 달리 점과 밑줄을 받는다. 켜고 끄기 검사가 이 이름을 쓴다.
  { name: "note_taking.v2", description: "메모를 정리한다" },
] as const;

/** 도구 가리기 검사만 쓰는 가짜 값이다. 실제 연결 값이 아니다. */
export const TOOL_DETAIL_SECRETS = [
  "12345678-1234-5678-9012-123456789abc",
  "short-test-secret",
  "ghp_redactionexample",
] as const;

export const TOOL_DETAIL_SAMPLE = JSON.stringify({
  id: TOOL_DETAIL_SECRETS[0],
  token: TOOL_DETAIL_SECRETS[1],
  note: TOOL_DETAIL_SECRETS[2],
  price: 12000,
});

/** 이 글을 입력으로 보내면 세션 조회가 실행에 실어 보낸 것과 다른 모델을 답한다. */
export const SESSION_MODEL_PROBE = "세션 모델 검사";

/**
 * 그 실행이 실제로 쓴 모델을 정한다. 세션 행만 이 값을 갖는다.
 *
 * <p>가격을 검사하는 화면 검사들이 입력으로 모델을 고른다. 요청에 실어 보낸 모델과 다르게 답해야 실제로
 * 돈 모델을 읽고 있는지 알 수 있다.
 */
export function actualModelFor(input: string, requested: string): string {
  if (input === SESSION_MODEL_PROBE) return "example-provider/example-model-c";
  if (input === "가격 없음 검사") return "unknown-model";
  if (input === "무료 모델 검사") return "gpt-zero";
  return requested;
}

/**
 * Chief 에게만 주는 지시에 들어 있는 말이다.
 *
 * <p>Control Plane 의 `ResearchAndBuildFlow` 가 만드는 지시와 같아야 한다. 어긋나면 흐름 검사가
 * 계약을 지키지 않는 답을 받아 실패한다.
 */
export const CHIEF_MARK = "조사할 것과 만들 것을 나눈다";

/**
 * 흐름의 첫 단계가 돌려줄 답을 정한다.
 *
 * <p>Chief 의 지시에는 사용자가 보낸 글이 그대로 들어 있어, 그 글로 어떤 답을 줄지 고른다.
 */
export function chiefOutputFor(input: string): string {
  if (input.includes("흐름 계약 위반 검사")) return "JSON 이 아니라 그냥 문장이다";
  if (input.includes("흐름 단독 검사")) return '{"research":"","build":""}';
  return '{"research":"전기차 보조금을 조사한다","build":"비교 표를 만든다"}';
}

/**
 * Control Plane 이 모든 대화 실행에 붙이는 묻는 형식 안내의 첫 줄이다. `AskFormat.GUIDE` 와 같아야 한다.
 *
 * <p>대역은 받은 instructions 를 답에 되돌려 준다. 안내에는 `<ask>` 예시가 있어서 그대로 두면 모든 답에 카드가 그려진다.
 * 그래서 되돌릴 때 그 구역만 뺀다. 안내가 붙었는지는 `lastSubmittedInstructions()` 로 따로 본다.
 */
export const ASK_GUIDE_HEADER = "# 사용자에게 물을 때";

/** 안내의 마지막 줄 앞머리다. 안내 구역이 어디서 끝나는지 이것으로 안다. */
export const ASK_GUIDE_LAST_LINE = "- 왜 묻는지는";

/** 이 글을 보내면 결과물 폴더에 HTML 과 그것이 부르는 사진을 쓰고 답한다. */
export const ARTIFACT_PROBE = "결과물 파일 검사";

/** 이 글을 보내면 폴더 이름이 같은 `초안/index.html` 을 두 상위 폴더에 하나씩 쓰고 답한다. */
export const ARTIFACT_SAME_NAME_PROBE = "같은 이름 결과물 파일 검사";

/** 실제 MCP 호출로 HTML과 CSS를 저장하는지 보는 입력이다. */
export const ARTIFACT_WRITE_PROBE = "MCP 결과물 파일 검사";

/**
 * 이 글 뒤에 공백과 Memory 번호를 붙여 보내면 그 run 안에서 `memory_read` 를 부르고, 도구 결과의 text 를 답으로 돌려준다.
 */
export const MEMORY_READ_PROBE = "MCP Memory 읽기 검사";

/** 이 글과 빈칸 뒤의 JSON 을 `follow_up_propose` 의 인자로 실어 부르고, 도구 결과의 text 를 답으로 돌려준다. */
export const FOLLOW_UP_PROPOSE_PROBE = "MCP 할 일 제안 검사";

/**
 * 이 글로 시작하는 입력을 받으면 그 run 안에서 하위 에이전트 session 을 등록하고, 답으로 자식 session 을 돌려준다.
 *
 * <p>실제 플러그인의 `subagent_start` hook 처럼 부모 run 이 끝나기 전에 등록을 마친다. 자식의 도구 호출은 시나리오가
 * 부모가 끝난 뒤 `readMemoryAsSubagent` 로 부른다.
 */
export const SUBAGENT_MEMORY_PROBE = "MCP 하위 에이전트 검사";

/**
 * 기존 자식 검사와 같은 흐름이되, 자식 session 응답에 부모 바인딩과 다른 provider 와 모델을 싣는 입력이다.
 * 자식 금액이 부모의 가격이 아니라 자식의 provider 와 모델로 계산되는지 본다.
 */
export const SUBAGENT_PROVIDER_PROBE = "자식 provider 확인 검사";

/**
 * 이 글로 시작하는 입력을 받으면 그 뒤의 줄마다 `<등록 이름> <JSON 인자>` 를 읽어, profile 플러그인의 hook 처럼
 * 차례로 Control Plane 에 판정을 묻는다. 답은 줄마다 `<등록 이름>: allow` 나 `<등록 이름>: block <글>` 이다.
 * `allow` 인 호출만 커넥터 서버에 닿은 것으로 치고 `connectorToolCalls` 에 남긴다.
 */
export const CONNECTOR_TOOL_PROBE = "커넥터 도구 검사";

/** 이 글을 보내면 도구를 부르지 않고 답만 한다. 사건 스트림에는 답 조각과 끝 사건만 온다. */
export const NO_TOOL_CALL_PROBE = "도구 없는 실행 검사";

/**
 * 허용된 커넥터 도구 호출의 완료 사건이 싣는 결과 미리보기다. 외부 서비스가 돌려준 글을 뜻하는 가짜 값이고, 실행 기록과 화면에는
 * 어디에도 남지 않아야 한다.
 */
export const CONNECTOR_RESULT_SAMPLE = "외부-결과-4821";

/** 허용된 커넥터 도구 호출의 `query` 인자로 보내 시작 사건의 미리보기에 실리는 가짜 값이다. 실행 기록과 화면에는 남지 않아야 한다. */
export const CONNECTOR_ARGUMENT_SAMPLE = "외부-인자-4821";

/**
 * 이 글을 보내면 `terminal` 도구 사건을 많이 보내고, 도구 줄 하나를 시작만 한 채 `releaseLongActivity` 를
 * 기다린다. 첫 번째로 풀리면 그 줄을 끝내고 열 쌍과 시작만 한 줄 하나를 더 보내 다시 기다린다. 두 번째로 풀리면 그 줄을
 * 끝내고 열 쌍을 더 보낸 뒤 스트림을 닫는다. 펼친 작업 과정 목록의 높이와 스크롤, 맨 아래를 따라가는 동작을 보려는 입력이다.
 */
export const LONG_ACTIVITY_PROBE = "긴 작업 과정 검사";

/**
 * Control Plane 이 먼저 살펴보기 입력에 넣는 지침 읽기 호출이다. 입력에 이것이 있으면 살펴보기 실행으로 본다.
 *
 * <p>`ProactiveCheckRun.OPENING` 과 같아야 한다. 어긋나면 살펴보기 실행이 보통 실행으로 답을 받아 결과 블록이 없다.
 */
export const PROACTIVE_CHECK_CALL = 'skill_view(name="proactive-check")';

/** 각본 없는 살펴보기가 내는 발견 하나의 주제 키와 원문 주소다. 브라우저 검사가 두 번째 답의 「이미 알린 것이에요」 를 이것으로 안다. */
export const PROACTIVE_DEFAULT_FINDING = {
  topicKey: "study:e2e-sample",
  title: "검사용 공부 자료",
  sourceUrl: "https://example.com/e2e/study",
} as const;

/** 결과 블록 JSON 을 답 끝에 둔 답 글을 만든다. 블록 앞의 글은 Control Plane 이 버린다. */
export function proactiveOutput(block: Record<string, unknown>): string {
  return `살펴본 결과를 정리했다.\n\n<fos-check-result>\n${JSON.stringify(block, null, 2)}\n</fos-check-result>`;
}

/**
 * 각본 없는 살펴보기의 답이다. 발견 하나가 「새로 알릴 것」 의 조건을 모두 갖춘다.
 *
 * <p>확인 시각은 답을 만드는 지금이다. 고정 시각이면 Control Plane 이 「이번에 다시 확인하지 않았어요」 로 내린다.
 */
export function defaultProactiveOutput(): string {
  return proactiveOutput({
    version: 1,
    outcome: "FINDINGS",
    summary: "공부 자료 하나를 찾았어요",
    findings: [
      {
        area: "study",
        topicKey: PROACTIVE_DEFAULT_FINDING.topicKey,
        title: PROACTIVE_DEFAULT_FINDING.title,
        sourceUrl: PROACTIVE_DEFAULT_FINDING.sourceUrl,
        checkedAt: new Date().toISOString(),
        freshness: "CURRENT",
        whyItMatters: "검사에서만 쓰는 합성 발견이에요",
        facts: ["검사용 원문에 적힌 합성 사실"],
        next: { type: "QUESTION", text: "이 자료를 이번 주에 읽어 볼까요" },
      },
    ],
  });
}

/** 각본 없는 살펴보기가 흘리는 도구 사건이다. */
export const DEFAULT_PROACTIVE_TOOLS = ["web_search", "web_extract"];

/** 묻는 카드를 그리는지 보는 검사가 보내는 글이다. 이 글에는 답 끝에 `<ask>` 를 둔 답을 준다. */
export const ASK_CARD_PROBE = "묻는 카드 검사";

/** Control Plane 이 추천 질문을 만들 때 입력 첫 줄에 두는 표지다. */
export const STARTER_MARK = "[추천 질문 만들기]";

/** 추천 질문을 만드는 입력에 주는 답이다. 실제 모델처럼 JSON 문자열 배열 하나만 답한다. */
export const FAKE_STARTER_PROMPTS = ["이번 주 일정 정리해 줘", "장보기 목록 만들어 줘", "오늘 날씨 알려 줘", "가계부 요약해 줘"];

export function specialOutputFor(input: string): string | null {
  if (input.startsWith(STARTER_MARK)) {
    return JSON.stringify(FAKE_STARTER_PROMPTS);
  }
  if (input === ASK_CARD_PROBE) {
    return [
      "사진을 다 봤어. 두 가지만 알려 줘.",
      "",
      "<ask>",
      '<question header="식당 이름">어느 식당에 다녀왔어?</question>',
      "<option>행복담</option>",
      '<option description="사진의 간판 글자">행복한 담벼락</option>',
      '<question header="먹은 메뉴" multiple="true">무엇을 먹었어?</question>',
      "<option>국밥</option>",
      "<option>수육</option>",
      "</ask>",
    ].join("\n");
  }
  if (input.includes(CHIEF_MARK)) {
    return chiefOutputFor(input);
  }
  if (input === ARTIFACT_PROBE || input === ARTIFACT_SAME_NAME_PROBE) {
    return "초안을 만들었다.";
  }
  if (input === "마크다운 보안 검사") {
    return [
      "| 항목 | 값 |",
      "| --- | --- |",
      "| 표 | 정상 |",
      "",
      "<script>window.__unsafeAgentHtml = true</script>",
    ].join("\n");
  }
  if (input === "구분 줄 없는 표 검사") {
    return "번호 | 구분 | 금액\n1 | 식비 | 100\n2 | 교통 | 200\n3 | 기타 | 300";
  }
  if (input === "긴 답 스트림 검사") {
    return Array.from({ length: 80 }, (_, index) => `${index + 1}번째 긴 답 줄`).join("\n\n");
  }
  if (input === "코드 블록 검사") {
    return [
      "```java",
      "public class Greeting {",
      "  // 인사 횟수",
      "  private static final int COUNT = 3;",
      "  void greet() {",
      "    System.out.println(\"안녕하세요\");",
      "  }",
      "}",
      "```",
    ].join("\n");
  }
  return null;
}

export function withoutAskGuide(instructions: string): string {
  const start = instructions.indexOf(ASK_GUIDE_HEADER);
  if (start < 0) return instructions;
  const last = instructions.indexOf(ASK_GUIDE_LAST_LINE, start);
  if (last < 0) {
    // 안내의 마지막 줄이 바뀐 것이다. 끝을 짐작해 뒤에 붙은 다시 생성 지시까지 지우지 말고 곧바로 드러낸다.
    throw new Error(`묻는 형식 안내의 끝 줄 "${ASK_GUIDE_LAST_LINE}" 을 찾지 못했다. AskFormat.GUIDE 와 맞춘다`);
  }
  const lineEnd = instructions.indexOf("\n", last);
  const end = lineEnd < 0 ? instructions.length : lineEnd;
  return (instructions.slice(0, start).replace(/\n+$/, "") + instructions.slice(end)).replace(/^\n+/, "");
}

/** 모델 지침은 답에 복사하지 않고, Memory 본문은 기존처럼 되돌려 검증한다. */
export function withoutResponseGuide(instructions: string): string {
  const header = "# 답변 형식\n\n";
  if (!instructions.startsWith(header)) return instructions;

  const nextSection = instructions.indexOf("\n\n", header.length);
  return withoutMemoryGuide(withoutToolCallGuide(nextSection < 0 ? "" : instructions.slice(nextSection + 2)));
}

/** 답변 형식 뒤에 붙는 로컬 MCP 단건 호출 지침이다. 빈 줄 없는 한 단락이라 그 단락만 뺀다. */
function withoutToolCallGuide(instructions: string): string {
  const header = "# 도구 호출\n\n";
  if (!instructions.startsWith(header)) return instructions;
  const nextSection = instructions.indexOf("\n\n", header.length);
  return nextSection < 0 ? "" : instructions.slice(nextSection + 2);
}

/** `memory_remember` 를 받는 실행에 답변 형식 뒤에 붙는 「# 기억」 지침이다. 빈 줄 없는 한 단락이라 그 단락만 뺀다. */
export function withoutMemoryGuide(instructions: string): string {
  const header = "# 기억\n\n";
  if (!instructions.startsWith(header)) return instructions;
  const nextSection = instructions.indexOf("\n\n", header.length);
  return nextSection < 0 ? "" : instructions.slice(nextSection + 2);
}

/** Control Plane 이 모든 실행 입력 맨 앞에 붙이는 결과물 폴더 단락의 첫 줄이다. `ArtifactService` 와 같아야 한다. */
export const ARTIFACT_HEADER = "[결과물 폴더]";

/** 결과물 폴더 단락 바로 뒤에 붙는 스킬 관리 단락의 첫 줄이다. `SkillAgentNotice` 와 같아야 한다. */
export const SKILL_NOTICE_HEADER = "[스킬 관리]";

/**
 * 입력 맨 앞의 결과물 폴더 단락과 그 뒤의 스킬 관리 단락을 떼고, 결과물 폴더 단락의 둘째 줄인 폴더를 함께 돌려준다.
 *
 * <p>단락은 각각 빈 줄 하나로 끝난다. 맨 앞에 결과물 폴더 단락이 없으면 아무것도 떼지 않는다. Chief 는 요청 본문 안에서 이 단락을
 * 받아 맨 앞이 아니므로 그대로 둔다. 떼지 않으면 입력을 글자 그대로 견주는 분기와 입력을 되돌려 주는 답이 모두
 * 어긋난다.
 */
export function splitArtifactPreamble(input: string): { folder?: string; conversationId?: string; rest: string } {
  if (!input.startsWith(`${ARTIFACT_HEADER}\n`)) return { rest: input };
  const end = input.indexOf("\n\n");
  if (end < 0) return { rest: input };
  const lines = input.slice(0, end).split("\n");
  const identifier = lines.find((line) => line.startsWith("대화 식별자: "));
  let rest = input.slice(end + 2);
  if (rest.startsWith(`${SKILL_NOTICE_HEADER}\n`)) {
    const noticeEnd = rest.indexOf("\n\n");
    if (noticeEnd >= 0) rest = rest.slice(noticeEnd + 2);
  }
  return { folder: lines[1], conversationId: identifier?.slice("대화 식별자: ".length), rest };
}

/** HTML 이 부르는 사진이다. 1픽셀짜리 PNG 다. */
export const ONE_PIXEL_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
  "base64",
);

/**
 * 에이전트가 초안을 만든 것처럼 폴더에 쓴다.
 *
 * <p>스크립트 한 줄을 품는다. 화면 검사가 제목이 바뀌지 않은 것으로 스크립트가 돌지 않았음을 본다.
 */
export function writeArtifactDraft(folder: string): void {
  const draft = join(folder, "초안");
  mkdirSync(draft, { recursive: true });
  writeFileSync(
    join(draft, "index.html"),
    [
      "<!doctype html>",
      "<html><head><meta charset=\"utf-8\"><title>초안</title></head>",
      "<body><h1>초안</h1><img src=\"photo.png\" alt=\"사진\">",
      "<script>document.title=\"스크립트가 돌았다\"</script>",
      "</body></html>",
    ].join("\n"),
  );
  writeFileSync(join(draft, "photo.png"), ONE_PIXEL_PNG);
}

/** 이름이 같은 두 결과물을 서로 다른 상위 폴더에 쓴다. 답 아래 줄과 패널 머리가 같은 이름을 쓰는지 보려는 입력이다. */
export function writeSameNameArtifacts(folder: string): void {
  for (const parent of ["가", "나"]) {
    const draft = join(folder, parent, "초안");
    mkdirSync(draft, { recursive: true });
    writeFileSync(join(draft, "index.html"),
      `<!doctype html><html><head><meta charset="utf-8"><title>초안</title></head><body><h1>${parent}</h1></body></html>`);
  }
}

/**
 * 게시된 디렉터리에서 스킬 이름과 설명을 읽는다.
 *
 * <p>실제 Hermes 처럼 `SKILL.md` 앞머리의 `name` 과 `description` 을 쓴다. 없는 디렉터리는 오류 없이
 * 건너뛴다. 게시 전에 디렉터리가 있는지 보는 것은 설정 쓰기 쪽이다.
 */
export function readPublishedSkills(dirs: readonly string[]): { name: string; description: string }[] {
  const found: { name: string; description: string }[] = [];
  for (const dir of dirs) {
    if (!existsSync(dir)) continue;
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const skillMd = join(dir, entry.name, "SKILL.md");
      if (!existsSync(skillMd)) continue;
      const frontmatter = readFileSync(skillMd, "utf-8").match(/^---\r?\n([\s\S]*?)\r?\n---/)?.[1] ?? "";
      found.push({
        name: frontmatter.match(/^name:\s*(.+)$/m)?.[1]?.trim() ?? entry.name,
        description: frontmatter.match(/^description:\s*(.+)$/m)?.[1]?.trim() ?? "",
      });
    }
  }
  return found;
}

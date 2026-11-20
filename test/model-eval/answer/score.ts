// 답변 품질 평가의 판정 규칙이다. 네트워크와 파일을 쓰지 않는 순수 함수만 둔다.
// 기준은 docs/features/answer-quality/spec.md 의 DECISION10 이, 세는 규칙은 docs/features/answer-quality.md 의 「답변 품질 평가」 가 갖는다.
import type { ExecutionNode, MessageView } from "../control-plane.ts";

export type Check = "sources" | "date" | "none";
export type Question = { id: string; category: string; messages: string[]; check: Check; compare: boolean };
/** 링크 하나를 열어 본 결과다. `unknown` 은 자동 요청을 막은 사이트라 열렸는지 모르는 것이다. */
export type LinkState = "open" | "closed" | "unknown";
export type ToolCounts = { search: number; searchFailed: number; extract: number; extractFailed: number };
export type Verdict = "similar" | "lacking";
export type Comparison = { id: string; verdict: Verdict; note: string };
export type QuestionScore = {
  id: string;
  category: string;
  check: Check;
  compare: boolean;
  /** 보내기나 읽기가 실패했으면 그 까닭이다. 이 문항은 통과가 아니다. */
  error?: string;
  passed: boolean;
  sourceLinks: number;
  openLinks: number;
  closedLinks: number;
  unknownLinks: number;
  latencyMs: number | null;
  tools: ToolCounts;
};
export type SetOutcome = "PASS" | "PASS_PENDING_COMPARE" | "PARTIAL" | "FAIL" | "ERROR";
export type SetScore = {
  outcome: SetOutcome;
  sourcePassed: number;
  sourceTotal: number;
  openLinks: number;
  checkedLinks: number;
  unknownLinks: number;
  datePassed: boolean;
  similar: number;
  compared: number;
  compareTotal: number;
  reasons: string[];
};

/** 출처 문항 열일곱 가운데 통과해야 하는 수의 비율이다(15/17). 문항 수가 바뀌어도 같은 비율로 올림한다. */
const SOURCE_PASS_RATIO = 15 / 17;
const OPEN_LINK_RATIO = 0.9;
/** 나란히 보는 여덟 문항 가운데 「비슷하다」 여야 하는 수의 비율이다(6/8). */
const SIMILAR_RATIO = 6 / 8;
/** 「출처」 가 든 마크다운 제목 줄이다. `## 출처 목록`, `### 참고 출처`, 그림 문자가 앞에 붙은 제목을 받는다. */
const HASH_HEADING = /^\s{0,3}#{1,6}\s.*출처/;
/** 제목 표시 없이 「출처」 로 시작하는 줄이다. 목록 표시와 굵은 글 표시는 떼고 본다. 콜론 뒤 같은 줄의 글은 절의 본문이다. */
const PLAIN_HEADING = /^(?:참고\s*)?출처(?:\s*목록)?\s*(?:\*\*|__)?\s*(?:[:：]\s*(?:\*\*|__)?(.*)|(?:\([^)]*\))?\s*)$/;
const LEADING_MARKS = /^[\s>*_\-•·]+/;
const NEXT_HEADING = /^\s{0,3}(?:#{1,6}\s+\S|(?:\*\*|__)[^*_]+(?:\*\*|__)\s*:?\s*$)/;
const URL_PATTERN = /https?:\/\/[^\s<>()\[\]"'`]+/g;
/** 주소 안의 괄호 한 겹(`…/A_(B)`)까지 받는다. */
const MARKDOWN_LINK = /\[[^\]]*\]\((https?:\/\/(?:[^\s()]|\([^\s()]*\))+)\)/g;

/** 기록한 실행의 질문 뒤부터 다음 사용자 메시지 전까지, 자동 turn 을 포함한 비서 답을 모은다. */
export function collectAnswers(messages: MessageView[], executionIds: (number | null)[]): string[] {
  const ids = new Set(executionIds.filter((id) => id !== null));
  const answers: string[] = [];
  let segment: MessageView[] | null = null;
  const collect = () => {
    if (!segment?.some((message) => message.role === "ASSISTANT" && message.executionId !== null && ids.has(message.executionId))) return;
    for (const message of segment) {
      if (message.role === "ASSISTANT" && message.content?.trim()) answers.push(message.content);
    }
  };
  for (const message of messages) {
    if (message.role === "USER") {
      collect();
      segment = [];
    } else {
      segment?.push(message);
    }
  }
  collect();
  return answers;
}

/** 그 줄이 「출처」 절의 머리면 같은 줄에 이어진 본문(없으면 빈 글)을, 아니면 null 을 돌려준다. */
function headingRest(line: string): string | null {
  if (HASH_HEADING.test(line)) return "";
  const match = PLAIN_HEADING.exec(line.replace(LEADING_MARKS, ""));
  return match ? (match[1] ?? "") : null;
}

/** 답 끝의 「출처」 절에 든 주소다. 절이 없으면 빈 목록이다. 절 밖의 링크는 세지 않는다. */
export function sourceLinks(answer: string | string[]): string[] {
  if (Array.isArray(answer)) return [...new Set(answer.flatMap((message) => sourceLinks(message)))];
  const lines = answer.split(/\r?\n/);
  let start = -1;
  let rest = "";
  for (let index = 0; index < lines.length; index++) {
    const found = headingRest(lines[index]);
    if (found !== null) {
      start = index;
      rest = found;
    }
  }
  if (start < 0) return [];
  const body: string[] = [rest];
  for (const line of lines.slice(start + 1)) {
    if (NEXT_HEADING.test(line)) break;
    body.push(line);
  }
  const text = body.join("\n");
  const found: string[] = [];
  for (const match of text.matchAll(MARKDOWN_LINK)) found.push(match[1]);
  for (const match of text.replace(MARKDOWN_LINK, " ").matchAll(URL_PATTERN)) found.push(match[0]);
  return [...new Set(found.map((url) => url.replace(/[.,;:!?]+$/, "")))];
}

/** 그 시각의 한국 날짜가 답에 들었는가. 「11월 18일」 과 연도가 붙은 「2026-11-18」, 「2026.11.18」, 「2026/11/18」 꼴을 받는다. 연도 없는 「11/18」 은 버전 번호나 범위와 헷갈려 받지 않는다. */
export function mentionsDate(answer: string, at: Date): boolean {
  const seoul = new Date(at.getTime() + 9 * 60 * 60 * 1000);
  const year = seoul.getUTCFullYear();
  const month = seoul.getUTCMonth() + 1;
  const day = seoul.getUTCDate();
  const patterns = [
    new RegExp(`(?<!\\d)0?${month}\\s*월\\s*0?${day}\\s*일`),
    new RegExp(`(?<!\\d)${year}\\s*[/.\\-]\\s*0?${month}\\s*[/.\\-]\\s*0?${day}(?!\\d)`),
  ];
  return patterns.some((pattern) => pattern.test(answer));
}

/** 실행 트리 전체에서 끝 사건만 센다. 시작 사건을 함께 세면 호출 하나가 둘로 세어진다. */
export function toolCounts(root: ExecutionNode): ToolCounts {
  const counts: ToolCounts = { search: 0, searchFailed: 0, extract: 0, extractFailed: 0 };
  const visit = (node: ExecutionNode) => {
    for (const event of node.events) {
      if (event.eventType !== "TOOL_COMPLETED") continue;
      if (event.toolName === "web_search") {
        counts.search++;
        if (event.failed) counts.searchFailed++;
      } else if (event.toolName === "web_extract") {
        counts.extract++;
        if (event.failed) counts.extractFailed++;
      }
    }
    node.children.forEach(visit);
  };
  visit(root);
  return counts;
}

/** HTTP 상태로 링크를 나눈다. 403 과 429 는 자동 요청을 막은 것이라 틀린 링크로 세지 않는다. 상태가 없으면 연결 실패나 시간 초과다. */
export function linkState(status: number | null): LinkState {
  if (status === null) return "closed";
  if (status === 403 || status === 429) return "unknown";
  return status >= 200 && status < 400 ? "open" : "closed";
}

export function scoreQuestion(
  question: Question,
  answer: string | string[],
  startedAt: Date,
  links: Map<string, LinkState>,
  root: ExecutionNode | null,
): QuestionScore {
  const urls = sourceLinks(answer);
  const states = urls.map((url) => links.get(url) ?? "closed");
  const answers = Array.isArray(answer) ? answer : [answer];
  const passed =
    question.check === "none" ? true : question.check === "date" ? answers.some((text) => mentionsDate(text, startedAt)) : urls.length > 0;
  return {
    id: question.id,
    category: question.category,
    check: question.check,
    compare: question.compare,
    passed,
    sourceLinks: urls.length,
    openLinks: states.filter((state) => state === "open").length,
    closedLinks: states.filter((state) => state === "closed").length,
    unknownLinks: states.filter((state) => state === "unknown").length,
    latencyMs: root?.latencyMs ?? null,
    tools: root ? toolCounts(root) : { search: 0, searchFailed: 0, extract: 0, extractFailed: 0 },
  };
}

export function failedQuestion(question: Question, error: string): QuestionScore {
  return {
    id: question.id,
    category: question.category,
    check: question.check,
    compare: question.compare,
    error,
    passed: false,
    sourceLinks: 0,
    openLinks: 0,
    closedLinks: 0,
    unknownLinks: 0,
    latencyMs: null,
    tools: { search: 0, searchFailed: 0, extract: 0, extractFailed: 0 },
  };
}

/**
 * 세트 통과를 정한다. 출처 문항, 열린 링크, 날짜 문항은 자동 기준이고 사람 비교는 받은 값으로 본다.
 * 자동 기준을 넘었는데 사람 비교가 다 차지 않았으면 `PASS_PENDING_COMPARE` 다. 보내기나 읽기에 실패한 문항이 있으면 `ERROR` 다.
 * 세트의 문항을 다 돌리지 않았으면 미달이 없어도 `PARTIAL` 이다. 일부만 돌린 결과를 세트 통과로 읽지 않게 한다.
 *
 * @param total 질문 세트의 문항 수. 주지 않으면 받은 문항이 세트 전체라고 본다
 */
export function scoreSet(scores: QuestionScore[], comparisons: Comparison[], total = scores.length): SetScore {
  const sources = scores.filter((score) => score.check === "sources");
  const dates = scores.filter((score) => score.check === "date");
  const compareIds = new Set(scores.filter((score) => score.compare).map((score) => score.id));
  // 같은 문항을 두 번 적었으면 마지막 것만 센다
  const given = [...new Map(comparisons.filter((comparison) => compareIds.has(comparison.id)).map((item) => [item.id, item])).values()];
  const sourcePassed = sources.filter((score) => score.passed).length;
  const openLinks = scores.reduce((sum, score) => sum + score.openLinks, 0);
  const closedLinks = scores.reduce((sum, score) => sum + score.closedLinks, 0);
  const unknownLinks = scores.reduce((sum, score) => sum + score.unknownLinks, 0);
  const checkedLinks = openLinks + closedLinks;
  const similar = given.filter((comparison) => comparison.verdict === "similar").length;
  const datePassed = dates.every((score) => score.passed);
  const reasons: string[] = [];
  const sourceNeeded = Math.ceil(sources.length * SOURCE_PASS_RATIO - 1e-9);
  if (sourcePassed < sourceNeeded) reasons.push(`출처가 붙은 문항 ${sourcePassed}/${sources.length} (기준 ${sourceNeeded} 이상)`);
  if (checkedLinks > 0 && openLinks / checkedLinks < OPEN_LINK_RATIO)
    reasons.push(`열린 링크 ${openLinks}/${checkedLinks} (기준 90% 이상)`);
  if (!datePassed) reasons.push("오늘 날짜 문항이 그날 날짜로 답하지 않았다");
  const similarNeeded = Math.ceil(compareIds.size * SIMILAR_RATIO - 1e-9);
  const compareDone = given.length >= compareIds.size;
  if (compareDone && similar < similarNeeded) reasons.push(`「비슷하다」 ${similar}/${compareIds.size} (기준 ${similarNeeded} 이상)`);
  const errors = scores.filter((score) => score.error).length;
  let outcome: SetOutcome;
  if (errors > 0) {
    outcome = "ERROR";
    reasons.unshift(`보내기나 읽기에 실패한 문항 ${errors}개`);
  } else if (reasons.length > 0) outcome = "FAIL";
  else if (scores.length < total) {
    outcome = "PARTIAL";
    reasons.push(`세트 ${total}문항 중 ${scores.length}문항만 돌렸다`);
  } else outcome = compareDone ? "PASS" : "PASS_PENDING_COMPARE";
  return {
    outcome,
    sourcePassed,
    sourceTotal: sources.length,
    openLinks,
    checkedLinks,
    unknownLinks,
    datePassed,
    similar,
    compared: given.length,
    compareTotal: compareIds.size,
    reasons,
  };
}

/** 세트 통과, 사람 비교 대기, 일부 문항만 돌린 것은 0, 기준 미달은 1, 보내기나 읽기 오류는 2 다. */
export function exitCode(outcome: SetOutcome): number {
  if (outcome === "ERROR") return 2;
  return outcome === "FAIL" ? 1 : 0;
}

const OUTCOME_TEXT: Record<SetOutcome, string> = {
  PASS: "통과",
  PASS_PENDING_COMPARE: "자동 기준 통과, 사람 비교 대기",
  PARTIAL: "일부 문항만 돌려 세트 결과를 내지 않는다",
  FAIL: "기준 미달",
  ERROR: "오류",
};

function seconds(latencyMs: number | null): string {
  return latencyMs === null ? "-" : `${Math.round(latencyMs / 1000)}초`;
}

/** 사람이 읽는 보고서다. 답 본문과 접속 값은 넣지 않는다. */
export function markdown(label: string, tier: string, scores: QuestionScore[], set: SetScore, comparisons: Comparison[]): string {
  const lines = [
    "# 답변 품질 평가 결과",
    "",
    `이름표: ${label || "-"}, 모델 칸: ${tier || "대화 기본값"}`,
    "",
    `세트 결과: **${OUTCOME_TEXT[set.outcome]}**`,
    "",
    `- 출처가 붙은 문항: ${set.sourcePassed}/${set.sourceTotal}`,
    `- 열린 링크: ${set.openLinks}/${set.checkedLinks} (확인 못 함 ${set.unknownLinks}개는 뺐다)`,
    `- 오늘 날짜 문항: ${set.datePassed ? "통과" : "미달"}`,
    `- 사람 비교: 「비슷하다」 ${set.similar}/${set.compareTotal} (적은 것 ${set.compared}개)`,
    ...set.reasons.map((reason) => `- ${set.outcome === "PARTIAL" ? "참고" : "미달"}: ${reason}`),
    "",
    "| 문항 | 범주 | 보는 것 | 결과 | 출처 링크 | 열림 | 닫힘 | 확인 못 함 | 걸린 시간 | 검색(실패) | 본문 읽기(실패) |",
    "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ...scores.map((score) => {
      const result = score.error ? `오류: ${score.error}` : score.passed ? "통과" : "미달";
      const tools = score.tools;
      return `| ${score.id} | ${score.category} | ${score.check} | ${result} | ${score.sourceLinks} | ${score.openLinks} | ${score.closedLinks} | ${score.unknownLinks} | ${seconds(score.latencyMs)} | ${tools.search}(${tools.searchFailed}) | ${tools.extract}(${tools.extractFailed}) |`;
    }),
    "",
    "## 사람 비교",
    "",
    "아래 문항을 ChatGPT 에 같은 글로 묻고 두 답을 나란히 본 뒤, `--compare` 파일에 문항마다 `similar`(비슷하다)나 `lacking`(모자라다)과 한 줄 까닭을 적어 `score` 를 다시 돌린다. 다시 돌릴 때 이미 지운 대화의 문항은 앞 보고서의 결과를 그대로 쓴다.",
    "",
    "| 문항 | 결과 | 까닭 |",
    "| --- | --- | --- |",
    ...scores
      .filter((score) => score.compare)
      .map((score) => {
        // 같은 문항을 두 번 적었으면 scoreSet 이 센 것과 같은 마지막 것을 보인다
        const comparison = comparisons.findLast((item) => item.id === score.id);
        const verdict = comparison ? (comparison.verdict === "similar" ? "비슷하다" : "모자라다") : "";
        return `| ${score.id} | ${verdict} | ${comparison?.note.replaceAll("|", "/") ?? ""} |`;
      }),
    "",
  ];
  return lines.join("\n");
}

import assert from "node:assert/strict";
import test from "node:test";
import type { ExecutionNode } from "../control-plane.ts";
import {
  asksForPhoto,
  collectAnswers,
  exitCode,
  failedQuestion,
  formatCheck,
  linkState,
  markdown,
  mentionsDate,
  scoreQuestion,
  scoreSet,
  sourceLinks,
  toolCounts,
  type Comparison,
  type LinkState,
  type Question,
  type QuestionScore,
} from "./score.ts";

const question = (id: string, check: Question["check"], compare = false): Question => ({
  id,
  category: "뉴스·시세",
  messages: ["질문"],
  check,
  compare,
});
const node = (events: ExecutionNode["events"], children: ExecutionNode[] = [], latencyMs = 1000): ExecutionNode => ({
  executionId: 1,
  status: "SUCCEEDED",
  latencyMs,
  events,
  children,
});
const NOON = new Date("2026-11-18T03:00:00Z");

test("답 끝의 출처 절에서 마크다운 링크와 맨 주소를 뽑고 중복은 한 번만 센다", () => {
  const answer = [
    "환율은 1,400원이다([한국은행](https://example.org/a)).",
    "",
    "## 출처",
    "- [한국은행](https://example.org/a)",
    "- 통계청: https://example.org/b.",
    "- [한국은행 다시](https://example.org/a)",
  ].join("\n");

  assert.deepEqual(sourceLinks(answer), ["https://example.org/a", "https://example.org/b"]);
});

test("출처 절의 머리는 제목 줄, 굵은 글, 콜론 꼴을 모두 받는다", () => {
  const headings = ["## 출처", "### 출처", "**출처**", "출처:", "**출처:**", "**출처**:", "## 출처 목록", "### 참고 출처", "## 📚 출처", "- 출처", "## 출처 (2026-11-18 기준)", "출처 목록"];
  for (const heading of headings) {
    assert.deepEqual(sourceLinks(`본문\n\n${heading}\n- https://example.org/x`), ["https://example.org/x"], heading);
  }
});

test("출처 머리와 같은 줄에 이어 쓴 링크도 센다", () => {
  assert.deepEqual(sourceLinks("본문\n\n출처: [한국은행](https://example.org/a)"), ["https://example.org/a"]);
  assert.deepEqual(sourceLinks("본문\n\n**출처:** [한국은행](https://example.org/a), https://example.org/b"), [
    "https://example.org/a",
    "https://example.org/b",
  ]);
});

test("출처로 시작하는 보통 문장은 절의 머리로 보지 않는다", () => {
  assert.deepEqual(sourceLinks("출처는 따로 없지만 https://example.org/a 를 참고했다."), []);
  assert.deepEqual(sourceLinks("출처를 확인하지 못했다.\nhttps://example.org/a"), []);
});

test("주소 안의 괄호를 자르지 않고 굵은 글로 된 다음 소제목 뒤의 링크는 세지 않는다", () => {
  const answer = "## 출처\n- [위키](https://example.org/A_(B))\n\n**덧붙임**\n- https://example.org/c";

  assert.deepEqual(sourceLinks(answer), ["https://example.org/A_(B)"]);
});

test("출처 절이 없으면 본문에 링크가 있어도 빈 목록이다", () => {
  assert.deepEqual(sourceLinks("환율은 [여기](https://example.org/a) 를 본다.\n출처는 없다."), []);
});

test("출처 절 뒤에 다른 제목이 오면 그 뒤의 링크는 세지 않는다", () => {
  const answer = "## 출처\n- https://example.org/a\n\n## 덧붙임\n- https://example.org/b";

  assert.deepEqual(sourceLinks(answer), ["https://example.org/a"]);
});

test("날짜 문항은 그 시각의 한국 날짜가 답에 들었는지로 본다", () => {
  assert.equal(mentionsDate("오늘은 2026년 11월 18일 수요일이에요.", NOON), true);
  assert.equal(mentionsDate("오늘은 2026-11-18 입니다.", NOON), true);
  assert.equal(mentionsDate("오늘은 2026.11.18 입니다.", NOON), true);
  // 연도 없는 숫자 꼴은 버전 번호나 범위와 헷갈려 받지 않는다
  assert.equal(mentionsDate("오늘은 11/18 입니다.", NOON), false);
  assert.equal(mentionsDate("버전 1.5 와 1-5명", new Date("2026-01-05T03:00:00Z")), false);
  assert.equal(mentionsDate("오늘은 11월 17일입니다.", NOON), false);
  assert.equal(mentionsDate("오늘은 1월 18일입니다.", NOON), false);
  // UTC 로는 18일이지만 한국 시간으로는 19일이다
  assert.equal(mentionsDate("오늘은 11월 19일입니다.", new Date("2026-11-18T15:30:00Z")), true);
});

test("도구 수는 실행 트리 전체의 끝 사건만 세어 시작 사건과 겹쳐 세지 않는다", () => {
  const child = node([
    { eventType: "TOOL_STARTED", toolName: "web_extract", failed: null },
    { eventType: "TOOL_COMPLETED", toolName: "web_extract", failed: true },
  ]);
  const root = node(
    [
      { eventType: "TOOL_STARTED", toolName: "web_search", failed: null },
      { eventType: "TOOL_COMPLETED", toolName: "web_search", failed: false },
      { eventType: "TOOL_STARTED", toolName: "web_search", failed: null },
      { eventType: "TOOL_COMPLETED", toolName: "web_search", failed: true },
      { eventType: "TOOL_COMPLETED", toolName: "mcp__other__web_search", failed: false },
    ],
    [child],
  );

  assert.deepEqual(toolCounts(root), { search: 2, searchFailed: 1, extract: 1, extractFailed: 1 });
});

test("링크 상태는 2xx 와 3xx 가 열림이고 403 과 429 는 확인 못 함이다", () => {
  assert.equal(linkState(200), "open");
  assert.equal(linkState(301), "open");
  assert.equal(linkState(403), "unknown");
  assert.equal(linkState(429), "unknown");
  assert.equal(linkState(404), "closed");
  assert.equal(linkState(500), "closed");
  assert.equal(linkState(null), "closed");
});

test("출처 문항은 출처 절에 링크가 있어야 통과이고 검사하지 않는 문항은 늘 통과다", () => {
  const links = new Map<string, LinkState>([
    ["https://example.org/a", "open"],
    ["https://example.org/b", "unknown"],
  ]);
  const withSources = scoreQuestion(
    question("q1", "sources"),
    "본문\n\n## 출처\n- https://example.org/a\n- https://example.org/b\n- https://example.org/c",
    NOON,
    links,
    node([], [], 42000),
  );
  const without = scoreQuestion(question("q2", "sources"), "검색 없이 답했다.", NOON, links, null);
  const none = scoreQuestion(question("q3", "none"), "김치찌개 만드는 법", NOON, links, null);

  assert.equal(withSources.passed, true);
  assert.deepEqual(
    [withSources.sourceLinks, withSources.openLinks, withSources.unknownLinks, withSources.closedLinks, withSources.latencyMs],
    [3, 1, 1, 1, 42000],
  );
  assert.equal(without.passed, false);
  assert.equal(none.passed, true);
});

/** 출처 문항 열일곱, 날짜 문항 하나, 검사하지 않는 문항 둘, 사람 비교 여덟의 세트다. */
function fullSet(sourcePassed: number, open = 9, closed = 1): QuestionScore[] {
  const scores: QuestionScore[] = [];
  for (let index = 0; index < 17; index++) {
    const passed = index < sourcePassed;
    scores.push({
      ...failedQuestion(question(`s${index}`, "sources", index < 8), ""),
      error: undefined,
      passed,
      sourceLinks: passed ? 1 : 0,
    });
  }
  scores[0] = { ...scores[0], openLinks: open, closedLinks: closed };
  scores.push({ ...failedQuestion(question("date", "date"), ""), error: undefined, passed: true });
  scores.push({ ...failedQuestion(question("n1", "none"), ""), error: undefined, passed: true });
  scores.push({ ...failedQuestion(question("n2", "none"), ""), error: undefined, passed: true });
  return scores;
}
const comparisons = (similar: number): Comparison[] =>
  Array.from({ length: 8 }, (_, index) => ({ id: `s${index}`, verdict: index < similar ? "similar" : "lacking", note: "" }));

test("출처 문항 열일곱 중 열다섯이면 통과이고 열넷이면 미달이다", () => {
  assert.equal(scoreSet(fullSet(15), comparisons(6)).outcome, "PASS");
  const failed = scoreSet(fullSet(14), comparisons(6));
  assert.equal(failed.outcome, "FAIL");
  assert.match(failed.reasons.join(" "), /14\/17/);
});

test("확인한 링크의 90% 가 열려야 하고 확인 못 한 링크는 분모에서 뺀다", () => {
  assert.equal(scoreSet(fullSet(17, 9, 1), comparisons(8)).outcome, "PASS");
  assert.equal(scoreSet(fullSet(17, 8, 2), comparisons(8)).outcome, "FAIL");
  const withUnknown = fullSet(17, 9, 1);
  withUnknown[1] = { ...withUnknown[1], unknownLinks: 5 };
  const set = scoreSet(withUnknown, comparisons(8));
  assert.equal(set.outcome, "PASS");
  assert.deepEqual([set.openLinks, set.checkedLinks, set.unknownLinks], [9, 10, 5]);
});

test("날짜 문항이 틀리면 미달이다", () => {
  const scores = fullSet(17);
  const date = scores.findIndex((score) => score.check === "date");
  scores[date] = { ...scores[date], passed: false };

  assert.equal(scoreSet(scores, comparisons(8)).outcome, "FAIL");
});

test("사람 비교가 비었으면 대기이고 여덟 중 여섯이 비슷하다여야 통과다", () => {
  assert.equal(scoreSet(fullSet(17), []).outcome, "PASS_PENDING_COMPARE");
  assert.equal(scoreSet(fullSet(17), comparisons(6).slice(0, 7)).outcome, "PASS_PENDING_COMPARE");
  assert.equal(scoreSet(fullSet(17), comparisons(6)).outcome, "PASS");
  assert.equal(scoreSet(fullSet(17), comparisons(5)).outcome, "FAIL");
  // 같은 문항을 여러 번 적어도 한 번만 센다
  const repeated = Array.from({ length: 8 }, () => ({ id: "s0", verdict: "similar" as const, note: "" }));
  assert.deepEqual([scoreSet(fullSet(17), repeated).outcome, scoreSet(fullSet(17), repeated).compared], ["PASS_PENDING_COMPARE", 1]);
  // 사람 비교 대상이 아닌 문항의 값은 세지 않는다
  assert.equal(scoreSet(fullSet(17), [{ id: "s16", verdict: "similar", note: "" }]).compared, 0);
});

test("보내기나 읽기에 실패한 문항이 있으면 오류이고 종료 코드는 2 다", () => {
  const scores = fullSet(17);
  scores[3] = failedQuestion(question("s3", "sources"), "HTTP 500");
  const set = scoreSet(scores, comparisons(8));

  assert.equal(set.outcome, "ERROR");
  assert.deepEqual(["PASS", "PASS_PENDING_COMPARE", "PARTIAL", "FAIL", "ERROR"].map((outcome) => exitCode(outcome as never)), [0, 0, 0, 1, 2]);
});

test("세트의 문항을 다 돌리지 않았으면 미달이 없어도 통과가 아니라 일부 결과다", () => {
  const one = fullSet(17).slice(0, 1);

  assert.equal(scoreSet(one, [], 20).outcome, "PARTIAL");
  assert.equal(scoreSet(fullSet(14).slice(0, 17), [], 20).outcome, "FAIL");
});

test("보고서는 문항 표와 사람 비교 표를 내고 적은 값을 옮긴다", () => {
  const scores = fullSet(17);
  const given: Comparison[] = [{ id: "s0", verdict: "lacking", note: "가격이 | 다르다" }];
  const report = markdown("exa", "MEDIUM", scores, scoreSet(scores, given), given);

  assert.match(report, /이름표: exa, 모델 칸: MEDIUM/);
  assert.match(report, /자동 기준 통과, 사람 비교 대기/);
  assert.match(report, /\| s0 \| 모자라다 \| 가격이 \/ 다르다 \|/);
  assert.match(report, /\| s7 \|  \|  \|/);
  assert.doesNotMatch(report, /\| s8 \|  \|  \|/);
});

test("같은 문항을 두 번 적으면 보고서도 센 것과 같은 마지막 판정을 보인다", () => {
  const scores = fullSet(17);
  const given: Comparison[] = [
    { id: "s0", verdict: "lacking", note: "처음" },
    { id: "s0", verdict: "similar", note: "고쳐 적음" },
  ];
  const set = scoreSet(scores, given);
  const report = markdown("", "", scores, set, given);

  assert.equal(set.similar, 1);
  assert.match(report, /\| s0 \| 비슷하다 \| 고쳐 적음 \|/);
  assert.doesNotMatch(report, /처음/);
});

test("나뉜 답의 출처를 메시지마다 모아 중복을 빼고 마지막 후속 답에 출처가 없어도 채점한다", () => {
  const contents = [
    "11월 18일 뉴스\n\n## 출처\n- https://example.org/a",
    "추가 뉴스\n\n## 출처\n- https://example.org/b\n- https://example.org/a",
    "앞서 전한 뉴스의 요약이에요. 본문 링크 https://example.org/c",
  ];
  const answers = collectAnswers([
    { role: "USER", executionId: null, content: "이번 주 뉴스", status: "COMPLETED", artifacts: [] },
    ...contents.map((content, index) => ({ role: "ASSISTANT", executionId: index + 1, content, status: "COMPLETED", artifacts: [] })),
  ], [1]);
  const links = new Map<string, LinkState>([
    ["https://example.org/a", "open"],
    ["https://example.org/b", "closed"],
  ]);
  assert.deepEqual(sourceLinks(answers), ["https://example.org/a", "https://example.org/b"]);
  const result = scoreQuestion(question("split", "sources", true), answers, NOON, links, null);
  assert.deepEqual([result.passed, result.sourceLinks, result.openLinks, result.closedLinks], [true, 2, 1, 1]);
  assert.equal(scoreQuestion(question("date", "date"), answers, NOON, links, null).passed, true);
});

test("형식 참고 칸: 꼴을 지킨 답은 어긴 것이 없다", () => {
  const answer = [
    "환율은 1,400원이다. 어제보다 조금 올랐다([한국은행](https://example.org/a)).",
    "",
    "### 출처",
    "",
    "- [한국은행 · 기준 환율](https://example.org/a)",
    "- [통계청 · 물가](https://example.org/b)",
  ].join("\n");

  assert.deepEqual(formatCheck(answer), { sourceListForm: true, bareUrls: 0, repeatedSources: 0, longParagraphs: 0 });
});

test("형식 참고 칸: 운영 답처럼 보통 줄 출처와 맨 주소는 어긴 것으로 센다", () => {
  const answer = [
    "열이 나면 물을 자주 먹인다. 옷은 얇게 입힌다. 해열제는 체중에 맞춘다. 경련이 있으면 119 에 전화한다.",
    "",
    "출처",
    "NHS, 소아 발열 안내: https://www.nhs.uk/a/",
    "NHS, 같은 안내: https://WWW.NHS.UK/a#top",
    "[https://example.org/c](https://example.org/c)",
  ].join("\n");

  assert.deepEqual(formatCheck(answer), { sourceListForm: false, bareUrls: 3, repeatedSources: 1, longParagraphs: 1 });
});

test("형식 참고 칸: 코드 안의 주소와 목록·표는 세지 않고, 출처 절이 없으면 해당 없음이다", () => {
  const answer = ["```", "curl https://example.org/x", "```", "", "- 하나. 둘. 셋. 넷.", "", "`https://example.org/y`"].join("\n");

  assert.deepEqual(formatCheck(answer), { sourceListForm: null, bareUrls: 0, repeatedSources: 0, longParagraphs: 0 });
});

test("형식 참고 칸: 메시지가 여럿이면 수를 더하고 출처 절이 있는 메시지만 꼴을 본다", () => {
  const check = formatCheck(["앞 메시지 https://example.org/a", "### 출처\n\n- [이름](https://example.org/b)"]);

  assert.deepEqual(check, { sourceListForm: true, bareUrls: 1, repeatedSources: 0, longParagraphs: 0 });
});

test("형식 참고 칸은 세트 통과를 바꾸지 않고 칸이 없는 옛 결과도 보고서에 그린다", () => {
  const fine = scoreQuestion(question("q1", "sources"), "### 출처\n\n- [이름](https://example.org/a)", NOON, new Map([["https://example.org/a", "open"]]), null);
  const bad = scoreQuestion(question("q2", "sources"), "출처\nhttps://example.org/b", NOON, new Map([["https://example.org/b", "open"]]), null);
  const { format: _dropped, ...old } = fine;
  const set = scoreSet([bad, old], [], 2);

  assert.equal(set.outcome, "PASS");
  assert.equal(set.formatViolations, 1);
  assert.equal(set.formatChecked, 1);
  const report = markdown("", "", [bad, old], set, []);
  assert.match(report, /형식 참고\(통과 기준 아님\): 어긴 문항 1\/1/);
  assert.match(report, /\| q2 \| 어김 \| 1 \| 0 \| 0 \|/);
  assert.match(report, /\| q1 \| - \| - \| - \| - \|/);
});

test("그림 참고 사진 문항은 답이 사진이나 링크를 보내 달라고 할 때만 통과하고, 미달이면 세트 사유에 든다", () => {
  assert.equal(asksForPhoto("찾은 사진은 그림 도구에 넘길 수 없어요. 상품 사진을 올려 주시거나 링크를 보내 주시면 그걸로 그릴게요."), true);
  assert.equal(asksForPhoto("양말을 신고 뛰노는 아이 그림을 그렸어요."), false);
  const links = new Map<string, LinkState>();
  const asked = scoreQuestion(question("p1", "photo-request"), ["사진을 직접 올려 주세요."], NOON, links, null);
  const drew = scoreQuestion(question("p1", "photo-request"), ["비슷하게 그려 봤어요."], NOON, links, null);
  assert.equal(asked.passed, true);
  assert.equal(drew.passed, false);
  const scores = fullSet(15);
  scores.push(drew);
  const set = scoreSet(scores, comparisons(6));
  assert.equal(set.outcome, "FAIL");
  assert.ok(set.reasons.some((reason) => reason.includes("사진이나 링크를 보내 달라고 하지 않았다")), set.reasons.join(", "));
});

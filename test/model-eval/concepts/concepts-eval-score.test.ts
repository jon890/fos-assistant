import assert from "node:assert/strict";
import test from "node:test";
import type { ExecutionNode } from "../control-plane.ts";
import {
  answerLinks,
  completedTools,
  exitCode,
  failedQuestion,
  fill,
  linkedTaskIds,
  markdown,
  scoreQuestion,
  scoreSet,
  skippedQuestion,
  type Question,
  type QuestionScore,
} from "./score.ts";

const node = (events: ExecutionNode["events"], children: ExecutionNode[] = []): ExecutionNode => ({
  executionId: 1,
  status: "SUCCEEDED",
  latencyMs: 1000,
  events,
  children,
});
const completed = (toolName: string) => ({ eventType: "TOOL_COMPLETED", toolName, failed: false });
const question = (expect: Question["expect"], messages = ["질문"]): Question => ({ id: "q", concept: "connection", messages, expect });
const score = (passed: boolean, extra: Partial<QuestionScore> = {}): QuestionScore => ({
  id: "q",
  concept: "schedule",
  passed,
  skipped: false,
  failures: [],
  ...extra,
});

test("답의 링크는 마크다운 대상과 맨 주소를 모두 찾고 전체 주소는 경로와 질의 문자열만 남긴다", () => {
  const answer = [
    "[예약 작업 만들기](/tasks/new) 에서 만들 수 있어요.",
    "연결은 /connections/google-calendar?agent=my-agent 화면에서 해요.",
    "[새 대화](https://assistant.example.com/new?agent=blog) 로 가세요.",
    "주소는 /memory.",
    "파일은 [여기](</files>) 가 아니라 /files 에 있어요.",
  ].join("\n");

  const links = answerLinks(answer);

  assert.ok(links.includes("/tasks/new"));
  assert.ok(links.includes("/connections/google-calendar?agent=my-agent"));
  assert.ok(links.includes("/new?agent=blog"));
  assert.ok(links.includes("/memory"));
  assert.ok(links.includes("/files"));
  assert.ok(!links.some((link) => link.startsWith("/assistant")), "도메인 안의 경로를 맨 주소로 읽지 않는다");
});

test("날짜나 분수 같은 슬래시는 링크로 읽지 않는다", () => {
  assert.deepEqual(answerLinks("11/20 에 3/4 만큼 했다. and/or"), []);
});

test("도구는 트리 전체의 끝난 Control Plane 도구만 이름으로 센다", () => {
  const tree = node([completed("mcp__fos_assistant__connector_list"), { eventType: "TOOL_STARTED", toolName: "mcp__fos_assistant__agent_list", failed: null }], [
    node([completed("mcp__fos_assistant__agent_list"), completed("web_search")]),
  ]);

  assert.deepEqual([...completedTools(tree)].sort(), ["agent_list", "connector_list"]);
});

test("링크는 똑같음, 앞부분 맞음, 없어야 할 앞부분을 나눠 본다", () => {
  const exact = question({ links: ["/connections"] });
  assert.equal(scoreQuestion(exact, "[연결](/connections)", node([])).passed, true);
  assert.equal(scoreQuestion(exact, "[연결](/connections/calendar)", node([])).passed, false, "/connections/xyz 는 똑같지 않다");

  const prefix = question({ linkPrefixes: ["/connections/calendar"] });
  assert.equal(scoreQuestion(prefix, "/connections/calendar?agent=a", node([])).passed, true);

  const none = question({ noLinkPrefixes: ["/new?agent="] });
  assert.equal(scoreQuestion(none, "쓸 수 있는 에이전트는 블로그 비서예요", node([])).passed, true);
  assert.equal(scoreQuestion(none, "[새 대화](/new?agent=x)", node([])).passed, false);
});

test("도구, 있어야 할 낱말 하나, 없어야 할 낱말을 모두 보고 미달 까닭을 남긴다", () => {
  const target = question({ tools: ["connector_list"], includes: ["입력창", "모델 단계"], absent: ["연결했"] });

  const passed = scoreQuestion(target, "입력창에서 고르세요", node([completed("mcp__fos_assistant__connector_list")]));
  const failed = scoreQuestion(target, "연결했어요", node([]));

  assert.equal(passed.passed, true);
  assert.equal(failed.passed, false);
  assert.equal(failed.failures.length, 3);
});

test("자리를 채우고, 쓴 자리의 값이 비면 문항을 건너뛴다", () => {
  const original = question({ linkPrefixes: ["/connections/{connectorId}"] }, ["{connectorTitle} 연결해 줘"]);

  const filled = fill(original, { connectorId: "calendar", connectorTitle: "구글 캘린더" });

  assert.deepEqual(filled?.messages, ["구글 캘린더 연결해 줘"]);
  assert.deepEqual(filled?.expect.linkPrefixes, ["/connections/calendar"]);
  assert.equal(fill(original, { connectorId: "calendar" }), null);
  assert.deepEqual(fill(question({ absent: ["바꿨"] }), {})?.expect.absent, ["바꿨"]);
});

test("세트는 돌린 문항의 85% 이상(올림)이면 통과이고 건너뛴 문항은 분모에서 빠진다", () => {
  const ran = (passedCount: number, total: number) =>
    Array.from({ length: total }, (_, index) => score(index < passedCount));

  assert.equal(scoreSet(ran(12, 14)).outcome, "PASS");
  assert.equal(scoreSet(ran(12, 14)).needed, 12);
  assert.equal(scoreSet(ran(11, 14)).outcome, "FAIL");
  const withSkipped = [...ran(11, 12), skippedQuestion(question({ absent: ["x"] }))];
  assert.deepEqual({ ...scoreSet(withSkipped), outcome: scoreSet(withSkipped).outcome }, {
    outcome: "PASS",
    passed: 11,
    ran: 12,
    needed: 11,
    skipped: 1,
    errors: 0,
  });
  assert.equal(scoreSet([]).outcome, "FAIL");
});

test("보내기나 읽기 오류가 하나라도 있으면 ERROR 이고 종료 코드는 0, 1, 2 다", () => {
  const set = scoreSet([score(true), failedQuestion(question({ absent: ["x"] }), "읽기 실패")]);

  assert.equal(set.outcome, "ERROR");
  assert.equal(exitCode("PASS"), 0);
  assert.equal(exitCode("FAIL"), 1);
  assert.equal(exitCode("ERROR"), 2);
});

test("보고서는 문항마다 결과와 미달 까닭을 적는다", () => {
  const report = markdown("pr1-before", "", [score(false, { failures: ["링크 /tasks 가 없다"] }), skippedQuestion(question({ absent: ["x"] }))], scoreSet([score(false)]));

  assert.match(report, /\| q \| schedule \| 미달 \| 링크 \/tasks 가 없다 \|/);
  assert.match(report, /건너뜀/);
  assert.match(report, /모델 칸: 기본/);
});

test("답이 링크한 예약 작업 번호를 찾고, 지시문 요소는 항목마다 낱말 하나가 있으면 통과다", () => {
  const id = "0192f6a1-0000-7000-8000-000000000001";
  const answer = `만들었어요. [아침 날씨](/tasks/${id}) 에서 바꿀 수 있어요. 목록은 /tasks 예요.`;
  assert.deepEqual(linkedTaskIds(answer), [id]);

  const expect = { instructionIncludes: ["30만|300000", "없으면|없을 때"] };
  const passed = scoreQuestion(question(expect), answer, node([]), ["가격이 30만 원 밑이면 알린다. 새 소식이 없으면 알리지 않는다."]);
  const missing = scoreQuestion(question(expect), answer, node([]), ["가격을 확인해 알린다."]);
  const none = scoreQuestion(question(expect), answer, node([]));
  assert.equal(passed.passed, true);
  assert.equal(missing.passed, false);
  assert.deepEqual(missing.failures, ["지시문에 30만, 300000 가운데 하나도 없다", "지시문에 없으면, 없을 때 가운데 하나도 없다"]);
  assert.deepEqual(none.failures, ["답이 링크한 예약 작업의 지시문이 없다"]);
});

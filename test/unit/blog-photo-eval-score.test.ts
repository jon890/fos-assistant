import assert from "node:assert/strict";
import test from "node:test";
import type { BackendPipelineEvent } from "../support/media-pipeline-log.ts";
import {
  exitCode,
  normalize,
  previewOf,
  scoreRun,
  scoreScenario,
  type PhotoAnswer,
  type RunRecord,
  type ScoreInput,
} from "../model-eval/blog/score.ts";

const CONVERSATION = 7;
const ROOT = 500;
const CHILD = 501;
const PHOTOS = ["p01", "p02", "p03", "p04", "p05", "p06"];
const IDS = [101, 102, 103, 104, 105, 106];
const ANSWERS = new Map<string, PhotoAnswer>(PHOTOS.map((id, i) => [id, { id, accept: [`정답${i + 1}`] }]));

const assets = (ids: number[]) => ids.map((id, position) => ({ assetId: String(id), position }));
const embedded = (ids: number[], missing: number[] = [], conversationId = CONVERSATION): BackendPipelineEvent => ({
  schemaVersion: 1,
  stage: "embedded",
  conversationId,
  count: ids.length,
  assets: assets(ids),
  missing: missing.map(String),
});
const referenced = (ids: number[], conversationId = CONVERSATION): BackendPipelineEvent => ({
  schemaVersion: 1,
  stage: "referenced",
  conversationId,
  count: ids.length,
  assets: assets(ids),
});
const inspected = (
  id: number,
  outcome = "OK",
  executionId = ROOT,
  conversationId = CONVERSATION,
): BackendPipelineEvent => ({
  schemaVersion: 1,
  stage: "inspected",
  conversationId,
  executionId,
  assetId: String(id),
  mode: "ORIGINAL",
  outcome,
});

/** 사진마다 `<img>` 와 정답 글자를 싣는 미리보기다. `skip` 의 사진은 부르지 않고, `blank` 의 정답은 싣지 않는다. */
function html(ids: number[], skip: number[] = [], blank: number[] = []): string {
  return ids
    .map((id, i) =>
      skip.includes(id) ? "" : `<p><img src="attachments/${id}"></p><p>${blank.includes(id) ? "글" : `정답${i + 1}`}</p>`,
    )
    .join("\n");
}

const pastOnly = (): RunRecord => ({
  scenarioId: "past-only",
  repeat: 1,
  conversationId: "c-1",
  messages: [
    { executionId: 400, attachmentIds: IDS, photoIds: PHOTOS },
    { executionId: ROOT, attachmentIds: [], photoIds: [] },
  ],
});
const sameMessage = (): RunRecord => ({
  scenarioId: "same-message",
  repeat: 1,
  conversationId: "c-2",
  messages: [{ executionId: ROOT, attachmentIds: IDS, photoIds: PHOTOS }],
});

function input(overrides: Partial<ScoreInput> = {}): ScoreInput {
  return {
    run: pastOnly(),
    answers: ANSWERS,
    events: [embedded(IDS), referenced(IDS), ...IDS.map((id) => inspected(id))],
    executionIds: [ROOT, CHILD],
    status: "SUCCEEDED",
    failedTools: [],
    truncated: false,
    preview: html(IDS),
    ...overrides,
  };
}

/** 문제가 있는 사진만 `photoId: 문제` 로 모은다. */
const issuesOf = (result: ReturnType<typeof scoreRun>) =>
  Object.fromEntries(result.photos.filter((p) => p.issues.length > 0).map((p) => [p.photoId, p.issues]));

test("past-only 에서 여섯 장 모두 참조, 조회, 원고, 정답이 있으면 PASS 다", () => {
  const result = scoreRun(input());
  assert.equal(result.outcome, "PASS");
  assert.deepEqual(result.reasons, []);
  assert.deepEqual(issuesOf(result), {});
  assert.equal(result.photos.length, 6);
});

test("한 장의 조회가 없거나 OK 가 아니면 그 사진만 조회 누락이다", () => {
  const without = scoreRun(input({ events: [embedded(IDS), referenced(IDS), ...IDS.slice(1).map((id) => inspected(id))] }));
  assert.equal(without.outcome, "FAIL");
  assert.deepEqual(issuesOf(without), { p01: ["조회 누락"] });

  const invalid = scoreRun(
    input({
      events: [
        embedded(IDS),
        referenced(IDS),
        inspected(101, "MCP_CALL_CONTEXT_INVALID"),
        ...IDS.slice(1).map((id) => inspected(id)),
      ],
    }),
  );
  assert.deepEqual(issuesOf(invalid), { p01: ["조회 누락"] });
});

test("정답이 없으면 판독 실패, 부르지 않으면 원고 누락, 참조에 없으면 참조 누락이다", () => {
  const result = scoreRun(
    input({
      events: [embedded(IDS), referenced(IDS.filter((id) => id !== 103)), ...IDS.map((id) => inspected(id))],
      preview: html(IDS, [102], [101]),
    }),
  );
  assert.equal(result.outcome, "FAIL");
  assert.deepEqual(issuesOf(result), { p01: ["판독 실패"], p02: ["판독 실패", "원고 누락"], p03: ["참조 누락"] });
});

test("미리보기가 없으면 미리보기 없음 하나만 적은 FAIL 이다", () => {
  const result = scoreRun(input({ preview: null }));
  assert.deepEqual(result, { outcome: "FAIL", reasons: ["미리보기 없음"], notes: [], photos: [] });
});

test("미리보기가 있으면 list_categories 실패를 참고에 남기고 사진 기준으로 PASS 다", () => {
  const result = scoreRun(input({ failedTools: ["list_categories", "list_categories"] }));
  assert.equal(result.outcome, "PASS");
  assert.deepEqual(result.reasons, []);
  assert.deepEqual(result.notes, ["list_categories 실패"]);
  assert.deepEqual(issuesOf(result), {});
  assert.equal(result.photos.length, 6);
});

test("미리보기와 도구 실패가 함께 있어도 사진 하나의 정답 글자가 없으면 FAIL 이다", () => {
  const result = scoreRun(input({ failedTools: ["list_categories"], preview: html(IDS, [], [101]) }));
  assert.equal(result.outcome, "FAIL");
  assert.deepEqual(issuesOf(result), { p01: ["판독 실패"] });
  assert.deepEqual(result.notes, ["list_categories 실패"]);
});

test("미리보기가 없고 네이버 블로그 도구가 실패하면 INCONCLUSIVE 다", () => {
  assert.deepEqual(scoreRun(input({ failedTools: ["list_categories", "render_draft"], preview: null })), {
    outcome: "INCONCLUSIVE",
    reasons: ["네이버 블로그 도구 실패"],
    notes: ["list_categories 실패", "render_draft 실패"],
    photos: [],
  });
});

test("미리보기가 있어도 실행이 SUCCEEDED 가 아니거나 트리가 잘리면 INCONCLUSIVE 다", () => {
  assert.deepEqual(scoreRun(input({ status: "FAILED" })), {
    outcome: "INCONCLUSIVE",
    reasons: ["실행 상태 FAILED"],
    notes: [],
    photos: [],
  });
  assert.deepEqual(scoreRun(input({ truncated: true })), {
    outcome: "INCONCLUSIVE",
    reasons: ["실행 트리 잘림"],
    notes: [],
    photos: [],
  });
  assert.equal(scoreRun(input({ run: { ...pastOnly(), error: "TimeoutError: 시간 초과" } })).outcome, "INCONCLUSIVE");
});

test("same-message 에서 embedded 의 missing 에 든 사진만 조회를 본다", () => {
  const missing = scoreRun(
    input({ run: sameMessage(), events: [embedded(IDS.slice(0, 5), [106])], preview: html(IDS) }),
  );
  assert.equal(missing.outcome, "FAIL");
  assert.deepEqual(issuesOf(missing), { p06: ["조회 누락"] });

  const inspectedMissing = scoreRun(
    input({ run: sameMessage(), events: [embedded(IDS.slice(0, 5), [106]), inspected(106)], preview: html(IDS) }),
  );
  assert.equal(inspectedMissing.outcome, "PASS");

  const allEmbedded = scoreRun(input({ run: sameMessage(), events: [embedded(IDS)], preview: html(IDS) }));
  assert.equal(allEmbedded.outcome, "PASS");
});

test("다른 대화나 다른 실행의 조회는 세지 않고 위임한 자식 실행의 조회는 센다", () => {
  const foreign = scoreRun(
    input({
      events: [
        embedded(IDS),
        referenced(IDS),
        inspected(101, "OK", ROOT, 99),
        inspected(102, "OK", 999),
        ...IDS.slice(2).map((id) => inspected(id, "OK", CHILD)),
      ],
    }),
  );
  assert.deepEqual(issuesOf(foreign), { p01: ["조회 누락"], p02: ["조회 누락"] });
});

test("앞 메시지의 referenced 가 아니라 마지막 메시지의 referenced 를 본다", () => {
  const second = [107, 108, 109, 110, 111, 112];
  const run: RunRecord = {
    scenarioId: "many-past",
    repeat: 1,
    conversationId: "c-3",
    messages: [
      { executionId: 300, attachmentIds: IDS, photoIds: PHOTOS },
      { executionId: 400, attachmentIds: second, photoIds: ["p07", "p08", "p09", "p10", "p11", "p12"] },
      { executionId: ROOT, attachmentIds: [], photoIds: [] },
    ],
  };
  const all = [...IDS, ...second];
  // 둘째 메시지가 남긴 referenced(첫 여섯 장)만 있고 마지막 메시지의 referenced 가 없다
  const result = scoreRun(
    input({ run, answers: null, events: [embedded(IDS), embedded(second), referenced(IDS), ...all.map((id) => inspected(id))], preview: html(all) }),
  );
  assert.equal(result.outcome, "FAIL");
  assert.equal(result.photos.filter((p) => p.issues.includes("참조 누락")).length, 12);

  const withLast = scoreRun(
    input({
      run,
      answers: null,
      events: [embedded(IDS), embedded(second), referenced(IDS), referenced(all), ...all.map((id) => inspected(id))],
      preview: html(all),
    }),
  );
  assert.equal(withLast.outcome, "PASS");
});

test("로그에 이 대화의 사건이 하나도 없으면 INCONCLUSIVE 다", () => {
  const result = scoreRun(input({ events: [embedded([901, 902], [], 99)], preview: null }));
  assert.deepEqual(result, { outcome: "INCONCLUSIVE", reasons: ["로그에 대화 사건 없음"], notes: [], photos: [] });
});

test("normalize 는 띄어쓰기, 태그, 엔티티를 지운다", () => {
  assert.equal(normalize("가상 <b>국수</b>"), "가상국수");
  assert.equal(normalize("수육&amp;막국수 &lt;반&gt; &quot;a&quot; &#39;b&#39;\n"), "수육&막국수<반>\"a\"'b'");
});

test("previewOf 는 이 실행의 첨부를 부르는 마지막 HTML 을 고르고 번호 앞부분만 맞는 것은 고르지 않는다", () => {
  const first = `<img src="attachments/101">`;
  const last = `<img src="attachments/102">`;
  assert.equal(previewOf([first, last, "<p>사진 없음</p>"], [101, 102]), last);
  assert.equal(previewOf([`<img src="attachments/1011">`], [101]), null);
  assert.equal(previewOf([], [101]), null);
});

test("정답이 없으면 정답 없음을 적고 판독을 보지 않는다", () => {
  const result = scoreRun(input({ answers: null, preview: html(IDS, [], IDS) }));
  assert.equal(result.outcome, "PASS");
  assert.deepEqual(result.reasons, ["정답 없음"]);
});

test("scoreScenario 와 exitCode", () => {
  assert.equal(scoreScenario(["PASS", "PASS", "PASS"], 3), "PASS");
  assert.equal(scoreScenario(["PASS", "PASS"], 3), "INCONCLUSIVE");
  assert.equal(scoreScenario(["PASS", "FAIL", "INCONCLUSIVE"], 3), "FAIL");
  assert.equal(scoreScenario(["PASS", "INCONCLUSIVE", "PASS"], 3), "INCONCLUSIVE");
  assert.equal(exitCode(["PASS", "PASS"]), 0);
  assert.equal(exitCode(["PASS", "FAIL", "INCONCLUSIVE"]), 1);
  assert.equal(exitCode(["PASS", "INCONCLUSIVE"]), 2);
});

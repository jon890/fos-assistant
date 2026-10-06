import assert from "node:assert/strict";
import test from "node:test";
import {
  approvalArgs,
  bulkApprovable,
  type ConnectorAction,
  readableArgs,
} from "../../web/src/lib/connector-action.ts";

test("JSON 객체는 최상위 키마다 한 줄로 바뀐다", () => {
  const rows = readableArgs(JSON.stringify({ title: "장보기", count: 3, done: false, note: null }));

  assert.deepEqual(rows, [
    { key: "title", value: "장보기" },
    { key: "count", value: "3" },
    { key: "done", value: "false" },
    { key: "note", value: "null" },
  ]);
});

test("객체와 배열은 2칸 들여쓴 JSON 글로 바뀐다", () => {
  const rows = readableArgs(JSON.stringify({ tags: ["a", "b"], place: { name: "집" } }));

  assert.deepEqual(rows, [
    { key: "tags", value: '[\n  "a",\n  "b"\n]' },
    { key: "place", value: '{\n  "name": "집"\n}' },
  ]);
});

test("키 이름이 비밀처럼 보여도 값을 가리지 않고 받은 그대로 돌려준다", () => {
  const keys = ["token", "Access_Token", "client-secret", "PASSWORD", "password_hint", "apiKey", "Authorization",
    "cookie", "credentials"];
  const rows = readableArgs(JSON.stringify(Object.fromEntries(keys.map((key) => [key, "원문"]))));

  assert.deepEqual(rows, keys.map((key) => ({ key, value: "원문" })));
});

test("안쪽 객체에 든 비밀처럼 보이는 키의 값도 그대로 돌려준다", () => {
  const rows = readableArgs(JSON.stringify({ header: { "X-Api-Key": "원문", name: "보임" }, list: [{ token: "원문" }] }));

  assert.deepEqual(rows, [
    { key: "header", value: '{\n  "X-Api-Key": "원문",\n  "name": "보임"\n}' },
    { key: "list", value: '[\n  {\n    "token": "원문"\n  }\n]' },
  ]);
});

test("서버가 가려 보낸 글은 그대로 보인다", () => {
  const rows = readableArgs(JSON.stringify({ api_token: "[가림]", body: "끝에 [가림]" }));

  assert.deepEqual(rows, [
    { key: "api_token", value: "[가림]" },
    { key: "body", value: "끝에 [가림]" },
  ]);
});

test("마크다운과 HTML 로 보이는 글도 바꾸지 않고 그대로 돌려준다", () => {
  const rows = readableArgs(JSON.stringify({ body: "<b>굵게</b> **굵게**" }));

  assert.deepEqual(rows, [{ key: "body", value: "<b>굵게</b> **굵게**" }]);
});

test("객체로 읽히지 않는 인자는 null 이다", () => {
  assert.equal(readableArgs(null), null);
  assert.equal(readableArgs('{"title":"잘린 글…'), null);
  assert.equal(readableArgs('["a"]'), null);
  assert.equal(readableArgs('"글"'), null);
  assert.equal(readableArgs("null"), null);
});

test("빈 객체는 빈 목록이다", () => {
  assert.deepEqual(readableArgs("{}"), []);
});

test("승인 카드 인자는 빈 값을 빼고 앞에서부터 짧은 값 둘을 위에 둔다", () => {
  const args = approvalArgs(
    JSON.stringify({
      name: "업무",
      body: "첫 줄\n둘째 줄",
      background_color: "",
      tags: [],
      place: {},
      note: null,
      count: 0,
      visible: false,
      extra: "넷째",
    }),
  );

  assert.deepEqual(args, {
    rows: [
      { key: "name", value: "업무", core: true },
      { key: "body", value: "첫 줄\n둘째 줄", core: false },
      { key: "count", value: "0", core: true },
      { key: "visible", value: "false", core: false },
      { key: "extra", value: "넷째", core: false },
    ],
    emptyKeys: ["background_color", "tags", "place", "note"],
  });
});

test("짧은 값이 없으면 모든 인자를 위에 둔다", () => {
  const args = approvalArgs(JSON.stringify({ body: "가".repeat(81), list: ["a"] }));

  assert.deepEqual(
    args?.rows.map((row) => row.core),
    [true, true],
  );
});

test("객체로 읽히지 않는 인자는 승인 카드 인자도 null 이다", () => {
  assert.equal(approvalArgs(null), null);
  assert.equal(approvalArgs('["a"]'), null);
  assert.equal(approvalArgs('{"title":"잘린 글…'), null);
});

test("모두 승인에는 상시 허락을 줄 수 있는 WRITE 도구의 기다리는 줄만 든다", () => {
  const base: ConnectorAction = {
    actionId: "0f0e0d0c-0b0a-4908-8706-050403020100",
    connectorId: "gmail",
    toolName: "create_label",
    title: "라벨 만들기",
    risk: "WRITE",
    status: "PENDING",
    argsJson: "{}",
    resultText: null,
    errorCode: null,
    createdAt: "2026-10-06T00:00:00Z",
    expiresAt: null,
    grantAllowed: true,
    hiddenArgs: false,
  };

  assert.equal(bulkApprovable(base), true);
  for (const change of [
    { grantAllowed: false },
    { risk: "DESTRUCTIVE" as const },
    { risk: "FINANCIAL" as const },
    { risk: null },
    { hiddenArgs: true },
    { status: "EXECUTING" as const },
  ]) {
    assert.equal(bulkApprovable({ ...base, ...change }), false, JSON.stringify(change));
  }
});

test("줄바꿈 문자(\\r)가 든 값은 위에 두지 않는다", () => {
  const args = approvalArgs(JSON.stringify({ subject: "제목\r숨은 줄", name: "업무" }));

  assert.deepEqual(
    args?.rows.map((row) => [row.key, row.core]),
    [
      ["subject", false],
      ["name", true],
    ],
  );
});

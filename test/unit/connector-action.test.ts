import assert from "node:assert/strict";
import test from "node:test";
import {
  approvalArgs,
  approvalArgLabel,
  approvalArgLabels,
  bulkApprovable,
  type ConnectorAction,
  hiddenArgKeys,
  readableArgs,
} from "../../web/src/lib/connector-action.ts";

test("인자 이름은 도구별이며 모르는 키와 가린 키는 유지한다", () => {
  assert.equal(approvalArgLabel("body", { body: "본문" }), "본문");
  assert.equal(approvalArgLabel("body", { body: "글 내용" }), "글 내용");
  assert.equal(approvalArgLabel("unknown", { body: "본문" }), "unknown");
  assert.equal(approvalArgLabel("[가림]", { token: "토큰" }), "[가림]");
  assert.equal(approvalArgLabel("body", Object.create({ body: "가짜" })), "body");
  assert.equal(approvalArgLabel("body", { body: "잘못\n된 이름" }), "body");
});

test("카드의 선언 밖 키가 표시 이름과 충돌하면 양쪽에 원래 키를 병기한다", () => {
  const keys = ["to", "받는 사람"];
  assert.equal(approvalArgLabel("to", { to: "받는 사람" }, keys), "받는 사람 (to)");
  assert.equal(approvalArgLabel("받는 사람", { to: "받는 사람" }, keys), "받는 사람 (받는 사람)");
});

test("표시 이름의 Unicode와 UTF-8 및 항목 수 경계는 실제 compact JSON을 따른다", () => {
  assert.deepEqual(approvalArgLabels({ a: "😀".repeat(80) }), { a: "😀".repeat(80) });
  assert.deepEqual(approvalArgLabels({ a: "😀".repeat(81) }), {});
  const labels = Object.fromEntries(Array.from({ length: 64 }, (_, index) => ["k" + index, "😀".repeat(60) + index]));
  let remaining = 16384 - new TextEncoder().encode(JSON.stringify(labels)).length;
  for (let index = 0; index < 64; index++) {
    const added = Math.min(80 - [...labels["k" + index]].length, remaining);
    labels["k" + index] += "x".repeat(added);
    remaining -= added;
  }
  assert.equal(remaining, 0);
  assert.deepEqual(approvalArgLabels(labels), labels);
  labels.k63 += "x";
  assert.deepEqual(approvalArgLabels(labels), {});
  assert.deepEqual(approvalArgLabels(Object.fromEntries(Array.from({ length: 65 }, (_, index) => ["k" + index, "값" + index]))), {});
  for (const code of [0, 9, 10, 31, 127, 159, 0x61c, 0x200e, 0x2028, 0x2029, 0x202e, 0x2066, 0xd800]) {
    assert.deepEqual(approvalArgLabels({ a: "앞" + String.fromCharCode(code) + "뒤" }), {});
  }
});

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

test("가려진 글이 든 맨 위 인자의 키만 원래 순서로 낸다", () => {
  const args = JSON.stringify({
    to: "friend@example.com",
    filter_id: "[가림]",
    nested: { token: "[가림]" },
    body: "회의록 [가림] 끝",
    "[가림]": "값",
  });

  assert.deepEqual(hiddenArgKeys(args), ["filter_id", "nested", "body", "[가림]"]);
  assert.deepEqual(hiddenArgKeys(JSON.stringify({ to: "friend@example.com" })), []);
  assert.deepEqual(hiddenArgKeys("[가림]"), []);
  assert.deepEqual(hiddenArgKeys(null), []);
});

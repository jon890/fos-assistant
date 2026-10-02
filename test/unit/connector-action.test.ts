import assert from "node:assert/strict";
import test from "node:test";
import { MASKED_VALUE, readableArgs } from "../../web/src/lib/connector-action.ts";

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

test("키 이름이 비밀처럼 보이면 값을 가린다", () => {
  const keys = ["token", "Access_Token", "client-secret", "PASSWORD", "passwd", "apiKey", "api_key", "Authorization",
    "cookie", "credentials"];
  const rows = readableArgs(JSON.stringify(Object.fromEntries(keys.map((key) => [key, "원문"]))));

  assert.deepEqual(rows, keys.map((key) => ({ key, value: MASKED_VALUE })));
});

test("안쪽 객체에 든 비밀처럼 보이는 키도 가린다", () => {
  const rows = readableArgs(JSON.stringify({ header: { "X-Api-Key": "원문", name: "보임" }, list: [{ token: "원문" }] }));

  assert.equal(rows?.length, 2);
  assert.equal(rows?.some((row) => row.value.includes("원문")), false, "가리지 않은 값이 남았다");
  assert.equal(rows?.[0].value.includes("보임"), true);
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

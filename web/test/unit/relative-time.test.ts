import assert from "node:assert/strict";
import test from "node:test";
import { formatFullTime, formatRelative } from "../../web/src/lib/format.ts";

const NOW = new Date("2026-10-05T12:00:00Z");

const CASES: [string, string, string][] = [
  ["1분이 안 지났으면", "2026-10-05T11:59:30Z", "방금"],
  ["앞날짜면", "2026-10-05T12:00:30Z", "방금"],
  ["1시간이 안 지났으면 분을 내림해", "2026-10-05T11:15:00Z", "45분 전"],
  ["24시간이 안 지났으면 시간을 내림해", "2026-10-05T09:00:00Z", "3시간 전"],
  ["7일이 안 지났으면 날을 내림해", "2026-10-03T12:00:00Z", "2일 전"],
  ["7일이 넘었으면 서울 날짜로", "2026-09-20T03:00:00Z", "9월 20일"],
  ["읽지 못하는 값이면", "not-a-date", "-"],
];

for (const [name, value, expected] of CASES) {
  test(`상대 시각: ${name} 「${expected}」 다`, () => {
    assert.equal(formatRelative(value, NOW), expected, `${value} 를 ${NOW.toISOString()} 기준으로 적은 글`);
  });
}

test("전체 시각: 읽지 못하는 값이면 - 다", () => {
  assert.equal(formatFullTime("not-a-date"), "-");
});

test("전체 시각: 서울 시각으로 날짜와 시각을 함께 적는다", () => {
  const text = formatFullTime("2026-09-20T03:00:00Z");
  assert.match(text, /2026년 9월 20일/, `받은 글: ${text}`);
  assert.match(text, /12:00/, `받은 글: ${text}`);
});

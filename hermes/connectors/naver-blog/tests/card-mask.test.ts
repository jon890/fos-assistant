import { expect, test } from "bun:test";
import { cardWouldMask } from "../src/card-mask.ts";
import { validateOverwrite } from "../src/overwrite-draft.ts";

const LONG_HEX = "a".repeat(16) + "0123456789abcdef";
const JWT_HEADER = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");

test.each([
  ["32자 16진수", `코드 ${LONG_HEX} 끝`],
  ["32자 영문과 숫자", `링크 https://example.com/${"x".repeat(32)} 참고`],
  ["Bearer 토큰", "Bearer abc"],
  ["알려진 접두사 key", "키는 sk-abc123 이다"],
  ["alg 머리가 있는 JWT", `${JWT_HEADER}.eyJzdWIiOiIxIn0.c2ln`],
  ["authorization 머리", "Authorization: 무엇이든"],
  ["비밀 이름 뒤의 값", "password=abc"],
])("%s 는 승인 카드가 가린다", (_, text) => {
  expect(cardWouldMask(text)).toBe(true);
});

test.each([
  ["한국어 글", "오늘은 동네 국숫집에 다녀왔어요 😋"],
  ["31자 덩어리", `값 ${"x".repeat(31)} 끝`],
  ["UUID", "번호 11111111-2222-4333-8444-555555555555 확인"],
  ["짧은 링크", "https://example.com/menu/noodle"],
  ["도메인과 날짜", "example.com 2026.10.09 방문"],
  ["비밀이 아닌 이름의 값", "가격: 9000"],
])("%s 는 가리지 않는다", (_, text) => {
  expect(cardWouldMask(text)).toBe(false);
});

test("덮어쓸 글의 본문에 가려질 줄이 있으면 그 줄 번호로 알린다", () => {
  const problems = validateOverwrite({
    title: "가상국수",
    category: "맛집",
    tags: ["국수"],
    body: `첫 줄\n링크 ${LONG_HEX}`,
  });

  expect(problems).toEqual([expect.stringContaining("본문 2번째 줄에 승인 카드가 가리는")]);
});

import { expect, test } from "bun:test";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import * as expressions from "../src/expressions.ts";
import {
  CLICKING,
  EXPRESSIONS,
  FILL_EXPRESSIONS,
  FILL_FUNCTIONS,
  OPEN_OPTION_LISTS,
  ORDER_SHEET_AMOUNT,
} from "../src/expressions.ts";

/**
 * 주문서의 결제 단추와 결제로 이어질 수 있는 단추(2026-11-13 실측). 이 이름 조각은 플러그인 소스 어디에도 없어야 한다.
 * design.md 「채우기(REQ11, PR3c)」 의 금지 선택자와 같다.
 */
const FORBIDDEN = [
  "SubmitButton",
  "button-pay",
  "area-button",
  "NpayMoneyPromotionBanner",
  "Counter_",
];

/** 이 파일이 export 한 글 상수 모두. 식과 함수 선언과 그 조각이다. */
const constants = Object.values(expressions).filter(
  (value): value is string => typeof value === "string",
);

test("식 상수와 함수 선언에 결제 단추 선택자와 「결제」 글이 없다", () => {
  for (const source of [...constants, ...EXPRESSIONS, ...FILL_FUNCTIONS]) {
    expect(source).not.toContain("결제");
    for (const fragment of FORBIDDEN) expect(source).not.toContain(fragment);
  }
});

test("플러그인 소스 어디에도 결제 단추 선택자 조각이 없다", () => {
  const dir = join(import.meta.dir, "../src");
  for (const file of readdirSync(dir).filter((name) => name.endsWith(".ts"))) {
    const text = readFileSync(join(dir, file), "utf8");
    for (const fragment of FORBIDDEN) expect(text).not.toContain(fragment);
  }
});

test("누르는 식과 함수 선언은 모두 스토어 호스트에서만 누르고, 주문서 금액 식은 누르지 않는다", () => {
  for (const source of CLICKING) {
    expect(source).toContain("smartstore.naver.com");
    expect(source).toContain("location.hostname");
  }
  expect(ORDER_SHEET_AMOUNT).not.toContain(".click(");
  expect(ORDER_SHEET_AMOUNT).not.toContain("dispatchEvent");
  // 누르기는 CLICKING 의 식과 함수 선언에만 있다. 수량 맞추기는 값을 넣을 뿐 누르지 않는다.
  for (const source of [...EXPRESSIONS, ...FILL_FUNCTIONS])
    if (source.includes(".click(")) expect(CLICKING.has(source)).toBe(true);
});

test("읽기 도구가 쓸 수 있는 누르기 식은 옵션 드롭다운 열기 하나다", () => {
  const readOnly = [...EXPRESSIONS].filter(
    (source) => !FILL_EXPRESSIONS.has(source),
  );
  expect(readOnly.filter((source) => CLICKING.has(source))).toEqual([
    OPEN_OPTION_LISTS,
  ]);
});

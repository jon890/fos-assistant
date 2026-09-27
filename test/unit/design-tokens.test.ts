import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";

const GLOBALS_CSS = join(import.meta.dirname, "../../web/src/app/globals.css");

/**
 * ADR-023 의 표가 정한 색 토큰 이름이다.
 *
 * Tailwind v4 는 쓰지 않는 유틸리티를 만들지 않아, 아직 아무 화면도 `bg-popover` 를 쓰지 않으면
 * `--color-popover` 가 빠져도 브라우저 검사가 알아채지 못한다. 그래서 소스를 직접 읽는다.
 */
const TOKENS = [
  "background",
  "foreground",
  "muted",
  "muted-foreground",
  "accent",
  "accent-foreground",
  "secondary",
  "secondary-foreground",
  "card",
  "card-foreground",
  "popover",
  "popover-foreground",
  "border",
  "input",
  "ring",
  "primary",
  "primary-foreground",
  "primary-strong",
  "primary-soft",
  "destructive",
  "destructive-foreground",
  "code-keyword",
  "code-string",
  "code-number",
  "code-function",
  "code-type",
  "code-comment",
];

/** 옮기기 전의 이름이다. 새 이름의 별칭으로도 남기지 않는다. */
const RETIRED_TOKENS = ["brand", "brand-strong", "brand-soft", "on-brand", "surface", "surface-raised", "danger"];

/** `선택자 {` 부터 짝이 맞는 `}` 까지의 본문을 꺼낸다. 이 파일의 블록은 중첩되지 않는다. */
function block(source: string, selector: string): string {
  const start = source.indexOf(`${selector} {`);
  assert.notEqual(start, -1, `globals.css 에 ${selector} 블록이 없다`);
  const end = source.indexOf("}", start);
  return source.slice(start, end);
}

/** 블록 안에서 선언한 변수 이름을 모두 읽는다. */
function declared(body: string): Set<string> {
  return new Set([...body.matchAll(/^\s*(--[\w-]+)\s*:/gm)].map((match) => match[1]));
}

test("@theme inline 이 새 토큰마다 --color-X: var(--X) 를 선언한다", async () => {
  const theme = block(await readFile(GLOBALS_CSS, "utf-8"), "@theme inline");
  const missing = TOKENS.filter((name) => !new RegExp(`--color-${name}:\\s*var\\(--${name}\\);`).test(theme));
  assert.deepEqual(missing, [], `@theme inline 에 빠진 --color-*: ${missing.join(", ")}`);
});

test(":root 와 .dark 가 새 토큰을 모두 값으로 선언한다", async () => {
  const source = await readFile(GLOBALS_CSS, "utf-8");
  for (const selector of [":root", ".dark"]) {
    const variables = declared(block(source, selector));
    const missing = TOKENS.filter((name) => !variables.has(`--${name}`));
    assert.deepEqual(missing, [], `${selector} 에 빠진 토큰: ${missing.join(", ")}`);
  }
});

test("옛 토큰 이름이 globals.css 어디에도 남지 않는다", async () => {
  const source = await readFile(GLOBALS_CSS, "utf-8");
  const variables = new Set([
    ...declared(block(source, "@theme inline")),
    ...declared(block(source, ":root")),
    ...declared(block(source, ".dark")),
  ]);
  const left = RETIRED_TOKENS.flatMap((name) => [`--${name}`, `--color-${name}`]).filter((name) => variables.has(name));
  assert.deepEqual(left, [], `남아 있는 옛 토큰: ${left.join(", ")}`);
});

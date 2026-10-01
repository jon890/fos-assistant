import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { join, relative } from "node:path";
import test from "node:test";

const WEB_SRC = join(import.meta.dirname, "../../web/src");
const GLOBALS_CSS = join(WEB_SRC, "app/globals.css");

/**
 * ADR-023 과 ADR-051 의 표가 정한 색 토큰 이름이다.
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
  "foreground-soft",
  "primary-soft-foreground",
  "signal",
  "pill",
  "pill-foreground",
  "destructive-soft",
  "success",
  "success-soft",
  "warning",
  "warning-soft",
  "info",
  "info-soft",
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

/** `start` 뒤의 첫 `{` 부터 짝이 맞는 `}` 까지를 꺼낸다. 안에 든 블록의 중괄호를 세어 바깥 블록의 끝을 찾는다. */
function nestedBlock(source: string, start: number): string {
  const open = source.indexOf("{", start);
  assert.notEqual(open, -1, "블록을 여는 중괄호가 없다");
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === "{") depth += 1;
    else if (source[index] === "}") depth -= 1;
    if (depth === 0) return source.slice(open, index + 1);
  }
  assert.fail("블록을 닫는 중괄호가 없다");
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

test("줄인 움직임 설정을 따르는 블록이 globals.css 에 있다", async () => {
  const source = await readFile(GLOBALS_CSS, "utf-8");
  const start = source.indexOf("@media (prefers-reduced-motion: reduce)");
  assert.notEqual(start, -1, "globals.css 에 prefers-reduced-motion: reduce 블록이 없다");
  const body = nestedBlock(source, start);
  // 이동과 크기 변화를 만드는 tw-animate-css 변수를 이 블록이 덮어야 흐려짐만 남는다.
  const overrides = [
    "--tw-enter-scale: 1 !important",
    "--tw-enter-translate-x: 0 !important",
    "--tw-enter-translate-y: 0 !important",
    "--tw-exit-scale: 1 !important",
    "--tw-exit-translate-x: 0 !important",
    "--tw-exit-translate-y: 0 !important",
    "animation-duration: 100ms !important",
    "transition-duration: 100ms !important",
  ];
  const missing = overrides.filter((declaration) => !body.includes(declaration));
  assert.deepEqual(missing, [], `줄인 움직임 블록에 빠진 선언: ${missing.join(", ")}`);
});

test("@theme inline 이 움직임의 길이와 곡선 토큰을 선언한다", async () => {
  const variables = declared(block(await readFile(GLOBALS_CSS, "utf-8"), "@theme inline"));
  const names = ["--duration-fast", "--duration-base", "--duration-slow", "--ease-out", "--ease-spring"];
  const missing = names.filter((name) => !variables.has(name));
  assert.deepEqual(missing, [], `@theme inline 에 빠진 움직임 토큰: ${missing.join(", ")}`);
});

test("한국어 낱말 가운데서 줄을 바꾸지 않는다", async () => {
  const source = await readFile(GLOBALS_CSS, "utf-8");
  assert.match(source, /word-break:\s*keep-all;/, "globals.css 에 word-break: keep-all 이 없다");
});

test("색 변수 값은 모두 hex 로 적는다", async () => {
  const source = await readFile(GLOBALS_CSS, "utf-8");
  assert.equal(source.includes("oklch("), false, "globals.css 에 oklch( 가 남아 있다");
  for (const selector of [":root", ".dark"]) {
    const body = block(source, selector);
    const notHex = TOKENS.filter((name) => !new RegExp(`^\\s*--${name}:\\s*#(?:[\\da-f]{3}|[\\da-f]{6});`, "im").test(body));
    assert.deepEqual(notHex, [], `${selector} 에서 hex 가 아닌 색: ${notHex.join(", ")}`);
  }
});

test("화면 코드가 토큰 밖의 모서리 값을 쓰지 않는다", async () => {
  const entries = await readdir(WEB_SRC, { recursive: true, withFileTypes: true });
  const files = entries
    .filter((entry) => entry.isFile() && /\.tsx?$/.test(entry.name))
    .map((entry) => join(entry.parentPath, entry.name));
  assert.notEqual(files.length, 0, "web/src 에서 .ts 와 .tsx 파일을 찾지 못했다");

  const found: string[] = [];
  for (const file of files) {
    const lines = (await readFile(file, "utf-8")).split("\n");
    lines.forEach((line, index) => {
      for (const match of line.matchAll(/rounded-3xl|rounded-\[/g)) {
        found.push(`${relative(WEB_SRC, file)}:${index + 1} ${match[0]}`);
      }
    });
  }
  assert.deepEqual(found, [], `토큰 밖의 모서리 값: ${found.join(", ")}`);
});

/** web/src 의 .ts 와 .tsx 를 줄 단위로 읽어 `pattern` 에 걸린 곳을 `파일:줄 낱말` 로 모은다. */
async function findInSources(pattern: RegExp, allowed: (match: RegExpMatchArray) => boolean = () => false): Promise<string[]> {
  const entries = await readdir(WEB_SRC, { recursive: true, withFileTypes: true });
  const files = entries
    .filter((entry) => entry.isFile() && /\.tsx?$/.test(entry.name))
    .map((entry) => join(entry.parentPath, entry.name));
  assert.notEqual(files.length, 0, "web/src 에서 .ts 와 .tsx 파일을 찾지 못했다");

  const found: string[] = [];
  for (const file of files) {
    const lines = (await readFile(file, "utf-8")).split("\n");
    lines.forEach((line, index) => {
      for (const match of line.matchAll(pattern)) {
        if (!allowed(match)) found.push(`${relative(WEB_SRC, file)}:${index + 1} ${match[0]}`);
      }
    });
  }
  return found;
}

test("화면 코드가 움직임의 길이를 숫자로 적지 않는다", async () => {
  const found = await findInSources(/duration-(?:100|150|200|300)\b/g);
  assert.deepEqual(found, [], `토큰 밖의 움직임 길이: ${found.join(", ")}`);
});

test("motion-reduce: 는 끝없이 도는 움직임을 끄고 대체 문장으로 바꾸는 데에만 쓴다", async () => {
  // 줄인 움직임은 globals.css 의 블록 하나가 정한다. 부품은 끝없이 도는 표시를 끄거나 그 자리를 문장으로 바꿀 때만 적는다.
  const allowedTargets = new Set(["animate-none", "hidden", "flex", "inline"]);
  const found = await findInSources(/motion-reduce:([\w-]+)/g, (match) => allowedTargets.has(match[1]));
  assert.deepEqual(found, [], `motion-reduce: 를 허용하지 않는 곳에 썼다: ${found.join(", ")}`);
});

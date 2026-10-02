import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const WEB_SRC = join(import.meta.dirname, "../../web/src");

/** 문서 본문과 서비스 토큰 원문을 다루는 파일이다. 새 파일이 생기면 여기에 더한다. */
const SECRET_FILES = [
  "app/api/memory-documents/route.ts",
  "app/api/memory-documents/[id]/route.ts",
  "app/api/memory-collections/route.ts",
  "app/api/service-tokens/route.ts",
  "app/api/service-tokens/[id]/route.ts",
  "lib/memory-api.ts",
  "lib/memory-document.ts",
  "lib/service-token-api.ts",
];

function sourcesUnder(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    return statSync(path).isDirectory() ? sourcesUnder(path) : [path];
  });
}

const SECRET_SOURCES = [
  ...SECRET_FILES.map((file) => join(WEB_SRC, file)),
  ...sourcesUnder(join(WEB_SRC, "components/memory")),
];

test("문서 본문과 토큰 원문을 다루는 파일은 콘솔에 아무것도 남기지 않는다", () => {
  for (const file of SECRET_SOURCES) {
    assert.doesNotMatch(readFileSync(file, "utf-8"), /console\.|logger\./, file);
  }
});

test("토큰 원문과 문서 본문을 브라우저 저장소와 쿠키에 두지 않는다", () => {
  for (const file of SECRET_SOURCES) {
    assert.doesNotMatch(readFileSync(file, "utf-8"), /localStorage|sessionStorage|indexedDB|document\.cookie|history\.(push|replace)State/, file);
  }
});

test("브라우저 코드는 Control Plane 을 직접 부르지 않고 서버 라우트만 부른다", () => {
  const files = [...sourcesUnder(join(WEB_SRC, "components/memory")), join(WEB_SRC, "lib/memory-api.ts"), join(WEB_SRC, "lib/memory-document.ts"), join(WEB_SRC, "lib/service-token-api.ts")];
  for (const file of files) {
    const source = readFileSync(file, "utf-8");
    assert.doesNotMatch(source, /CONTROL_PLANE|\/api\/v1\//, file);
    for (const [, path] of source.matchAll(/["'`](\/api\/[^"'`]*)["'`]/g)) {
      assert.match(path, /^\/api\/(memor|service-tokens)/, `${file} 이 서버 라우트가 아닌 주소를 부른다: ${path}`);
    }
  }
});

test("토큰 발급 화면은 만료 없음 선택지를 두지 않고 만료를 1년까지로 제한한다", () => {
  const form = readFileSync(join(WEB_SRC, "components/memory/service-token-form.tsx"), "utf-8").replace(/\/\*[\s\S]*?\*\//g, "");
  const days = [...form.matchAll(/days:\s*(\d+)/g)].map((match) => Number(match[1]));
  assert.deepEqual(days, [30, 90, 365]);
  assert.doesNotMatch(form, /만료 없음|expiresInDays:\s*(null|undefined|0)/);
});

test("문서 목록 읽기는 본문 칸을 요구하지 않고 본문은 문서 하나를 열 때만 받는다", () => {
  const api = readFileSync(join(WEB_SRC, "lib/memory-document.ts"), "utf-8");
  const listBody = api.slice(api.indexOf("export function listDocuments"), api.indexOf("export function openDocument"));
  assert.match(listBody, /\/api\/memory-documents"/);
  assert.doesNotMatch(listBody, /content/);
  const section = readFileSync(join(WEB_SRC, "components/memory/document-section.tsx"), "utf-8");
  assert.doesNotMatch(section, /content/);
});

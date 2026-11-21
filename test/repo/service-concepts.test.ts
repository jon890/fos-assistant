// 서비스 개념 목록이 가리키는 화면 주소와 도구 이름이 실제로 있는지 본다(스펙 docs/features/service-concepts/spec.md DECISION18, REQ2).
// 화면 이름이나 주소, 도구 이름을 바꾸는 PR 은 이 검사에 걸려 목록을 함께 고친다.
import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const REPO_ROOT = join(import.meta.dirname, "../../");
const CONCEPTS = "backend/src/main/java/com/bifos/assistant/context/application/ServiceConceptsInstructions.java";
const APP = "web/src/app";
const MCP_APPLICATION = "backend/src/main/java/com/bifos/assistant/mcp/application";

function read(relative: string): string {
  return readFileSync(join(REPO_ROOT, relative), "utf8");
}

/** 개념 목록 글(텍스트 블록) 안의 서비스 안 주소다. 질의 문자열은 뗀다. */
export function conceptPaths(source: string): string[] {
  const block = source.slice(source.indexOf('"""'), source.lastIndexOf('"""'));
  const found = new Set<string>();
  for (const match of block.matchAll(/(?<![\w/.])(\/[a-z][a-z0-9/_\-{}<>]*)(?:\?[^\s,.]*)?/g)) found.add(match[1].replace(/\/$/, ""));
  return [...found];
}

/** 주소 하나가 `web/src/app` 의 경로와 맞는가. `{id}` 나 `<code>` 같은 틀 자리는 동적 경로 폴더(`[id]`)와 맞춘다. */
export function routeExists(appRoot: string, path: string): boolean {
  let dir = appRoot;
  for (const segment of path.split("/").filter(Boolean)) {
    const exact = join(dir, segment);
    if (!/^[{<].*[}>]$/.test(segment) && existsSync(exact) && statSync(exact).isDirectory()) {
      dir = exact;
      continue;
    }
    const dynamic = readdirSync(dir).find((name) => /^\[[^\]]+\]$/.test(name) && statSync(join(dir, name)).isDirectory());
    if (!dynamic) return false;
    dir = join(dir, dynamic);
  }
  return existsSync(join(dir, "page.tsx"));
}

/** Control Plane MCP 가 여는 도구 이름이다. 목록 정의의 이름과 도구 클래스의 이름 상수를 모은다. */
function mcpToolNames(): Set<string> {
  const names = new Set<string>();
  for (const file of readdirSync(join(REPO_ROOT, MCP_APPLICATION)).filter((name) => name.endsWith(".java"))) {
    const source = read(`${MCP_APPLICATION}/${file}`);
    for (const match of source.matchAll(/"name",\s*"([a-z_]+)"/g)) names.add(match[1]);
    for (const match of source.matchAll(/\n\s*"([a-z]+_[a-z_]+)",\n\s*"description"/g)) names.add(match[1]);
    for (const match of source.matchAll(/static final String [A-Z_]+ = "([a-z]+_[a-z_]+)";/g)) names.add(match[1]);
  }
  return names;
}

test("개념 목록의 화면 주소는 모두 web/src/app 의 화면이다", () => {
  const paths = conceptPaths(read(CONCEPTS));
  assert.ok(paths.length > 0, "주소를 찾지 못했다");
  for (const path of paths) assert.ok(routeExists(join(REPO_ROOT, APP), path), `${path} 화면이 없다`);
});

test("개념 목록이 이름으로 부르는 Control Plane 도구는 모두 도구 목록에 있다", () => {
  const block = read(CONCEPTS);
  const named = [...new Set([...block.matchAll(/\b([a-z]+(?:_[a-z]+)*_(?:list|propose|put|remember|delegate|create|delete|done|forget|attach))\b/g)].map((match) => match[1]))];
  const tools = mcpToolNames();
  assert.ok(named.length > 0, "도구 이름을 찾지 못했다");
  for (const name of named) assert.ok(tools.has(name), `${name} 도구가 없다`);
});

test("개념 목록에 사용량이 없고, 모델 단계나 에이전트를 바꾸는 도구가 없다", () => {
  assert.ok(!read(CONCEPTS).includes("사용량"));
  assert.ok(!read(CONCEPTS).includes("/usage"));
  const changing = [...mcpToolNames()].filter((name) => /tier|model/.test(name));
  assert.deepEqual(changing, []);
});

test("주소 맞추기는 질의 문자열을 떼고 틀 자리를 동적 경로와 맞춘다", () => {
  assert.deepEqual(conceptPaths('"""\n/new?agent=<code> 와 /tasks/new, /connections/{id}.\n11/20 은 아니다\n"""'), [
    "/new",
    "/tasks/new",
    "/connections/{id}",
  ]);
  const app = join(REPO_ROOT, APP);
  assert.ok(routeExists(app, "/connections/{id}"));
  assert.ok(!routeExists(app, "/no-such-screen"));
});

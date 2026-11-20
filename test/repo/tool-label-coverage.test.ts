/**
 * 비서가 받을 수 있는 도구마다 작업 과정의 한국어 이름과 종류가 있는지 본다(docs/features/app-shell/spec.md REQ14).
 *
 * <p>도구 이름은 도구를 등록하는 곳에서 읽는다.
 * - Control Plane MCP 도구: `McpToolCatalog.tools()` 와 그것이 부르는 정의 메서드
 * - Hermes 내장 도구: 에이전트에 켤 수 있는 toolset(`AgentToolPolicy`)과 profile 틀의 `platform_toolsets.api_server`
 * - fos-ctx 가 등록하는 도구: `hermes/plugins/fos-ctx/__init__.py` 의 `register_tool`
 * - MCP 도구 찾기 중계: `hermes/tests/hermes_contract.py` 의 `TOOL_GUARD_BRIDGE`
 * 플러그인 도구는 서비스 이름으로 보이므로 대상이 아니다.
 */
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { toolKind } from "../../web/src/lib/tool-kind.ts";
import { LABELED_TOOLS, toolLabel } from "../../web/src/lib/tool-label.ts";

const ROOT = join(import.meta.dirname, "../..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");
const quoted = (text: string) => [...text.matchAll(/"([^"]+)"/g)].map((match) => match[1]!);

const MCP_DIR = "backend/src/main/java/com/bifos/assistant/mcp/application";
const MCP_PREFIX = "mcp__fos_assistant__";

/**
 * Hermes 의 toolset 마다 든 내장 도구다. `hermes/tests/hermes_contract.py` 의 `HERMES_TOOLSETS` 를 읽는다.
 * 그 표가 고정 버전 Hermes 의 `TOOLSETS` 와 같은지는 `hermes/tests/test_tool_guard_contract.py` 가 본다.
 */
function hermesToolsets(): Record<string, string[]> {
  const source = read("hermes/tests/hermes_contract.py");
  const start = source.indexOf("HERMES_TOOLSETS = {");
  assert.notEqual(start, -1, "HERMES_TOOLSETS 를 찾지 못했다");
  const body = source.slice(start, source.indexOf("\n}\n", start));
  const entries = [...body.matchAll(/^ {4}"([^"]+)": \(([^)]*)\),$/gm)];
  assert.equal(entries.length, (body.match(/^ {4}"/gm) ?? []).length, "HERMES_TOOLSETS 의 줄을 모두 읽지 못했다");
  return Object.fromEntries(entries.map(([, name, tools]) => [name!, quoted(tools!)]));
}

/**
 * 켤 수 있지만 내장 도구가 없는 toolset 이다. 도구 이름은 다른 곳이 갖는다.
 * - `fos-assistant`: Control Plane MCP 서버. 도구는 `McpToolCatalog` 에서 읽는다
 * - `fos-attachments`: fos-ctx 가 등록하는 도구. `register_tool` 에서 읽는다
 * - `homeassistant`, `spotify`: 고정 버전 Hermes 의 `toolsets.py` 에 없고 따로 설치하는 plugin 이 도구를 가져온다
 */
const NON_BUILTIN_TOOLSETS = new Set(["fos-assistant", "fos-attachments", "homeassistant", "spotify"]);


/** Python 의 `NAME = frozenset({...})` 안의 글자들이다. */
function pythonSet(source: string, name: string): string[] {
  const start = source.indexOf(`${name} = `);
  assert.notEqual(start, -1, `${name} 를 찾지 못했다`);
  const open = source.indexOf("{", start);
  return quoted(source.slice(open, source.indexOf("}", open)));
}

/** Java 의 `NAME = Set.of(...)` 안의 글자들이다. */
function javaSet(source: string, name: string): string[] {
  const start = source.indexOf(`${name} = Set.of(`);
  assert.notEqual(start, -1, `${name} 를 찾지 못했다`);
  return quoted(source.slice(start, source.indexOf(");", start)));
}

/** `open` 위치의 `{` 와 짝인 `}` 까지의 본문이다. 글자 상수 안의 괄호는 세지 않는다. */
function block(source: string, open: number): string {
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    const ch = source[i];
    if (ch === '"') {
      for (i++; source[i] !== '"'; i++) if (source[i] === "\\") i++;
    } else if (ch === "{") depth++;
    else if (ch === "}" && --depth === 0) return source.slice(open, i + 1);
  }
  throw new Error("괄호의 짝이 없다");
}

function methodBody(source: string, method: string): string {
  const match = new RegExp(`\\b${method}\\(\\)\\s*\\{`).exec(source);
  assert.ok(match, `${method}() 를 찾지 못했다`);
  return block(source, match.index + match[0].length - 1);
}

/** 정의 메서드 본문의 도구 이름이다. `Map.of("name", 이름` 과 `definition(이름` 꼴을 읽고 상수는 같은 파일에서 푼다. */
function definedNames(body: string, source: string): string[] {
  return [...body.matchAll(/(?:Map\.of\(\s*"name",\s*|\bdefinition\(\s*)(?:"([a-z][a-z0-9_]*)"|([A-Z][A-Z0-9_]*)\b)/g)].map(
    ([, literal, constant]) => {
      if (literal) return literal;
      const value = new RegExp(`static final String ${constant} = "([^"]+)"`).exec(source);
      assert.ok(value, `상수 ${constant} 를 풀지 못했다`);
      return value[1]!;
    },
  );
}

/** `McpToolCatalog` 필드 가운데 도구 정의를 내지 않는 것이다. 이 밖의 필드 호출은 정의를 읽지 못하면 실패한다. */
const CATALOG_NON_TOOL_FIELDS = new Set(["delegationProperties"]);

function controlPlaneTools(): string[] {
  const catalog = read(join(MCP_DIR, "McpToolCatalog.java"));
  const body = methodBody(catalog, "tools");
  const fields = new Map(
    [...catalog.matchAll(/private final (\w+) (\w+);/g)].map(([, type, field]) => [field!, type!]),
  );
  const names = definedNames(body, catalog);
  assert.equal(
    names.length,
    (body.match(/Map\.of\(\s*"name"/g) ?? []).length,
    "McpToolCatalog 안에 직접 쓴 정의의 이름을 모두 읽지 못했다",
  );
  for (const [call, field, method, args] of body.matchAll(/\b([a-z]\w*)\.(\w+)\(([^)]*)\)/g)) {
    if (CATALOG_NON_TOOL_FIELDS.has(field!) || !fields.has(field!)) continue;
    assert.equal(args!.trim(), "", `${call} 는 인자를 받아 정의를 읽지 못한다`);
    const file = join(MCP_DIR, `${fields.get(field!)}.java`);
    assert.ok(existsSync(join(ROOT, file)), `${call} 의 정의 파일 ${file} 이 없다`);
    const source = read(file);
    const defined = definedNames(methodBody(source, method!), source);
    assert.ok(defined.length > 0, `${call} 에서 도구 이름을 읽지 못했다`);
    names.push(...defined);
  }
  return names;
}

function fosCtxTools(): string[] {
  const dir = "hermes/plugins/fos-ctx";
  const init = read(join(dir, "__init__.py"));
  const calls = (init.match(/register_tool\(/g) ?? []).length;
  const found = [...init.matchAll(/register_tool\(name=(\w+)/g)];
  assert.equal(found.length, calls, "register_tool 호출을 모두 읽지 못했다");
  return found.map(([, ident]) => {
    const imported = new RegExp(`from \\.(\\w+) import [^\\n]*\\b(\\w+) as ${ident}\\b`).exec(init);
    const source = imported ? read(join(dir, `${imported[1]}.py`)) : init;
    const constant = imported ? imported[2] : ident;
    const value = new RegExp(`^${constant} = "([^"]+)"`, "m").exec(source);
    assert.ok(value, `${ident} 를 풀지 못했다`);
    return value[1]!;
  });
}

/** 비서 에이전트에 켤 수 있는 toolset 이다. 에이전트 화면에서 켜는 것과 profile 틀이 늘 여는 것을 합친다. */
function reachableToolsets(): string[] {
  const policy = read("backend/src/main/java/com/bifos/assistant/agent/domain/AgentToolPolicy.java");
  const template = read("hermes/profile-template/config.yaml.template");
  const lines = template.split("\n");
  const head = lines.findIndex(
    (line, i) => line.trim() === "api_server:" && lines[i - 1]?.trim() === "platform_toolsets:",
  );
  assert.notEqual(head, -1, "platform_toolsets.api_server 를 찾지 못했다");
  const platform: string[] = [];
  for (const line of lines.slice(head + 1)) {
    if (line.trim() === "" || line.trim().startsWith("#")) continue;
    const item = /^\s+- (\S+)$/.exec(line);
    if (!item) break;
    platform.push(item[1]!);
  }
  assert.ok(platform.length > 0, "platform_toolsets.api_server 를 읽지 못했다");
  return [...new Set([...javaSet(policy, "OWNER_TOOLSETS"), ...javaSet(policy, "ADMIN_TOOLSETS"), ...platform])];
}

function reachableBuiltinTools(): string[] {
  const toolsets = hermesToolsets();
  return reachableToolsets().flatMap((toolset) => toolsets[toolset] ?? []);
}

function assertNamed(name: string) {
  assert.notEqual(toolLabel(name, true), toolLabel("unknown-tool", true), `${name} 의 도는 문장이 없다`);
  assert.notEqual(toolLabel(name, false), toolLabel("unknown-tool", false), `${name} 의 끝난 문장이 없다`);
  assert.notEqual(toolKind(name).id, "other", `${name} 의 종류가 없다`);
}

test("에이전트에 켤 수 있는 toolset 은 모두 도구 목록을 안다", () => {
  const unknown = reachableToolsets().filter(
    (toolset) => !Object.hasOwn(hermesToolsets(), toolset) && !NON_BUILTIN_TOOLSETS.has(toolset),
  );
  assert.deepEqual(unknown, []);
});

test("도구를 등록하는 곳을 읽어 낸 목록이 비지 않는다", () => {
  const mcp = controlPlaneTools();
  for (const name of ["memory_read", "content_draft_write", "connector_list", "memory_remember", "memory_search"]) {
    assert.ok(mcp.includes(name), `Control Plane MCP 도구 ${name} 를 읽지 못했다`);
  }
  assert.ok(reachableBuiltinTools().includes("image_generate"));
  assert.ok(reachableBuiltinTools().includes("delegate_task"));
  assert.deepEqual(fosCtxTools(), ["attachment_inspect"]);
});

test("Control Plane MCP 도구는 모두 한국어 이름과 종류가 있다", () => {
  for (const name of controlPlaneTools()) {
    assertNamed(name);
    assertNamed(`${MCP_PREFIX}${name}`);
    assert.notEqual(toolKind(`${MCP_PREFIX}${name}`).id, "connector", `${name} 가 연결된 서비스로 보인다`);
  }
});

test("에이전트에 켤 수 있는 Hermes 내장 도구는 모두 한국어 이름과 종류가 있다", () => {
  for (const name of reachableBuiltinTools()) assertNamed(name);
});

test("fos-ctx 가 등록하는 도구와 MCP 도구 찾기 중계는 한국어 이름과 종류가 있다", () => {
  for (const name of fosCtxTools()) assertNamed(name);
  for (const name of pythonSet(read("hermes/tests/hermes_contract.py"), "TOOL_GUARD_BRIDGE")) assertNamed(name);
});

/**
 * 문장 표에 있지만 지금 등록하는 곳이 없는 이름이다. 지난 대화의 작업 과정이 이 이름으로 남아 있다.
 * - `memory_link`: 기억 잇기 도구. 대체 제안으로 바뀌며 등록에서 빠졌다
 * - `task_*`: #818 이 Control Plane MCP 에 등록한다. 머지 전에 먼저 넣어 둔다
 */
const UNREGISTERED_LABELS = new Set([
  "memory_link",
  "task_list",
  "task_create",
  "task_update",
  "task_set_state",
  "task_delete",
]);

test("문장 표의 도구는 모두 등록하는 곳이 있다", () => {
  const registered = new Set([
    ...controlPlaneTools(),
    ...Object.values(hermesToolsets()).flat(),
    ...fosCtxTools(),
    ...pythonSet(read("hermes/tests/hermes_contract.py"), "TOOL_GUARD_BRIDGE"),
  ]);
  const stale = LABELED_TOOLS.filter((name) => !registered.has(name) && !UNREGISTERED_LABELS.has(name));
  assert.deepEqual(stale, []);
  for (const name of LABELED_TOOLS) assertNamed(name);
});

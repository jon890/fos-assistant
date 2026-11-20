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
import { toolLabel } from "../../web/src/lib/tool-label.ts";

const ROOT = join(import.meta.dirname, "../..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8");

const MCP_DIR = "backend/src/main/java/com/bifos/assistant/mcp/application";
const MCP_PREFIX = "mcp__fos_assistant__";

/**
 * Hermes 의 toolset 마다 든 내장 도구다. 손으로 옮긴 목록이고 원본은 고정 버전 Hermes 의 `toolsets.py` 의 `TOOLSETS` 다
 * (버전은 `hermes/tests/hermes_contract.py` 의 `HERMES_VERSION`). 여러 toolset 을 묶은 플랫폼 묶음(`hermes-*`, `coding` 등)은 뺐다.
 * 합친 이름이 계약의 `HERMES_BUILTIN_TOOLS` 와 같은지 아래 테스트가 본다. Hermes 를 올려 그 목록이 바뀌면 여기도 고친다.
 */
const HERMES_TOOLSETS: Record<string, string[]> = {
  web: ["web_search", "web_extract"],
  search: ["web_search"],
  x_search: ["x_search"],
  vision: ["vision_analyze"],
  video: ["video_analyze"],
  image_gen: ["image_generate"],
  video_gen: ["video_generate", "xai_video_edit", "xai_video_extend"],
  computer_use: ["computer_use"],
  terminal: ["terminal", "process_manage"],
  skills: ["skills_list", "skill_view", "skill_manage"],
  browser: ["browser_exec", "browser_cdp", "browser_dialog", "browser_vault"],
  cronjob: ["cronjob_manage"],
  file: ["read_file", "write_file", "patch", "search_files"],
  tts: ["text_to_speech"],
  todo: ["todo_list"],
  memory: ["memory"],
  session_search: ["session_search"],
  connections: ["manage_connections"],
  project: ["desktop_project"],
  desktop_ui: [
    "read_terminal", "close_terminal", "desktop_preview", "drive_preview", "annotate_preview",
    "read_window_below", "focus_pane", "react_to_message", "gui_tour", "show_tip", "set_theme",
  ],
  onboarding: ["setup_beat"],
  catalog: ["manage_catalog"],
  clarify: ["clarify"],
  code_execution: ["execute_code"],
  delegation: ["delegate_task"],
  kanban: [
    "kanban_show", "kanban_list", "kanban_complete", "kanban_block", "kanban_request_review",
    "kanban_request_changes", "kanban_heartbeat", "kanban_comment", "kanban_create", "kanban_link",
    "kanban_unblock", "kanban_attach", "kanban_attach_url", "kanban_attachments",
  ],
  discord: ["discord"],
  discord_admin: ["discord_admin"],
  feishu_doc: ["feishu_doc_read"],
  feishu_drive: [
    "feishu_drive_list_comments", "feishu_drive_list_comment_replies",
    "feishu_drive_reply_comment", "feishu_drive_add_comment",
  ],
};

/**
 * 켤 수 있지만 내장 도구가 없는 toolset 이다. 도구 이름은 다른 곳이 갖는다.
 * - `fos-assistant`: Control Plane MCP 서버. 도구는 `McpToolCatalog` 에서 읽는다
 * - `fos-attachments`: fos-ctx 가 등록하는 도구. `register_tool` 에서 읽는다
 * - `homeassistant`, `spotify`: 고정 버전 Hermes 의 `toolsets.py` 에 없고 따로 설치하는 plugin 이 도구를 가져온다
 */
const NON_BUILTIN_TOOLSETS = new Set(["fos-assistant", "fos-attachments", "homeassistant", "spotify"]);

const quoted = (text: string) => [...text.matchAll(/"([^"]+)"/g)].map((match) => match[1]!);

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

function controlPlaneTools(): string[] {
  const catalog = read(join(MCP_DIR, "McpToolCatalog.java"));
  const body = methodBody(catalog, "tools");
  const fields = new Map(
    [...catalog.matchAll(/private final (\w+) (\w+);/g)].map(([, type, field]) => [field!, type!]),
  );
  const names = definedNames(body, catalog);
  for (const [, field, method] of body.matchAll(/\b([a-z]\w*)\.(\w+)\(\)/g)) {
    const type = fields.get(field!);
    const file = join(MCP_DIR, `${type}.java`);
    if (!type || !existsSync(join(ROOT, file))) continue;
    const source = read(file);
    names.push(...definedNames(methodBody(source, method!), source));
  }
  return names;
}

function fosCtxTools(): string[] {
  const dir = "hermes/plugins/fos-ctx";
  const init = read(join(dir, "__init__.py"));
  return [...init.matchAll(/register_tool\(name=(\w+)/g)].map(([, ident]) => {
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
  const section = template.slice(template.indexOf("platform_toolsets:"));
  const apiServer = section.slice(section.indexOf("api_server:"), section.indexOf("\n\n"));
  const platform = [...apiServer.matchAll(/^\s+- (\S+)$/gm)].map((match) => match[1]!);
  assert.ok(platform.length > 0, "platform_toolsets.api_server 를 읽지 못했다");
  return [...new Set([...javaSet(policy, "OWNER_TOOLSETS"), ...javaSet(policy, "ADMIN_TOOLSETS"), ...platform])];
}

function reachableBuiltinTools(): string[] {
  return reachableToolsets().flatMap((toolset) => HERMES_TOOLSETS[toolset] ?? []);
}

function assertNamed(name: string) {
  assert.notEqual(toolLabel(name, true), toolLabel("unknown-tool", true), `${name} 의 도는 문장이 없다`);
  assert.notEqual(toolLabel(name, false), toolLabel("unknown-tool", false), `${name} 의 끝난 문장이 없다`);
  assert.notEqual(toolKind(name).id, "other", `${name} 의 종류가 없다`);
}

test("손으로 옮긴 toolset 목록은 Hermes 계약의 내장 도구 목록과 같다", () => {
  const contract = new Set(pythonSet(read("hermes/tests/hermes_contract.py"), "HERMES_BUILTIN_TOOLS"));
  const copied = new Set(Object.values(HERMES_TOOLSETS).flat());
  assert.deepEqual([...copied].filter((name) => !contract.has(name)), [], "계약에 없는 도구");
  assert.deepEqual([...contract].filter((name) => !copied.has(name)), [], "toolset 목록에 빠진 도구");
});

test("에이전트에 켤 수 있는 toolset 은 모두 도구 목록을 안다", () => {
  const unknown = reachableToolsets().filter(
    (toolset) => !Object.hasOwn(HERMES_TOOLSETS, toolset) && !NON_BUILTIN_TOOLSETS.has(toolset),
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

import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import test from "node:test";

const APP_ROOT = join(import.meta.dirname, "../../web/src/app");

const SRC_ROOT = join(APP_ROOT, "..");

/**
 * 뼈대를 두기로 한 경로와, 그 화면의 바깥 틀(`mx-auto w-full max-w-*`)을 그리는 파일이다.
 *
 * `/agents/{code}` 처럼 대괄호 세그먼트가 있는 경로는 `web/src/app` 아래 디렉터리 이름 그대로 적는다.
 * 틀 파일은 `web/src` 기준이다. 바깥 틀이 `page.tsx` 가 아니라 그 화면의 첫 부품에 있는 경로가 있다.
 */
const ROUTE_FRAMES: Record<string, string> = {
  agents: "app/agents/page.tsx",
  "agents/[code]": "components/agent/persona-editor.tsx",
  memory: "components/memory/memory-list.tsx",
  usage: "app/usage/page.tsx",
  "executions/[id]": "app/executions/[id]/page.tsx",
  "admin/people": "app/admin/people/people-admin-panel.tsx",
  "chat/[conversationId]": "components/chat/message-list.tsx",
  "c/[conversationId]": "components/chat/message-list.tsx",
};

/** 화면 파일에서 처음 나오는 바깥 틀의 폭을 읽는다. 틀이 없으면 `undefined` 다. */
function frameWidth(source: string): string | undefined {
  return /className="[^"]*\bmx-auto\b[^"]*\bmax-w-(\w+)\b[^"]*"/.exec(source)?.[1];
}

async function findFiles(dir: string, name: string): Promise<string[]> {
  const entries = await readdir(dir, { withFileTypes: true });
  const found: string[] = [];
  for (const entry of entries) {
    const entryPath = join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...(await findFiles(entryPath, name)));
    } else if (entry.name === name) {
      found.push(entryPath);
    }
  }
  return found;
}

async function fileExists(path: string): Promise<boolean> {
  try {
    await readFile(path);
    return true;
  } catch {
    return false;
  }
}

test("callControlPlane 을 부르는 page.tsx 마다 같은 자리에 loading.tsx 가 있다", async () => {
  const pageFiles = await findFiles(APP_ROOT, "page.tsx");
  const missing: string[] = [];
  for (const pageFile of pageFiles) {
    const content = await readFile(pageFile, "utf-8");
    if (!content.includes("callControlPlane")) continue;
    const loadingFile = join(pageFile, "..", "loading.tsx");
    if (!(await fileExists(loadingFile))) missing.push(pageFile);
  }
  assert.deepEqual(missing, []);
});

test("경로마다 loading.tsx 의 width 가 그 화면의 바깥 틀 폭과 같다", async () => {
  for (const [route, frameFile] of Object.entries(ROUTE_FRAMES)) {
    const expectedWidth = frameWidth(await readFile(join(SRC_ROOT, frameFile), "utf-8"));
    assert.ok(expectedWidth, `${frameFile} 에서 mx-auto 와 max-w-* 가 있는 바깥 틀을 찾지 못했다`);
    const content = await readFile(join(APP_ROOT, route, "loading.tsx"), "utf-8");
    const match = /width="([^"]+)"/.exec(content);
    assert.ok(match, `${route} 의 loading.tsx 에 width 가 없다`);
    assert.equal(match?.[1], expectedWidth, `${route} 의 width 가 ${frameFile} 의 max-w-${expectedWidth} 와 다르다`);
  }
});

test("loading.tsx 는 뼈대를 두기로 한 경로에만 있다", async () => {
  const loadingFiles = await findFiles(APP_ROOT, "loading.tsx");
  const routes = loadingFiles.map((file) => relative(APP_ROOT, join(file, "..")).split(sep).join("/")).sort();
  assert.deepEqual(routes, Object.keys(ROUTE_FRAMES).sort());
});

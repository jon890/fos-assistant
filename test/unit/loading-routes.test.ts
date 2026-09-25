import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";
import test from "node:test";

const APP_ROOT = join(import.meta.dirname, "../../web/src/app");

/**
 * 뼈대를 두기로 한 여덟 경로와 그 `width` 값이다.
 *
 * `/agents/{code}` 처럼 대괄호 세그먼트가 있는 경로는 `web/src/app` 아래 디렉터리 이름 그대로 적는다.
 */
const ROUTE_WIDTHS: Record<string, string> = {
  agents: "2xl",
  "agents/[code]": "2xl",
  memory: "4xl",
  usage: "5xl",
  "executions/[id]": "5xl",
  "admin/agents": "4xl",
  "admin/people": "4xl",
  "c/[conversationId]": "3xl",
};

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

test("경로마다 loading.tsx 가 있고 그 width 가 화면의 바깥 틀과 같다", async () => {
  for (const [route, expectedWidth] of Object.entries(ROUTE_WIDTHS)) {
    const loadingFile = join(APP_ROOT, route, "loading.tsx");
    const content = await readFile(loadingFile, "utf-8");
    const match = /width="([^"]+)"/.exec(content);
    assert.ok(match, `${route} 의 loading.tsx 에 width 가 없다`);
    assert.equal(match?.[1], expectedWidth, `${route} 의 width 가 다르다`);
  }
});

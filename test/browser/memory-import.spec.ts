import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";
import { cleanupMemories, memoryRow, openDocumentTab, openMemoryAdvanced } from "./memory-page.ts";

const MARK = "평문-표식-7391";

type Item = {
  sourceRef: string;
  sourceDate: string | null;
  collection: string;
  entryType: string;
  documentKey: string | null;
  title: string;
  content: string;
  sensitive: boolean;
  retrieval: string;
};

function memoryItem(project: string, title: string): Item {
  return {
    sourceRef: `private/wiki/sample/e2e-${project}-memory.md`,
    sourceDate: "2026-01-02",
    collection: "core",
    entryType: "MEMORY",
    documentKey: null,
    title,
    content: MARK,
    sensitive: false,
    retrieval: "SEARCH",
  };
}

function documentItem(project: string, title: string, key: string, collection = "career"): Item {
  return {
    sourceRef: `private/wiki/sample/e2e-${project}-${key}.md`,
    sourceDate: "2026-01-03",
    collection,
    entryType: "DOCUMENT",
    documentKey: key,
    title,
    content: `${MARK} 문서`,
    sensitive: true,
    retrieval: "SEARCH",
  };
}

async function choose(page: Page, items: Item[], name = "bundle-001.json") {
  const bundle = { schemaVersion: 1, createdAt: "2026-10-02T00:00:00Z", items };
  await page.getByLabel("가져올 파일").setInputFiles({
    name,
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(bundle)),
  });
}

test("묶음을 올리면 미리보기가 보이고 본문은 보이지 않는다", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const memoryTitle = `가져올 기억 ${project}`;
  const documentTitle = `가져올 문서 ${project}`;
  await openMemoryAdvanced(page);
  await choose(page, [memoryItem(project, memoryTitle), documentItem(project, documentTitle, `doc-a-${project}`)]);

  await expect(page.getByText("새로 가져와요 2개")).toBeVisible();
  await expect(page.getByText(memoryTitle)).toBeVisible();
  await expect(page.getByText(documentTitle)).toBeVisible();
  await expect(page.getByText("민감", { exact: true })).toBeVisible();
  await expect(page.getByText(MARK)).toHaveCount(0);
});

test("가져오면 목록에 생기고 같은 묶음을 다시 올리면 가져올 것이 없다", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const memoryTitle = `가져온 기억 ${project}`;
  const documentTitle = `가져온 문서 ${project}`;
  const items = [memoryItem(project, memoryTitle), documentItem(project, documentTitle, `doc-b-${project}`)];
  try {
    await openMemoryAdvanced(page);
    await choose(page, items);
    await page.getByRole("button", { name: "2개 가져오기" }).click();

    await expect(page.getByText("2개를 가져왔어요")).toBeVisible();
    await expect(memoryRow(page, memoryTitle)).toBeVisible();
    await openDocumentTab(page);
    await expect(memoryRow(page, documentTitle)).toBeVisible();

    await choose(page, items);
    await expect(page.getByText("이미 가져왔어요 2개")).toBeVisible();
    await expect(page.getByRole("button", { name: /개 가져오기/ })).toBeDisabled();
  } finally {
    await cleanupMemories(page, [memoryTitle, documentTitle]);
  }
});

test("같은 이름의 문서와 부딪히면 가져오지 않는다", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const key = `taken-${project}`;
  const title = `먼저 있던 문서 ${project}`;
  try {
    const created = await page.request.post("/api/memory-documents", {
      data: { collection: "career", documentKey: key, title, content: "기존 본문", sensitive: false },
    });
    expect(created.ok()).toBe(true);
    await openMemoryAdvanced(page);
    await choose(page, [documentItem(project, `부딪히는 문서 ${project}`, key)]);

    await expect(page.getByText("같은 이름의 문서가 이미 있어요")).toBeVisible();
    await expect(page.getByRole("button", { name: /개 가져오기/ })).toBeDisabled();
  } finally {
    await cleanupMemories(page, [title]);
  }
});

test("신원 기록은 아직 가져올 수 없다", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  await openMemoryAdvanced(page);
  await choose(page, [documentItem(project, `신원 문서 ${project}`, `id-${project}`, "identity")]);

  await expect(page.getByText("신원 기록은 아직 가져올 수 없어요")).toBeVisible();
  await expect(page.getByRole("button", { name: /개 가져오기/ })).toBeDisabled();
});

test("묶음이 아닌 파일은 거절하고 요청을 보내지 않는다", async ({ page }) => {
  const requests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/memory-imports")) requests.push(request.url());
  });
  await openMemoryAdvanced(page);
  await page.getByLabel("가져올 파일").setInputFiles({
    name: "other.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify({ hello: 1 })),
  });

  await expect(page.getByText("가져올 수 있는 파일이 아니에요.")).toBeVisible();
  expect(requests).toEqual([]);
});

test("취소하면 미리보기가 사라진다", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const title = `취소할 기억 ${project}`;
  await openMemoryAdvanced(page);
  await choose(page, [memoryItem(project, title)]);
  await expect(page.getByText(title)).toBeVisible();

  await page.getByRole("button", { name: "취소" }).click();
  await expect(page.getByText(title)).toHaveCount(0);
});

test("좁은 화면에서도 미리보기가 가로로 넘치지 않는다", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const longTitle = `아주 긴 제목 ${project} `.repeat(8).trim();
  await openMemoryAdvanced(page);
  await choose(page, [memoryItem(project, longTitle.slice(0, 190))]);
  await expect(page.getByText("새로 가져와요 1개")).toBeVisible();

  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(scrollWidth).toBeLessThanOrEqual(page.viewportSize()!.width);
});

test("2MB 를 넘는 묶음은 웹 서버가 먼저 거절한다", async ({ page }) => {
  await openMemoryAdvanced(page);
  const body = JSON.stringify({ schemaVersion: 1, items: [{ content: "가".repeat(1_000_000) }] });
  for (const path of ["/api/memory-imports/preview", "/api/memory-imports"]) {
    const response = await page.request.post(path, {
      data: body,
      headers: { "Content-Type": "application/json" },
    });
    expect(response.status()).toBe(413);
    expect((await response.json()).code).toBe("MEMORY_IMPORT_TOO_LARGE");
  }
});

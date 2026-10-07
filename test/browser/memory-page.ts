import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect } from "./fixtures.ts";

/** 기억 화면 검사들이 함께 쓰는 조작이다. 손으로 만드는 양식은 숨겨져 있으므로 항목은 API 로 만든다. */

/** 제목으로 기억이나 문서의 한 줄을 찾는다. 숨은 탭의 줄은 찾지 않는다. */
export function memoryRow(page: Page, title: string) {
  return page
    .getByRole("listitem")
    .filter({ has: page.getByRole("heading", { name: title, exact: true }) });
}

/** 줄을 눌러 펼친다. 이미 펼쳐져 있으면 그대로 둔다. */
export async function openRow(page: Page, title: string) {
  const toggle = memoryRow(page, title).getByRole("button", { name: title });
  if ((await toggle.getAttribute("aria-expanded")) !== "true") await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * 펼친 줄의 「지우기」 를 누르고 확인 창에서 지운다. 지우기 요청이 끝나고 확인 창이 닫힐 때까지 기다린다.
 *
 * <p>확인 창이 열린 동안에는 나머지 화면이 접근성 트리에서 빠진다. 닫히기 전에 줄 수를 세면 지우지 않은 줄도 0 으로 센다.
 */
export async function confirmDelete(page: Page, title: string) {
  const removed = page.waitForResponse((response) => response.request().method() === "DELETE");
  await memoryRow(page, title).getByRole("button", { name: "지우기" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "지우기" }).click();
  await removed;
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
}

export async function createMemory(
  page: Page,
  input: { scope: "USER" | "GROUP"; title: string; content: string; alwaysInject?: boolean },
) {
  const response = await page.request.post("/api/memories", {
    data: { alwaysInject: false, ...input },
  });
  expect(response.ok()).toBe(true);
  return (await response.json()) as { id: number };
}

export async function createDocument(
  page: Page,
  input: { collection?: string; documentKey: string; title: string; content: string; sensitive: boolean },
) {
  const response = await page.request.post("/api/memory-documents", {
    data: { collection: "career", ...input },
  });
  expect(response.ok()).toBe(true);
  return (await response.json()) as { id: number; revision: number };
}

/** 제목이 같은 기억과 문서를 지운다. 뒤 검사의 목록에 남지 않게 한다. */
export async function cleanupMemories(page: Page, titles: string[]) {
  const memories = (await (await page.request.get("/api/memories")).json()) as { id: number; title: string }[];
  const documents = (await (await page.request.get("/api/memory-documents")).json()) as { id: number; title: string }[];
  for (const row of [...memories, ...documents]) {
    if (titles.includes(row.title)) await page.request.delete(`/api/memories/${row.id}`);
  }
}

/** 기존 기억을 저장해 화면의 실제 목록 다시 읽기를 실행한다. */
export async function reloadMemoryListBySaving(page: Page, title: string) {
  await openRow(page, title);
  const row = memoryRow(page, title);
  await row.getByRole("button", { name: "고치기" }).click();
  const reloaded = page.waitForResponse(
    (response) => response.request().method() === "GET" && new URL(response.url()).pathname === "/api/memories",
  );
  await row.getByRole("button", { name: "저장" }).click();
  await reloaded;
}

export async function openDocumentTab(page: Page) {
  await page.getByRole("tab", { name: "문서" }).click();
}

/** 기억 화면을 열고 아래에 접어 둔 「외부 서비스 연결」 을 펼친다. */
export async function openMemoryAdvanced(page: Page) {
  await page.goto("/memory");
  await page.getByText("외부 서비스 연결").click();
}

export async function expectNoHorizontalOverflow(page: Page) {
  const viewportWidth = page.viewportSize()?.width;
  expect(viewportWidth).toBeDefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth!);
}

import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import { expect, test } from "./fixtures.ts";

/** 검사가 만든 문서를 화면에서 지운다. 뒤 검사의 목록에 남지 않게 한다. */
async function removeDocument(page: Page, title: string) {
  await page.goto("/memory");
  const item = documentItem(page, title);
  if ((await item.count()) === 0) return;
  await item.getByRole("button", { name: "지우기" }).click();
  await expect(item).toHaveCount(0);
}

function documentItem(page: Page, title: string) {
  return page.getByRole("heading", { name: title, exact: true }).locator("xpath=ancestor::article");
}

async function createDocument(page: Page, key: string, title: string, content: string, sensitive: boolean) {
  const form = page.locator("form").filter({ has: page.getByRole("heading", { name: "새 문서" }) });
  await form.getByLabel("영역").selectOption({ label: "신원" });
  await form.getByLabel("문서 이름").fill(key);
  await form.getByLabel("제목", { exact: true }).fill(title);
  await form.getByLabel("내용", { exact: true }).fill(content);
  if (sensitive) await form.getByLabel("민감한 내용이에요. 암호화해서 저장해요").check();
  await form.getByRole("button", { name: "문서 저장" }).click();
}

test("민감 문서를 만들고 열고 고치고 지운다", async ({ page }, testInfo) => {
  const key = `profile-${testInfo.project.name}`;
  const title = `지원서 공통 프로필 ${testInfo.project.name}`;
  await page.goto("/memory");
  await expect(page.getByRole("button", { name: "문서 저장" })).toBeDisabled();
  await createDocument(page, key, title, "평문-표식-7391", true);

  const item = documentItem(page, title);
  await expect(item).toBeVisible();
  await expect(item.getByText("민감", { exact: true })).toBeVisible();
  await expect(item.getByText("1번째 판")).toBeVisible();
  await expect(page.getByText("평문-표식-7391")).toHaveCount(0);

  await item.getByRole("button", { name: "열기" }).click();
  await expect(item.getByText("평문-표식-7391")).toBeVisible();

  await item.getByRole("button", { name: "고치기" }).click();
  await item.getByRole("textbox").fill("평문-표식-8802");
  await item.getByRole("button", { name: "저장" }).click();
  await expect(item.getByText("2번째 판")).toBeVisible();

  await page.reload();
  await expect(documentItem(page, title)).toBeVisible();
  await expect(page.getByText("평문-표식-8802")).toHaveCount(0);

  await documentItem(page, title).getByRole("button", { name: "지우기" }).click();
  await expect(page.getByRole("heading", { name: title, exact: true })).toHaveCount(0);
});

test("같은 이름의 문서를 두 번 만들지 못한다", async ({ page }, testInfo) => {
  const key = `dup-${testInfo.project.name}`;
  const title = `중복 검사 ${testInfo.project.name}`;
  await page.goto("/memory");
  await createDocument(page, key, title, "평문-표식-1100", false);
  await expect(documentItem(page, title)).toBeVisible();
  await createDocument(page, key, `${title} 둘째`, "평문-표식-1101", false);
  await expect(page.getByText("같은 이름의 문서가 이미 있어요. 다른 이름을 입력해 주세요.")).toBeVisible();
  await removeDocument(page, title);
});

test("그사이 바뀐 문서는 다시 열게 하고 쓰던 글을 남긴다", async ({ page }, testInfo) => {
  const key = `race-${testInfo.project.name}`;
  const title = `경합 검사 ${testInfo.project.name}`;
  await page.goto("/memory");
  await createDocument(page, key, title, "평문-표식-2200", false);
  const item = documentItem(page, title);
  await item.getByRole("button", { name: "열기" }).click();
  await expect(item.getByText("평문-표식-2200")).toBeVisible();

  const documents = (await (await page.request.get("/api/memory-documents")).json()) as { id: number; documentKey: string }[];
  const id = documents.find((document) => document.documentKey === key)?.id;
  expect(id).toBeDefined();
  const bumped = await page.request.put(`/api/memory-documents/${id}`, {
    data: { content: "평문-표식-2201", sensitive: false, expectedRevision: 1 },
  });
  expect(bumped.ok()).toBe(true);

  await item.getByRole("button", { name: "고치기" }).click();
  await item.getByRole("textbox").fill("평문-표식-2202");
  await item.getByRole("button", { name: "저장" }).click();
  await expect(item.getByText("그사이 문서가 바뀌었어요. 문서를 다시 열어 주세요.")).toBeVisible();
  await expect(item.getByText("쓰던 글은 아래 입력 칸에 그대로 있어요.", { exact: false })).toBeVisible();
  await expect(item.getByRole("textbox")).toHaveValue("평문-표식-2202");

  // 안내대로 취소하고 닫았다 다시 열면 새 판을 읽어 이어서 고칠 수 있다.
  await item.getByRole("button", { name: "취소" }).click();
  await item.getByRole("button", { name: "닫기" }).click();
  await item.getByRole("button", { name: "열기" }).click();
  await expect(item.getByText("평문-표식-2201")).toBeVisible();
  await item.getByRole("button", { name: "고치기" }).click();
  await item.getByRole("textbox").fill("평문-표식-2203");
  await item.getByRole("button", { name: "저장" }).click();
  await expect(item.getByText("3번째 판")).toBeVisible();
  await expect(item.getByText("평문-표식-2203")).toBeVisible();
  await removeDocument(page, title);
});

test("다른 곳에서 고친 문서는 목록을 다시 읽어도 연 본문의 판으로 보내 덮어쓰지 않는다", async ({ page }, testInfo) => {
  const key = `stale-${testInfo.project.name}`;
  const title = `오래된 본문 검사 ${testInfo.project.name}`;
  const other = `stale-other-${testInfo.project.name}`;
  const otherTitle = `다른 문서 ${testInfo.project.name}`;
  await page.goto("/memory");
  await createDocument(page, key, title, "평문-표식-5500", false);
  const item = documentItem(page, title);
  await item.getByRole("button", { name: "열기" }).click();
  await expect(item.getByText("평문-표식-5500")).toBeVisible();

  const documents = (await (await page.request.get("/api/memory-documents")).json()) as { id: number; documentKey: string }[];
  const id = documents.find((document) => document.documentKey === key)?.id;
  expect((await page.request.put(`/api/memory-documents/${id}`, { data: { content: "평문-표식-5501", sensitive: false, expectedRevision: 1 } })).ok()).toBe(true);

  // 다른 문서를 만들어 이 탭이 목록을 다시 읽게 한다. 목록의 판은 2 가 되지만 열어 둔 본문은 1 판이다.
  await createDocument(page, other, otherTitle, "평문-표식-5502", false);
  await expect(item.getByText("2번째 판")).toBeVisible();
  await item.getByRole("button", { name: "고치기" }).click();
  await item.getByRole("textbox").fill("평문-표식-5503");
  await item.getByRole("button", { name: "저장" }).click();
  await expect(item.getByText("그사이 문서가 바뀌었어요. 문서를 다시 열어 주세요.")).toBeVisible();
  await removeDocument(page, otherTitle);
  await removeDocument(page, title);
});

test("문서는 기억 목록에 섞이지 않고 좁은 화면에서 넘치지 않는다", async ({ page }, testInfo) => {
  const key = `mix-${testInfo.project.name}`;
  const title = `섞임 검사 ${testInfo.project.name}`;
  await page.goto("/memory");
  await createDocument(page, key, title, "평문-표식-3300 ".repeat(40), false);
  const item = documentItem(page, title);
  await expect(item).toBeVisible();
  await item.getByRole("button", { name: "열기" }).click();

  const personal = page.getByRole("heading", { name: "나에 대해 아는 것" }).locator("xpath=ancestor::section");
  await expect(personal.getByText(title)).toHaveCount(0);

  const viewportWidth = page.viewportSize()?.width;
  expect(viewportWidth).toBeDefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewportWidth!);
  await removeDocument(page, title);
});

test("문서 목록 응답은 본문을 싣지 않는다", async ({ page }, testInfo) => {
  const key = `list-${testInfo.project.name}`;
  const title = `목록 검사 ${testInfo.project.name}`;
  await page.goto("/memory");
  await createDocument(page, key, title, "평문-표식-4400", true);
  await expect(documentItem(page, title)).toBeVisible();
  const listed = await (await page.request.get("/api/memory-documents")).text();
  expect(listed).toContain(key);
  expect(listed).not.toContain("평문-표식-4400");
  expect(listed).not.toContain('"content"');
  await removeDocument(page, title);
});

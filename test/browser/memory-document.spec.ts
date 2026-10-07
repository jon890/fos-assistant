import { expect, test } from "./fixtures.ts";
import {
  cleanupMemories,
  confirmDelete,
  createDocument,
  expectNoHorizontalOverflow,
  memoryRow,
  openDocumentTab,
  openRow,
} from "./memory-page.ts";

test("문서 탭에서 민감 문서를 눌러 열고 고치고 지운다", async ({ page }, testInfo) => {
  const key = `profile-${testInfo.project.name}`;
  const title = `지원서 공통 프로필 ${testInfo.project.name}`;
  try {
    await createDocument(page, { collection: "identity", documentKey: key, title, content: "평문-표식-7391", sensitive: true });
    await page.goto("/memory");
    // 기억 탭에는 문서가 섞이지 않는다.
    await expect(memoryRow(page, title)).toHaveCount(0);
    await openDocumentTab(page);

    const row = memoryRow(page, title);
    await expect(row).toBeVisible();
    await expect(row.getByText("민감", { exact: true })).toBeVisible();
    await expect(row.getByText("신원")).toBeVisible();
    await expect(page.getByText("평문-표식-7391")).toHaveCount(0);

    await openRow(page, title);
    await expect(row.getByText("평문-표식-7391")).toBeVisible();
    await expect(row.getByText("1번째 판")).toBeVisible();

    await row.getByRole("button", { name: "고치기" }).click();
    await row.getByRole("textbox").fill("평문-표식-8802");
    await row.getByRole("button", { name: "저장" }).click();
    await expect(row.getByText("2번째 판")).toBeVisible();

    // 접으면 받은 본문을 버린다.
    await row.getByRole("button", { name: title }).click();
    await expect(page.getByText("평문-표식-8802")).toHaveCount(0);

    await page.reload();
    await openDocumentTab(page);
    await openRow(page, title);
    await memoryRow(page, title).getByRole("button", { name: "지우기" }).click();
    await expect(page.getByText("이 문서를 지울까요?")).toBeVisible();
    await page.getByRole("alertdialog").getByRole("button", { name: "취소" }).click();
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await confirmDelete(page, title);
    await expect(memoryRow(page, title)).toHaveCount(0);
  } finally {
    await cleanupMemories(page, [title]);
  }
});

test("그사이 바뀐 문서는 다시 열게 하고 쓰던 글을 남긴다", async ({ page }, testInfo) => {
  const key = `race-${testInfo.project.name}`;
  const title = `경합 검사 ${testInfo.project.name}`;
  try {
    const { id } = await createDocument(page, { documentKey: key, title, content: "평문-표식-2200", sensitive: false });
    await page.goto("/memory");
    await openDocumentTab(page);
    const row = memoryRow(page, title);
    await openRow(page, title);
    await expect(row.getByText("평문-표식-2200")).toBeVisible();

    const bumped = await page.request.put(`/api/memory-documents/${id}`, {
      data: { content: "평문-표식-2201", sensitive: false, expectedRevision: 1 },
    });
    expect(bumped.ok()).toBe(true);

    await row.getByRole("button", { name: "고치기" }).click();
    await row.getByRole("textbox").fill("평문-표식-2202");
    await row.getByRole("button", { name: "저장" }).click();
    await expect(row.getByText("그사이 문서가 바뀌었어요. 문서를 다시 열어 주세요.")).toBeVisible();
    await expect(row.getByText("쓰던 글은 아래 입력 칸에 그대로 있어요.", { exact: false })).toBeVisible();
    await expect(row.getByRole("textbox")).toHaveValue("평문-표식-2202");

    // 안내대로 취소하고 접었다 다시 펼치면 새 판을 읽어 이어서 고칠 수 있다.
    await row.getByRole("button", { name: "취소" }).click();
    await row.getByRole("button", { name: title }).click();
    await openRow(page, title);
    await expect(row.getByText("평문-표식-2201")).toBeVisible();
    await row.getByRole("button", { name: "고치기" }).click();
    await row.getByRole("textbox").fill("평문-표식-2203");
    await row.getByRole("button", { name: "저장" }).click();
    await expect(row.getByText("3번째 판")).toBeVisible();
    await expect(row.getByText("평문-표식-2203")).toBeVisible();
  } finally {
    await cleanupMemories(page, [title]);
  }
});

test("긴 문서를 펼쳐도 좁은 화면에서 넘치지 않는다", async ({ page }, testInfo) => {
  const key = `wide-${testInfo.project.name}`;
  const title = `넘침 검사 ${testInfo.project.name} ${"아주 긴 제목 ".repeat(6)}`.trim();
  try {
    await createDocument(page, { documentKey: key, title, content: "평문-표식-3300 ".repeat(40), sensitive: false });
    await page.goto("/memory");
    await openDocumentTab(page);
    await openRow(page, title);
    await expect(memoryRow(page, title).getByText("평문-표식-3300", { exact: false })).toBeVisible();
    await expectNoHorizontalOverflow(page);
  } finally {
    await cleanupMemories(page, [title]);
  }
});

test("문서 목록 응답은 본문을 싣지 않는다", async ({ page }, testInfo) => {
  const key = `list-${testInfo.project.name}`;
  const title = `목록 검사 ${testInfo.project.name}`;
  try {
    await createDocument(page, { documentKey: key, title, content: "평문-표식-4400", sensitive: true });
    const listed = await (await page.request.get("/api/memory-documents")).text();
    expect(listed).toContain(key);
    expect(listed).not.toContain("평문-표식-4400");
    expect(listed).not.toContain('"content"');
  } finally {
    await cleanupMemories(page, [title]);
  }
});

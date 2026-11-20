import { expect, test } from "./fixtures.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

// 1x1 PNG 다. 깨진 그림이 아닌지 naturalWidth 로 본다.
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);
const PROMPT = "창가에 앉은 고양이를 수채화로, 부드러운 오후 햇살, 배경은 흐리게";

type Artifact = {
  path: string;
  byteSize: number;
  deleted: boolean;
  autoVault: string | null;
  autoVaultError: string | null;
  kind: "IMAGE" | "HTML";
  prompt: string | null;
  vaultItemId: number | null;
  sourceLinks?: string[];
};

function image(title: string, extra: Partial<Artifact> = {}): Artifact {
  return {
    path: `generated/${title}.png`,
    byteSize: PNG.length,
    deleted: false,
    autoVault: null,
    autoVaultError: null,
    kind: "IMAGE",
    prompt: PROMPT,
    vaultItemId: null,
    ...extra,
  };
}

/**
 * 실제 대화 하나를 만들고, 그 답에 그림 결과물이 묶인 것처럼 메시지 응답과 파일 응답을 바꾼다.
 *
 * <p>그림을 Control Plane 에 올리는 일은 profile 플러그인이 하고 `test/e2e` 의 그림 시나리오가 본다. 여기서는 화면만 본다.
 */
async function openWithImages(page: Page, artifacts: Artifact[], content = "그림을 만들었어요."): Promise<string> {
  const created = await page.request.post("/api/chat", { data: { text: `그림 검사 ${Date.now()}`, agentCode: "browser" } });
  expect(created.ok(), `대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };
  await page.route(`**/api/chat/conversations/${conversationId}/messages`, async (route) => {
    const response = await route.fetch();
    const messages = (await response.json()) as { role: string; content: string; artifacts: Artifact[] }[];
    const answer = messages.findLast((message) => message.role === "ASSISTANT");
    if (answer) {
      answer.artifacts = artifacts;
      answer.content = content;
    }
    await route.fulfill({ response, json: messages });
  });
  await page.route(`**/api/chat/conversations/${conversationId}/files/generated/**`, (route) =>
    route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
  await page.goto(`/chat/${conversationId}`);
  return conversationId;
}

test("답 안에 그림 두 장이 만든 순서로 보이고 지시문은 눌러야 펼쳐진다", async ({ page }) => {
  await openWithImages(page, [image("수채화 고양이"), image("수채화 강아지")]);

  const images = page.getByTestId("assistant-message").last().getByTestId("generated-image");
  await expect(images).toHaveCount(2);
  await expect(images.nth(0).getByRole("img")).toHaveAttribute("alt", "수채화 고양이");
  await expect(images.nth(1).getByRole("img")).toHaveAttribute("alt", "수채화 강아지");
  await expect.poll(() => images.nth(0).getByRole("img").evaluate((img) => (img as HTMLImageElement).naturalWidth))
    .toBeGreaterThan(0);
  // 그림은 파일 줄로 보이지 않는다.
  await expect(page.getByTestId("assistant-message").last().getByTestId("message-artifact")).toHaveCount(0);

  const prompt = images.nth(0).getByTestId("generated-image-prompt");
  await expect(prompt.getByText(PROMPT)).toBeHidden();
  await prompt.getByText("그림 지시문 보기").click();
  await expect(prompt.getByText(PROMPT)).toBeVisible();
});

for (const width of [1280, 390]) {
  test(`참고한 링크가 지시문 칸에 새 창 링크로 보이고 링크 없는 그림에는 없다 (폭 ${width})`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const links = ["https://shop.example.com/goods/1", "https://img.example.com/p.png"];
    await openWithImages(page, [image("양말 신은 아이", { sourceLinks: links }), image("그냥 고양이", { sourceLinks: [] })]);

    const images = page.getByTestId("assistant-message").last().getByTestId("generated-image");
    await images.nth(0).getByTestId("generated-image-prompt").getByText("그림 지시문 보기").click();
    const list = images.nth(0).getByTestId("generated-image-source-links");
    await expect(list).toBeVisible();
    const anchors = list.getByRole("link");
    await expect(anchors).toHaveCount(2);
    for (const [index, link] of links.entries()) {
      await expect(anchors.nth(index)).toHaveText(link);
      await expect(anchors.nth(index)).toHaveAttribute("href", link);
      await expect(anchors.nth(index)).toHaveAttribute("target", "_blank");
      await expect(anchors.nth(index)).toHaveAttribute("rel", "noopener noreferrer");
    }
    await images.nth(1).getByTestId("generated-image-prompt").getByText("그림 지시문 보기").click();
    await expect(images.nth(1).getByTestId("generated-image-source-links")).toHaveCount(0);
  });
}

test("그림을 누르면 크게 보이고 내려받는 파일 이름이 제목이며 보관본이 없으면 보관할 수 있다", async ({ page }) => {
  const conversationId = await openWithImages(page, [image("수채화 고양이")]);
  let kept: unknown;
  await page.route("**/api/artifact-vault", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    kept = route.request().postDataJSON();
    await route.fulfill({ status: 200, json: { id: 7, path: "generated/수채화 고양이.png", byteSize: PNG.length, fileCount: 1, vaultedAt: new Date().toISOString() } });
  });

  await page.getByRole("button", { name: "수채화 고양이 크게 보기" }).click();
  const viewer = page.getByTestId("generated-image-viewer");
  await expect(viewer.getByRole("heading", { name: "수채화 고양이" })).toBeVisible();
  await expect(viewer.getByRole("img", { name: "수채화 고양이" })).toBeVisible();
  const download = viewer.getByTestId("generated-image-download");
  await expect(download).toHaveAttribute("download", "수채화 고양이.png");
  await expect(download).toHaveAttribute("href", new RegExp(`/api/chat/conversations/${conversationId}/files/generated/`));
  // 크게 보기에서도 지시문을 펼쳐 볼 수 있다.
  await viewer.getByText("그림 지시문 보기").click();
  await expect(viewer.getByText(PROMPT)).toBeVisible();

  await viewer.getByRole("button", { name: "보관" }).click();
  await expect(viewer.getByTestId("generated-image-kept")).toBeVisible();
  expect(kept).toEqual({ conversationId, path: "generated/수채화 고양이.png" });

  await viewer.getByRole("button", { name: "크게 보기 닫기" }).click();
  await expect(viewer).toBeHidden();
  // 다시 열어도 보관한 그림에는 「보관」 단추가 보이지 않는다.
  await page.getByRole("button", { name: "수채화 고양이 크게 보기" }).click();
  await expect(viewer.getByTestId("generated-image-kept")).toBeVisible();
  await expect(viewer.getByRole("button", { name: "보관" })).toHaveCount(0);
});

test("보관한 그림은 보관 상태가 보이고 크게 보기에 보관 단추 대신 내 파일 링크가 있다", async ({ page }) => {
  await openWithImages(page, [image("수채화 고양이", { autoVault: "DONE", vaultItemId: 12 })]);

  await expect(page.getByTestId("generated-image").getByTestId("artifact-auto-vault")).toHaveText("보관 완료");
  await page.getByRole("button", { name: "수채화 고양이 크게 보기" }).click();
  const viewer = page.getByTestId("generated-image-viewer");
  await expect(viewer.getByRole("link", { name: "내 파일에서 보기" })).toHaveAttribute("href", "/files?item=12");
  await expect(viewer.getByRole("button", { name: "보관" })).toHaveCount(0);
});

test("보관 기간이 지난 그림은 안내와 내 파일 링크가 보이고 지시문은 남으며 답에 남은 경로는 보이지 않는다", async ({ page }) => {
  await openWithImages(
    page,
    [image("오래된 그림", { deleted: true, vaultItemId: 12 }), image("지운 그림", { deleted: true })],
    "사진을 만들었습니다.\n\nMEDIA:/srv/x/.hermes/profiles/p/cache/generated/images/a.png",
  );

  const answer = page.getByTestId("assistant-message").last();
  const images = answer.getByTestId("generated-image");
  await expect(images.nth(0)).toContainText("보관 기간이 지나 대화에서는 볼 수 없어요.");
  await expect(images.nth(0).getByRole("link", { name: "내 파일에서 보기" })).toHaveAttribute("href", "/files?item=12");
  await expect(images.nth(0).getByRole("img")).toHaveCount(0);
  await expect(images.nth(1).getByRole("link", { name: "내 파일에서 보기" })).toHaveCount(0);
  await images.nth(0).getByText("그림 지시문 보기").click();
  await expect(images.nth(0).getByText(PROMPT)).toBeVisible();

  await expect(answer.getByTestId("assistant-body")).toContainText("사진을 만들었습니다.");
  await expect(answer.getByTestId("assistant-body")).not.toContainText("MEDIA:");
  await expect(answer.getByTestId("assistant-body")).not.toContainText("/cache/generated/images/");
});

test("내 파일의 그림 줄은 제목과 작은 그림을 보이고 주소가 가리킨 그림의 미리보기가 열린다", async ({ page }) => {
  const items = [
    { id: 12, path: "generated/수채화 고양이.png", byteSize: PNG.length, fileCount: 1, vaultedAt: "2026-11-19T05:30:00Z", kind: "IMAGE" },
    { id: 13, path: "초안/index.html", byteSize: 120, fileCount: 1, vaultedAt: "2026-11-19T05:31:00Z", kind: "DOCUMENT" },
  ];
  await page.route((url) => url.pathname === "/api/artifact-vault", (route) =>
    route.fulfill({ status: 200, json: { items, nextCursor: null, usedBytes: 0, quotaBytes: 5 * 1024 ** 3, full: false } }));
  await page.route("**/api/artifact-vault/12", (route) => route.fulfill({ status: 200, json: items[0] }));
  await page.route("**/api/artifact-vault/12/content*", (route) =>
    route.fulfill({ status: 200, contentType: "image/png", body: PNG }));

  await page.goto("/files?tab=vault&item=12");
  const rows = page.getByTestId("my-file");
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(0)).toContainText("수채화 고양이");
  await expect(rows.nth(0)).not.toContainText("수채화 고양이.png");
  await expect(rows.nth(0).getByTestId("my-file-thumbnail")).toBeVisible();
  await expect(rows.nth(1).getByTestId("my-file-thumbnail")).toHaveCount(0);
  // 주소의 item 이 가리킨 그림은 미리보기가 열린 채 열린다.
  const image = page.getByTestId("my-file-preview").getByTestId("my-file-image");
  await expect(image).toBeVisible();
  await expect.poll(() => image.evaluate((img) => (img as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
});

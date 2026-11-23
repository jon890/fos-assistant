import { expect, test } from "./fixtures.ts";
import { restorePrimaries, seedConversation } from "./helpers.ts";

/**
 * 에이전트가 기본 비서가 아닌 지난 대화는 읽기만 한다(docs/features/assistant-hub/spec.md 의 REQ5).
 *
 * <p>그런 대화는 이제 화면으로 만들 수 없다. test-support 대화 만들기가 그 에이전트를 잠시 기본 비서로 두는 동안 한 번 묻고,
 * 기본 비서를 되돌려 지난 대화로 만든다. 검사 사용자의 기본 비서는 공통 fixture 가 둔 「browser」 다.
 */
test("지난 전문 에이전트 대화는 입력창 대신 안내와 대화방 링크를 보이고 보내기를 거절한다", async ({
  page,
}) => {
  const created = await page.request.post("/api/agents", {
    data: { name: "읽기 검사 쇼핑" },
  });
  expect(created.status()).toBe(201);
  const { code } = (await created.json()) as { code: string };
  try {
    const conversationId = await seedConversation(code);
    const asked = await page.request.post("/api/chat", {
      data: { conversationId, text: "운동화 찾아줘" },
    });
    expect(asked.ok()).toBeTruthy();
    await restorePrimaries();

    await page.goto(`/chat/${conversationId}`);
    await expect(page.getByTestId("assistant-message")).toHaveCount(1);
    const notice = page.getByTestId("read-only-notice");
    await expect(notice).toContainText(
      "이 대화는 더 이어 쓸 수 없어요. 비서에게 이어서 물어보세요",
    );
    await expect(notice.getByRole("link", { name: "대화방으로 가기" })).toHaveAttribute(
      "href",
      "/",
    );
    await expect(page.getByRole("textbox", { name: "메시지" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "답 다시 만들기" })).toHaveCount(0);

    const rejected = await page.request.post("/api/chat", {
      data: { conversationId, text: "하나 더" },
    });
    expect(rejected.status()).toBe(400);
    expect(((await rejected.json()) as { code: string }).code).toBe(
      "VALIDATION_FAILED",
    );

    await notice.getByRole("link", { name: "대화방으로 가기" }).click();
    await expect(page.getByTestId("read-only-notice")).toHaveCount(0);
    await expect(page.getByRole("textbox", { name: "메시지" })).toBeVisible();
  } finally {
    await restorePrimaries();
    const removed = await page.request.delete(`/api/agents/${code}`);
    expect(removed.status()).toBe(204);
  }
});

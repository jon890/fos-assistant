import { expect, setSession, test } from "./fixtures.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

/**
 * 대화 중 `@` 로 전문 에이전트를 골라 바로 맡기는 흐름을 본다(docs/features/agent-team/spec.md 의 REQ7).
 *
 * <p>가짜 Hermes 가 맡은 에이전트의 run 에도 답하므로 이력과 사건을 대역하지 않는다. 사용자는 검사마다 나누고 만든 에이전트는 지운다.
 */

async function createAgent(page: Page, name: string): Promise<string> {
  const created = await page.request.post("/api/agents", { data: { name } });
  expect(created.status()).toBe(201);
  return ((await created.json()) as { code: string }).code;
}

test("메시지가 있는 대화에서 `@` 로 고른 에이전트에게 맡기면 맡긴 줄과 그 에이전트 이름표의 답이 보이고 새로 고쳐도 같다", async ({
  page,
  context,
  isolatedMember,
}) => {
  await setSession(context, isolatedMember);
  const chief = await createAgent(page, "맡김 검사 비서");
  const shopper = await createAgent(page, "쇼핑 담당");
  try {
    const started = await page.request.post("/api/chat", {
      data: { text: "다음 주에 등산 가", agentCode: chief },
    });
    expect(started.ok()).toBeTruthy();
    const { conversationId } = (await started.json()) as {
      conversationId: string;
    };
    await page.goto(`/chat/${conversationId}`);
    await expect(page.getByTestId("assistant-message")).toHaveCount(1);

    const composer = page.getByRole("textbox", { name: "메시지" });
    await composer.fill("@쇼");
    const list = page.getByRole("listbox", { name: "에이전트 고르기" });
    // 줄의 글에는 이름표의 머리글자가 섞이므로 접근성 이름으로 본다.
    await expect(list.getByRole("option")).toHaveCount(1);
    await expect(list.getByRole("option")).toHaveAccessibleName("쇼핑 담당");
    await list.getByRole("option", { name: "쇼핑 담당" }).click();
    await expect(page.getByTestId("delegate-chip")).toContainText(
      "쇼핑 담당에게 맡기기",
    );
    await expect(composer).toHaveValue("");

    await composer.fill("운동화 찾아줘");
    await composer.press("Enter");

    const line = page.getByTestId("mention-line");
    await expect(line).toHaveCount(1);
    await expect(line).toContainText("나 → 쇼핑 담당에게 맡김");
    await expect(line.getByTestId("mention-status")).toHaveText("끝남");
    await expect(page.getByTestId("delegate-chip")).toHaveCount(0);
    const answers = page.getByTestId("assistant-message");
    await expect(answers).toHaveCount(2);
    await expect(answers.last().getByTestId("answer-agent-name")).toHaveText(
      "쇼핑 담당",
    );
    await expect(answers.first().getByTestId("answer-agent-name")).toHaveText(
      "맡김 검사 비서",
    );
    await expect(
      answers.last().getByRole("button", { name: "답 다시 만들기" }),
    ).toHaveCount(0);

    await page.reload();
    await expect(page.getByTestId("mention-line")).toContainText(
      "나 → 쇼핑 담당에게 맡김",
    );
    await expect(
      page.getByTestId("mention-line").getByTestId("mention-status"),
    ).toHaveText("끝남");
    await expect(
      page.getByTestId("assistant-message").last().getByTestId("answer-agent-name"),
    ).toHaveText("쇼핑 담당");
  } finally {
    for (const code of [shopper, chief]) {
      const removed = await page.request.delete(`/api/agents/${code}`);
      expect(removed.status()).toBe(204);
    }
  }
});

test("`@` 목록에는 대화의 에이전트가 없다", async ({
  page,
  context,
  isolatedMember,
}) => {
  await setSession(context, isolatedMember);
  const chief = await createAgent(page, "목록 검사 비서");
  const shopper = await createAgent(page, "목록 검사 쇼핑");
  try {
    const started = await page.request.post("/api/chat", {
      data: { text: "안녕", agentCode: chief },
    });
    const { conversationId } = (await started.json()) as {
      conversationId: string;
    };
    await page.goto(`/chat/${conversationId}`);
    await expect(page.getByTestId("assistant-message")).toHaveCount(1);

    await page.getByRole("textbox", { name: "메시지" }).fill("@목록");
    const options = page
      .getByRole("listbox", { name: "에이전트 고르기" })
      .getByRole("option");
    await expect(options).toHaveCount(1);
    await expect(options).toHaveAccessibleName("목록 검사 쇼핑");
  } finally {
    for (const code of [shopper, chief]) {
      const removed = await page.request.delete(`/api/agents/${code}`);
      expect(removed.status()).toBe(204);
    }
  }
});

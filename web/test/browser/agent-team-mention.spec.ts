import { expect, setSession, test } from "./fixtures.ts";
import { clearPrimary, relabelExecution, usePrimary } from "./helpers.ts";
import type { Page } from "../../node_modules/@playwright/test/index.js";

/**
 * `@` 맡기기를 없앤 뒤의 대화를 본다(docs/features/assistant-hub/spec.md 의 REQ6).
 *
 * <p>입력창의 `@` 는 목록을 띄우지 않고, 그 전에 남은 맡김은 맡긴 줄과 맡은 에이전트 이름표의 답으로 지금처럼 보인다. 지난 맡김은
 * 이제 화면으로 만들 수 없어, 대화의 실행 하나를 test-support 경로로 다른 에이전트의 것으로 바꿔 흉내 낸다. 사용자는 검사마다
 * 나누고 만든 에이전트는 지운다.
 */

async function createAgent(page: Page, name: string): Promise<string> {
  const created = await page.request.post("/api/agents", { data: { name } });
  expect(created.status()).toBe(201);
  return ((await created.json()) as { code: string }).code;
}

test("입력창에 `@` 를 쳐도 에이전트를 고르는 목록과 맡기기 칩이 뜨지 않고 글로 보낸다", async ({
  page,
  context,
  isolatedMember,
}) => {
  await setSession(context, isolatedMember);
  const chief = await createAgent(page, "목록 검사 비서");
  const shopper = await createAgent(page, "목록 검사 쇼핑");
  await usePrimary(isolatedMember.email, chief);
  try {
    const started = await page.request.post("/api/chat", {
      data: { text: "안녕", agentCode: chief },
    });
    const { conversationId } = (await started.json()) as {
      conversationId: string;
    };
    await page.goto(`/chat/${conversationId}`);
    await expect(page.getByTestId("assistant-message")).toHaveCount(1);

    const composer = page.getByRole("textbox", { name: "메시지" });
    await composer.fill("@목록 검사 쇼핑 운동화 찾아줘");
    await expect(page.getByRole("listbox", { name: "에이전트 고르기" })).toHaveCount(0);
    await composer.press("Enter");

    await expect(page.getByTestId("assistant-message")).toHaveCount(2);
    await expect(page.getByTestId("mention-line")).toHaveCount(0);
    await expect(page.getByTestId("delegate-chip")).toHaveCount(0);
    await expect(
      page.getByTestId("assistant-message").last().getByTestId("answer-agent-name"),
    ).toHaveText("목록 검사 비서");
  } finally {
    await clearPrimary(isolatedMember.email);
    for (const code of [shopper, chief]) {
      const removed = await page.request.delete(`/api/agents/${code}`);
      expect(removed.status()).toBe(204);
    }
  }
});

test("지난 `@` 맡김은 맡긴 줄과 그 에이전트 이름표의 답으로 보이고 다시 만들지 못한다", async ({
  page,
  context,
  isolatedMember,
}) => {
  await setSession(context, isolatedMember);
  const chief = await createAgent(page, "맡김 검사 비서");
  const shopper = await createAgent(page, "쇼핑 담당");
  await usePrimary(isolatedMember.email, chief);
  try {
    const started = await page.request.post("/api/chat", {
      data: { text: "다음 주에 등산 가", agentCode: chief },
    });
    expect(started.ok()).toBeTruthy();
    const { conversationId } = (await started.json()) as {
      conversationId: string;
    };
    const mentioned = await page.request.post("/api/chat", {
      data: { conversationId, text: "운동화 찾아줘" },
    });
    expect(mentioned.ok()).toBeTruthy();
    const { executionId } = (await mentioned.json()) as { executionId: number };
    await relabelExecution(isolatedMember.email, executionId, shopper);

    await page.goto(`/chat/${conversationId}`);
    const line = page.getByTestId("mention-line");
    await expect(line).toHaveCount(1);
    await expect(line).toContainText("나 → 쇼핑 담당에게 맡김");
    await expect(line.getByTestId("mention-status")).toHaveText("끝남");
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
  } finally {
    await clearPrimary(isolatedMember.email);
    for (const code of [shopper, chief]) {
      const removed = await page.request.delete(`/api/agents/${code}`);
      expect(removed.status()).toBe(204);
    }
  }
});

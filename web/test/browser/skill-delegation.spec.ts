import type { Page } from "../../node_modules/@playwright/test/index.js";
import {
  connectDemoConnector,
  disconnectDemoConnector,
  expect,
  setSession,
  test,
} from "./fixtures.ts";
import { clearPrimary, usePrimary } from "./helpers.ts";
import { DEMO_CONNECTOR } from "../../../test/e2e/fake-hermes.ts";

/**
 * 비서 대화의 `/` 목록에서 전문 에이전트의 스킬을 맡긴다(docs/features/assistant-hub/spec.md 의 REQ10, DECISION26).
 *
 * <p>검사마다 사용자를 나누고 비서와 전문 에이전트를 만들어 끝나면 지운다. 가짜 Hermes 는 맡긴 실행을 바로 끝내므로 결과 답까지 본다.
 */
const SKILL = "position-recommender";

async function createAgent(page: Page, name: string): Promise<string> {
  const created = await page.request.post("/api/agents", { data: { name } });
  expect(created.status()).toBe(201);
  return ((await created.json()) as { code: string }).code;
}

async function putSkill(page: Page, code: string, name: string) {
  const response = await page.request.put(`/api/agents/${code}/skills/${name}`, {
    data: {
      skillMd: `---\nname: ${name}\ndescription: 공고를 골라요\n---\n# 공고 고르기\n`,
      files: [],
    },
  });
  expect(response.ok(), `스킬을 올리지 못했다: ${await response.text()}`).toBeTruthy();
}

test("/ 목록에 「에이전트 · 스킬」 이 보이고, 골라 보내면 입력창이 비고 질문 아래 맡김 줄과 결과 답이 생긴다", async ({
  page,
  context,
  isolatedMember,
}) => {
  await setSession(context, isolatedMember);
  const chief = await createAgent(page, "맡기기 검사 비서");
  const career = await createAgent(page, "커리어 검사");
  await usePrimary(isolatedMember.email, chief);
  try {
    await putSkill(page, career, SKILL);
    await page.goto("/new");
    const input = page.getByRole("textbox", { name: "메시지" });

    await input.fill("/커리");
    const option = page
      .getByRole("listbox", { name: "스킬 고르기" })
      .getByRole("option", { name: `/커리어 검사 · ${SKILL}` });
    await expect(option).toBeVisible();
    await input.press("Enter");
    await expect(input).toHaveValue(`/커리어 검사 · ${SKILL} `);
    await input.pressSequentially("이번 주 공고");
    await input.press("Enter");

    await expect(input).toHaveValue("");
    await expect(page).toHaveURL(/\/chat\/[0-9a-f-]{36}$/);
    const line = page.getByTestId("mention-line");
    await expect(line).toHaveCount(1);
    await expect(line).toContainText("나 → 커리어 검사에게 맡김");
    await expect(page.getByTestId("user-message").last()).toContainText(
      `/커리어 검사 · ${SKILL} 이번 주 공고`,
    );
    await expect(page.getByTestId("assistant-message")).toHaveCount(1, {
      timeout: 15_000,
    });
    await expect(
      page.getByTestId("assistant-message").last().getByTestId("answer-agent-name"),
    ).toHaveText("맡기기 검사 비서");
  } finally {
    await clearPrimary(isolatedMember.email);
    for (const code of [career, chief]) {
      const removed = await page.request.delete(`/api/agents/${code}`);
      expect(removed.status()).toBe(204);
    }
  }
});

test("맡기기를 받아들이기 전에 다시 눌러도 한 번만 보내고, 답장을 집은 채 보내면 맡기지 않고 칩을 남긴다", async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name === "mobile", "답장은 마우스 사례라 넓은 화면에서 본다");
  const created = await page.request.post("/api/chat", {
    data: { text: `맡기기 중복 검사 ${Date.now()}`, agentCode: "browser" },
  });
  expect(created.ok()).toBeTruthy();
  const { conversationId } = (await created.json()) as { conversationId: string };
  // 목록과 맡기기 응답만 바꾼다. 실제 맡기기는 위 검사와 Control Plane 테스트가 본다.
  await page.route("**/api/chat/skill-delegations/targets", (route) =>
    route.fulfill({ json: [{ agentCode: "career-fake", agentName: "커리어", skills: [SKILL] }] }),
  );
  let posts = 0;
  let release = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/chat/skill-delegations", async (route) => {
    posts += 1;
    await held;
    await route.fulfill({ status: 202, json: {} });
  });
  try {
    await page.goto(`/chat/${conversationId}`);
    const input = page.getByRole("textbox", { name: "메시지" });
    // 맡길 목록을 읽은 뒤에 보낸다. 읽기 전의 글은 보통 보내기로 간다.
    await input.fill("/커리");
    await expect(
      page.getByRole("listbox", { name: "스킬 고르기" }).getByRole("option", { name: `/커리어 · ${SKILL}` }),
    ).toBeVisible();
    await input.fill(`/커리어 · ${SKILL} 공고`);
    await input.press("Enter");
    await expect.poll(() => posts).toBe(1);
    await input.press("Enter");
    release();
    await expect(input).toHaveValue("");
    expect(posts).toBe(1);

    const answer = page.getByTestId("assistant-message").first();
    await answer.hover();
    await answer.getByRole("button", { name: "답장", exact: true }).click();
    await input.fill(`/커리어 · ${SKILL} 이것도`);
    await input.press("Enter");
    await expect(page.getByText("답장은 맡길 때 함께 보낼 수 없어요. 답장을 빼고 보내 주세요.")).toBeVisible();
    await expect(page.getByTestId("reply-chip")).toBeVisible();
    await expect(input).toHaveValue(`/커리어 · ${SKILL} 이것도`);
    expect(posts).toBe(1);
  } finally {
    release();
    await page.unrouteAll({ behavior: "ignoreErrors" });
  }
});

test("작업 화면의 지시 칸 아래에 /스킬 과 /에이전트 이름 · 스킬 안내가 있다", async ({ page }) => {
  await page.goto("/tasks/new");
  await expect(page.locator("#task-instruction-hint")).toContainText("/에이전트 이름 · 스킬");
});

test("비서 상세의 연결 절에는 붙이기와 「외부 서비스 연결하기」 가 없고 비서는 맡긴다고 알린다(스펙 assistant-hub REQ9)", async ({
  page,
  context,
  isolatedMember,
}) => {
  await setSession(context, isolatedMember);
  expect((await page.request.get("/api/me")).ok()).toBeTruthy();
  const chief = await createAgent(page, "연결 검사 비서");
  await usePrimary(isolatedMember.email, chief);
  await connectDemoConnector(isolatedMember.email);
  try {
    await page.goto(`/agents/${chief}`);
    const section = page.getByRole("region", { name: "이 에이전트가 쓰는 연결" });
    await expect(section).toContainText("비서에는 연결을 붙이지 않아요");
    await expect(section.getByRole("button", { name: "붙이기" })).toHaveCount(0);
    await expect(section.getByRole("link", { name: "외부 서비스 연결하기" })).toHaveCount(0);
    const refused = await page.request.put(`/api/agents/${chief}/connections/${DEMO_CONNECTOR.id}`);
    expect(refused.status()).toBe(409);
  } finally {
    await disconnectDemoConnector(isolatedMember.email);
    await clearPrimary(isolatedMember.email);
    const removed = await page.request.delete(`/api/agents/${chief}`);
    expect(removed.status()).toBe(204);
  }
});


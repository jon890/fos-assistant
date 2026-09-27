import { SignJWT } from "../../web/node_modules/jose/dist/webapi/index.js";
import type { Page } from "../../web/node_modules/@playwright/test/index.js";
import {
  CONVERSATION_URL,
  conversationIdOf,
  expect,
  PERSONA_FAMILY_AGENT_CODE,
  setAgentVisibility,
  setSession,
  test,
} from "./fixtures.ts";
import { CONTROL_PLANE_BASE_URL, JWT_SECRET, TEST_EMAIL } from "./settings.ts";

async function controlPlaneToken(): Promise<string> {
  return new SignJWT({ name: "브라우저 테스트" })
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(TEST_EMAIL)
    .setIssuedAt()
    .setExpirationTime("2m")
    .sign(new TextEncoder().encode(JWT_SECRET));
}

/** 공개 식별자로 대화 번호를 얻는다. 번호는 주소와 API 에 나오지 않아 test-support 로만 얻는다. */
async function conversationNumber(conversationId: string): Promise<number> {
  const response = await fetch(
    `${CONTROL_PLANE_BASE_URL}/api/v1/test-support/chat/conversations/${conversationId}/number`,
    { headers: { Authorization: `Bearer ${await controlPlaneToken()}` } },
  );
  if (!response.ok) {
    throw new Error(`대화 번호를 얻지 못했다: ${conversationId} ${response.status} ${await response.text()}`);
  }
  return ((await response.json()) as { number: number }).number;
}

async function sendFirstMessage(page: Page, text: string): Promise<void> {
  await page.goto("/");
  await page.getByRole("textbox", { name: "메시지" }).fill(text);
  await page.getByRole("button", { name: "보내기" }).click();
  await expect(page).toHaveURL(CONVERSATION_URL);
  await expect(page.getByTestId("assistant-message").last()).toBeVisible({ timeout: 30_000 });
}

test("첫 메시지를 보내면 주소가 대화의 공개 식별자로 바뀐다", async ({ page }, testInfo) => {
  await sendFirstMessage(page, `공개 식별자 주소 ${testInfo.project.name} ${Date.now()}`);
  expect(new URL(page.url()).pathname, "대화 화면 주소").toMatch(CONVERSATION_URL);
});

test("내 대화의 옛 번호 주소는 공개 식별자 주소로 넘어가고 메시지가 보인다", async ({ page }, testInfo) => {
  const text = `옛 주소 넘겨 주기 ${testInfo.project.name} ${Date.now()}`;
  await sendFirstMessage(page, text);
  const conversationId = conversationIdOf(page.url());
  const number = await conversationNumber(conversationId);

  await page.goto(`/c/${number}`);
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
  await expect(page.getByTestId("user-message").last()).toContainText(text);
});

test("남의 대화 번호로 옛 주소를 열면 첫 화면으로 간다", async ({ context, page }) => {
  // 씨 뿌린 에이전트는 모두 TEST_EMAIL 만 쓰는 PRIVATE 라 다른 사용자가 대화를 시작하지 못한다.
  // 이 검사 동안만 가족에게 공개하고 끝나면 되돌린다. identity.spec.ts 가 가족 공개 에이전트가
  // 정확히 하나라고 가정하고 있고, Playwright 설정의 workers: 1 이 다른 spec 과 겹치지 않게 한다.
  await setAgentVisibility(PERSONA_FAMILY_AGENT_CODE, "FAMILY", null);
  try {
    await setSession(context, { email: "member@example.com", name: "가족 사용자" });
    // 빈 대화라 Hermes 실행이 돌지 않는다.
    const created = await page.request.post("/api/chat/conversations", {
      data: { agentCode: PERSONA_FAMILY_AGENT_CODE },
    });
    expect(created.ok(), `남의 대화를 만들지 못했다: ${created.status()}`).toBeTruthy();
    const { conversationId } = (await created.json()) as { conversationId: string };
    const number = await conversationNumber(conversationId);

    await setSession(context, { email: TEST_EMAIL, name: "브라우저 테스트" });
    await page.goto(`/c/${number}`);
    await expect(page).toHaveURL(/\/$/);
  } finally {
    await setAgentVisibility(PERSONA_FAMILY_AGENT_CODE, "PRIVATE", TEST_EMAIL);
  }
});

test("공개 식별자 모양이 아닌 대화 주소는 첫 화면으로 간다", async ({ page }) => {
  await page.goto("/chat/abc");
  await expect(page).toHaveURL(/\/$/);
});

test("대문자 공개 식별자로 대화를 열면 소문자 주소로 옮겨진다", async ({ page }, testInfo) => {
  const text = `대문자 주소 ${testInfo.project.name} ${Date.now()}`;
  await sendFirstMessage(page, text);
  const conversationId = conversationIdOf(page.url());

  await page.goto(`/chat/${conversationId.toUpperCase()}`);
  await expect(page).toHaveURL(new RegExp(`/chat/${conversationId}$`));
  await expect(page.getByTestId("user-message").last()).toContainText(text);
});

for (const path of ["/api/chat", "/api/chat/stream"]) {
  test(`${path} 는 공개 식별자 모양이 아닌 대화로 보내면 400 으로 답한다`, async ({ page }) => {
    const response = await page.request.post(path, {
      data: { conversationId: "abc", text: "모양이 틀린 대화", agentCode: PERSONA_FAMILY_AGENT_CODE },
    });
    expect(response.status(), `${path} 응답 상태`).toBe(400);
    expect(((await response.json()) as { code: string }).code, `${path} 오류 코드`).toBe("VALIDATION_FAILED");
  });
}

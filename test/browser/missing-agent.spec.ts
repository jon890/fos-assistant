import { expect, test } from "./fixtures.ts";

const MISSING_ID = "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d";
const MISSING_AGENT_LABEL = "지운 에이전트";

test("에이전트 행이 없는 대화는 지운 에이전트로 보이고 다른 에이전트의 모델과 사진 단추가 없다", async ({
  page,
}, testInfo) => {
  // 목록에는 진짜 대화 뒤에 에이전트 칸이 null 인 줄을 하나 더한다. 에이전트 목록은 그대로 두므로
  // 첫 에이전트의 코드가 이 대화에 채워지면 모델 설정과 사진 단추가 살아난다.
  await page.route("**/api/chat/conversations", async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    const response = await route.fetch();
    const conversations = (await response.json()) as unknown[];
    await route.fulfill({
      response,
      json: [
        {
          id: MISSING_ID,
          title: "에이전트 행이 없는 대화",
          agentCode: null,
          agentName: null,
          updatedAt: new Date().toISOString(),
          provider: null,
          model: null,
          reasoningEffort: null,
        },
        ...conversations,
      ],
    });
  });
  await page.route(`**/api/chat/conversations/${MISSING_ID}/running`, (route) =>
    route.fulfill({
      json: { running: false, executionId: null, startedAt: null },
    }),
  );
  await page.route(
    `**/api/chat/conversations/${MISSING_ID}/messages`,
    (route) => route.fulfill({ json: [] }),
  );

  // 에이전트 목록이 대화 목록보다 늦게 도착하게 붙든다. 빈 코드가 첫 에이전트의 코드로 채워지는 경쟁을 만든다.
  let releaseAgents: () => void = () => {};
  const agentsHeld = new Promise<void>((resolve) => {
    releaseAgents = resolve;
  });
  await page.route("**/api/agents", async (route) => {
    await agentsHeld;
    await route.continue();
  });

  await page.goto(`/chat/${MISSING_ID}`);

  // desktop 은 대화 머리줄에, mobile 은 그 줄이 숨어 셸 윗줄의 제목에 보인다.
  if (testInfo.project.name === "mobile") {
    await expect(
      page
        .locator("header.md\\:hidden")
        .getByText(MISSING_AGENT_LABEL, { exact: true }),
    ).toBeVisible();
  } else {
    await expect(
      page.getByRole("main").getByText(MISSING_AGENT_LABEL, { exact: true }),
    ).toBeVisible();
  }

  const agentsLoaded = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/agents",
  );
  releaseAgents();
  await agentsLoaded;
  // 응답을 받은 뒤 화면이 다시 그려질 시간을 준다. 그 전에는 사진 단추가 없다는 확인이 그리기 전에 통과한다.
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      }),
  );

  await expect(page.getByRole("textbox", { name: "메시지" })).toBeVisible();
  await expect(page.getByTestId("model-tier-settings")).toBeDisabled();
  await expect(page.getByRole("button", { name: "사진 첨부" })).toHaveCount(0);
});

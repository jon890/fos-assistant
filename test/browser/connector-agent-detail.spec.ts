import { connectDemoConnector, disconnectDemoConnector, expect, setSession, test } from "./fixtures.ts";

const MEMBER_EMAIL = "member@example.com";

test.afterEach(async () => {
  await disconnectDemoConnector(MEMBER_EMAIL);
});

test("MEMBER 주인의 커넥터 에이전트 화면은 도구와 스킬 편집 대신 연결 안내를 보인다", async ({ context, page }) => {
  const agentCode = await connectDemoConnector(MEMBER_EMAIL);
  await setSession(context, { email: MEMBER_EMAIL, name: "가족 사용자" });

  await page.goto(`/agents/${agentCode}`);

  await expect(page.getByText("연결 화면에서 이 에이전트의 연결 상태를 관리해요.")).toBeVisible();
  await expect(page.getByRole("heading", { name: /도구/ })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: /스킬/ })).toHaveCount(0);
});
